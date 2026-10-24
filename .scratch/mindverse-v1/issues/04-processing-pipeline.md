# 04 — Processing pipeline (ingest → extract → embed, WorkManager)

Status: claimed
Spec: .scratch/mindverse-v1/spec.md (Processing pipeline)

## Body

Implement the capture→Memory pipeline as WorkManager expedited jobs (constrained: network-not-required for pre/post steps):

1. **Ingest**: store Capture row + blob, hash-dedupe (issue 02), enqueue Extract.
2. **Extract**: build prompt per Capture kind (image→vision, text/URL→scrape-or-passthrough) with the structured-output contract: title, summary, category, tags, entities {dates, prices, places (nullable lat/lng), events/deadlines, ticket fields {PNR, QR/barcode coords, seat, gate, departure}, people {name/handle}}. Null-able on model miss (extra API calls forbidden).
3. **Embed**: on-device embedding of (title+summary+tags+extracted text) via a local MiniLM-class ONNX runner (bundles with a `ModelRegistry` so a better model can swap later), writes to sqlite-vec.
4. **Pin probe**: ticket fields present → create Pin row with departure-derived TTL (rendered later as notification/Live Update in M3).
5. **Chip**: notification chip while queued processing (issue 06 owns launch, pipeline owns lifecycle updates); tap deep-links to the resulting Memory.
- Retry: exponential backoff, cap, then dead-letter state visible in-app.
- FGS types: `dataSync` (34) / `mediaProcessing` (35+) where long-running applies.

## Accepted when
A synthetic Capture (image + text fixtures) round-trips through the pipeline into an indexed Memory without UI; failure path leaves a dead-lettered Capture with visible state and no data loss.

## Comments

- 2026-10-25 (implemented): Pipeline lives in `core/pipeline` (processor + controller +
  scheduler + workers + chip) with `core/embed` for the model seam. DB v3 adds capture
  processing state / memory linkage / mime type and a `pin` table (auto-migration 2→3).
  - **Extract**: image/PDF → `AiGateway.vision`; text/URL → `AiGateway.chat` with the
    structured-output schema. URL scraping is deliberately NOT done in M1 (it would add an
    egress path outside the chokepoint) — the URL + share body are passed through instead.
    One call per stage; model misses degrade to nulls (no second call).
  - **Retry**: WorkManager exponential backoff (30s base) with a hard 5-attempt cap in the
    processor. Transient (network/5xx/408/429/unparseable reply) retries; terminal failures
    (egress toggle off, missing key, unconfigured provider, 4xx) dead-letter immediately.
    Dead-letter keeps the Capture row + blob untouched and sets `processing_state`/
    `processing_error`; `CaptureDao.listDeadLettered()` is the in-app surface seam (06).
  - **Embed**: on-device only, through `ModelRegistry`. MiniLM ONNX slot is declared but
    not bundled; the deterministic feature-hashing engine (384-d, L2-normalized) is the
    built-in fallback so Memories are indexed today. Swapping the model = asset + factory.
  - **Pin probe**: any TICKET entity with a departure creates one pin with
    `expires_at = departure + 24h` grace (departure-derived TTL; M3 renders it).
  - **Chip**: single notification slot (channel `processing`), updated by pipeline stages,
    cancelled on success, retained with the failure reason on dead-letter; tap deep-links
    via `mindverse://memory/<id>` (scheme filter added to MainActivity).
  - **FGS**: expedited jobs use `getForegroundInfo()` with `dataSync` on API 34 and
    `mediaProcessing` on 35+; WorkManager's default initializer is removed in favour of
    `MindverseApp`'s `Configuration.Provider` + `HiltWorkerFactory`.
  - Tests: `PipelineProcessorTest` (text+image round-trip → FTS+vec, Pin, dead-letter with
    no data loss, terminal vs transient retry, dedupe never queued), `ExtractionParserTest`,
    `FeatureHashingEmbeddingEngineTest`. Full suite green on both flavors; version 0.4.0.
