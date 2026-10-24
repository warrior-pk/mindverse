# 02 — Local storage core (Capture/Memory schema, FTS5, sqlite-vec, dedupe)

Status: ready-for-agent
Spec: .scratch/mindverse-v1/spec.md (Processing + storage rows)
ADR: docs/adr/0003-local-first-storage.md

## Body

Implement the local data core (no UI):

- Room schema, **sync-ready** per ADR-0003: UUID row ids, mutation timestamps, tombstone flags on `Memory` and `Capture` tables.
- `Capture` row: blob path, kind (share/image/text/pdf/audio/notification), source app package, deep link/URL, hash (byte hash; perceptual hash for screenshot blobs), captured-at.
- `Memory` row: title, summary, category, tags[], entities (typed columns or typed child table: dates, prices, places w/ nullable lat/lng, events/deadlines, ticket fields, people), source refs, embedding pointer.
- Attach FTS5 index over (title+summary+tags+extracted text) and sqlite-vec (loadable extension) single-vector column per Memory — schema chunk-ready (a future chunk table must slot in without migration pain; document this in schema comments).
- Hash-dedupe at the ingestion boundary: exact-dup silent skip, near-dup (same pHash) flagged for later merge prompt.
- Keystore-encrypted settings persistence for provider keys (ADR/spec technical defaults).

## Accepted when
Inserting a Capture + synthesized Memory exercises FTS query, vector load, and dedupe logic in unit tests (in-memory DB); tombstones and mutation timestamps land on updates/deletes.
