# 04 — Processing pipeline (ingest → extract → embed, WorkManager)

Status: ready-for-agent
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
