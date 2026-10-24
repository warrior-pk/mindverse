# 05 — Capture receivers: Share sheet + QSTile + screenshot watcher

Status: resolved
Spec: .scratch/mindverse-v1/spec.md (Triggers rows, M1 scope)

## Body

Implement the three M1 triggers; all funnel into the same pipeline entry (issue 04):

1. **Share Sheet**: `ACTION_SEND` multifilter handles URL / image / PDF / text; Extract capture kind + source identity (referrer extras where available); no permissions beyond intent.
2. **Quick Settings Tile**: tap opens dashboard (in-app) for M1; wire TileService lifecycle + shade collapse.
3. **Screenshot watcher**: `ScreenCaptureCallback` (minSdk 34, unconditional) — prompt-first behavior: local notification "Add to Mindverse?" with a one-tap ingest action; on accept, capture bitmap + enqueue. Settings exposes an auto-ingest mode that defaults OFF (R4-4).
- All trigger plumbing must respect egress toggles (nothing should auto-send when voice/audio egress is off, irrelevant for M1 but keep the chokepoint call shape).
- Failed intents/source-partials degrade to best-effort source context with NULL fields rather than rejecting the capture.

## Accepted when
Each trigger produces a Capture row end-to-end on an emulator; watcher respects default prompt mode.

## Comments

- 2026-10-25 (implemented): triggers live in `dev.shipyu.mindverse.triggers` (share /
  tile / screenshot) with shared helpers in `core/data/ingest` (`CaptureBlobStore` —
  app-private blob storage + SHA-256 content hash; `ImageFingerprint` — dHash perceptual
  hash for near-dup detection).
  - **Share**: `ShareReceiverActivity` trampoline (text / image / PDF ACTION_SEND).
    Best-effort source context: referrer → sourceApp, first http(s) URL in shared text →
    deepLink, MIME from resolver/type; missing pieces degrade to nulls. Text shares are
    stored as UTF-8 blobs so extraction reads them back. Dedupe drops exact dupes silently.
  - **Tile**: `MindverseTileService` opens the dashboard via
    `startActivityAndCollapse(PendingIntent)` (collapses the shade; API 34+).
  - **Watcher**: `ScreenCaptureCallback` registered with the activity lifecycle (the API
    only reports screenshots while the app is visible — accepted per spec R2-4). The
    callback carries no bitmap, so `MediaStoreScreenshotSource` looks up the newest
    Screenshots row (READ_MEDIA_IMAGES; silent no-op without the grant — ticket 08's
    first-run flow requests it). Prompt-first: "Add to Mindverse?" notification with a
    one-tap accept broadcast; auto-ingest defaults OFF (`WatcherSettings` DataStore,
    toggle surfaced on the Privacy screen until ticket 06 restructures nav).
  - Tests: `ShareIntentIngestTest` (text/image/pdf classification, URL trim, dedupe,
    empty degrade), `ScreenshotIngestHandlerTest` (prompt-first default, auto-ingest
    skip, missing screenshot no-op), `ImageFingerprintTest` (dHash stability, unreadable
    → null; native graphics mode). Full suite green on both flavors.
