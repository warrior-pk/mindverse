# 05 — Capture receivers: Share sheet + QSTile + screenshot watcher

Status: ready-for-agent
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
