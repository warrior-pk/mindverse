# 06 — Memory surfaces: Gallery list, capture review sheet, Memory detail

Status: ready-for-agent
Spec: .scratch/mindverse-v1/spec.md (feature scope, UI)

## Body

Build the Compose UI over the spine:

- **Gallery**: the home screen of Memories — thumbnail grid/list, category filter chips, time-range filter, source-app filter; dedupe merge prompt surface for near-dup captures (R5-3).
- **Capture review sheet**: launched from watcher-prompt notification or share-target preview; shows the Capture immediately (even pre-extraction), lets user edit source context, confirm/discard.
- **Memory detail**: full card — title, summary, category, tags, entity chips (tappable entity types), ticket block when present, source deep-link + "jump back to origin" action, blob/thumbnail view.
- **Ask entry point** (placeholder screen until issue 07 lands) + bottom-level nav between Gallery / Ask / Privacy (issue 03 screen) / Support (issue 09).
- Empty/error/loading states designed; no invented flows beyond spec.

## Accepted when
Gallery browsing, review sheet confirm/discard, Memory detail with deep-link back work on emulator against synthetic data; near-dup merges resolve via prompt.

## Comments
- 2026-10-24: Nav updated to include the Support screen per post-grill decision (issue 09 added to v1 scope).
