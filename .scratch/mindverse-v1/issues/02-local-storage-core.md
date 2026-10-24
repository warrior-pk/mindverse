# 02 — Local storage core (Capture/Memory schema, FTS5, sqlite-vec, dedupe)

Status: resolved
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

## Comments

`.scratch/mindverse-v1/issues/02-local-storage-core.md` — storage core implemented; all acceptance behaviors covered by JVM unit tests (Robolectric, in-memory DB on the androidx BundledSQLiteDriver). Verified: full unit suites (both flavors), `assemblePlayDebug` + `assembleFdroidDebug`, strict lint both flavors.

- **Stack**: Room 2.7.2 (KSP driver-mode codegen confirmed in generated sources) on `androidx.sqlite:sqlite-bundled` 2.7.1 — its bundled SQLite is compiled with `SQLITE_ENABLE_FTS5=1` + `SQLITE_ENABLE_LOAD_EXTENSION=1` (verified in the androidx build scripts), which is what makes FTS5 + sqlite-vec work deterministically (Android framework SQLite has no FTS5 and no extension loading). DataStore 1.3.0 for settings; Robolectric 4.17 for tests.
- **sqlite-vec**: vendored prebuilt loadable binaries v0.1.9 (checksums verified against the release `checksums.txt`) — `app/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86,x86_64}/libvec0.so` for device, `app/src/test/resources/native/linux-x86_64/vec0.bin` for JVM tests. Entry point `sqlite3_vec_init`, registered via `BundledSQLiteDriver.addExtension(path, entry)` (API added in androidx.sqlite 2.6.0).
- **AGP quirks hit**: (1) AGP silently strips `*.so` from unit-test Java resources — the test copy is stored extension-less (`vec0.bin`; dlopen doesn't care about extension); (2) the android-variant `NativeLibraryLoader` does `System.loadLibrary("sqliteJni")`, whose `java.library.path` includes `app/src/test/jniLibs` — `libsqliteJni.so` (linux_x64 from the published `sqlite-bundled-jvm` jar) is vendored there.
- **Room driver-mode gotchas (android artifact 2.7.2)**: `openHelper`/`compileStatement`/top-level `androidx.room.withTransaction` are legacy-only and hard-fail with a driver configured; all raw access + transactions in `MindverseDatabase` go through `useWriterConnection`/`useReaderConnection` + `Transactor.withTransaction(IMMEDIATE)` — suspend DAO calls inside the block reuse the confined connection (verified). `RoomDatabase.Callback` must override the `SQLiteConnection` overloads in driver mode (SupportSQLiteDatabase overloads never fire).
- **Schema**: `captures` + `memory` + `memory_item` entities (sync-ready per ADR-0003: UUID PKs, created_at/updated_at stamps, deleted tombstones; captured_at distinct from ingestion time; `near_duplicate_of` carries the merge-prompt flag). Aux indexes are **code-owned**, outside Room's schema version: `memory_fts` (FTS5 external-content over `memory`, rowid-mapped, trigger-synced AI/AD/AU, porter tokenizer, bm25 ranking) and `memory_vec` (vec0, `memory_id TEXT PRIMARY KEY`, `embedding float[384] distance_metric=cosine`). All queries join `memory` and filter `deleted = 0` so tombstones never surface; tombstoning also eagerly deletes the vec row. CHUNK-READY documented in `MindverseDatabaseSchema` (1 card = 1 chunk in v1; future chunk table reuses the table shape; aux rebuilds need no Room migration). Schema exported to committed `app/schemas/…/1.json` as the migration contract.
- **Dedupe** (`CaptureIngestionService.ingest`): exact-dup (equal content hash) silently skipped, no row; near-dup (equal pHash) stored + flagged for the later merge prompt; tombstoned captures don't shadow re-ingestion. v1 near-dup matching is exact pHash equality — hamming-distance thresholds deferred (noted as a follow-up).
- **Typed entities**: single child table `memory_item` with typed nullable columns (dates/deadlines via start/end epoch ms, prices via amount-micros + ISO-4217 currency, places via nullable lat/lng, ticket code/seat/gate, people via name + handle) — matches the ticket's "typed child table" option; barcode/QR *coordinates* deliberately deferred to the Vault module (M3) as capture-time geometry, not storage.
- **Settings** (`EncryptedSettingsStore`): keys sealed by injected `KeyCipher` before storage; production binding `KeystoreKeyCipher` = AES-256/GCM with a non-exportable AndroidKeyStore key (IV-prepended framing); corrupt/absent ciphertext degrades to null. Keystore cipher itself is JVM-untestable — flagged for later instrumentation tests on-device.
- **Tests** (seams as pre-agreed): `CaptureToSearchableMemoryTest` (tracer: capture → memory → FTS hit ×3 sources → KNN self-match at distance 0), `CaptureIngestionDedupeTest` ×6, `MemoryRepositoryTest` ×8 (entity round-trip, timestamps on update/tombstone, re-embedding replaces vec row, tombstones drop from both indexes, FTS re-sync via trigger, quoted-phrase FTS injection safety), `EncryptedSettingsStoreTest` ×5 (round-trip, ciphertext-at-rest, removal, corrupt→null, blank-id rejection). One test-data bug fixed during the loop (near-parallel embeddings made an orthogonality assertion vacuous).

## Review follow-ups (code-review pass, fixed)

- Standards: GLOSSARY `Memory / Avoid: card` — all 8 KDoc occurrences renamed to `Memory` (`MemoryRepository`, `MemoryEntity`, `MemoryItemDao`, `MemoryItemEntity` ×2, `MindverseDatabaseSchema`); "Gym membership card" test title renamed; `andGem` typo fixed. Embedding-pointer wording drift closed by documenting on `MemoryEntity` that `memory_vec(memory_id)` keyed by the row id *is* the embedding pointer (no separate column).
- Spec gaps closed: `CaptureDao.update` + `MemoryItemDao.updateItem/getItem/tombstone` added (item/capture rows now have full update/tombstone paths with mutation stamps; covered by `captureUpdateStampsUpdatedAtWithoutTouchingCreatedAt`, `memoryItemUpdateStampsAndTombstoneHidesButRetainsRow`).
- Ingest race: `CaptureIngestionService.ingest` now runs dedupe-check + insert atomically inside one immediate writer transaction; content-hash match takes precedence over pHash (covered by `contentHashMatchWinsOverPHashMatchOnDifferentRow`).
- Tombstone FTS remnant: UPDATE trigger now deletes the old FTS entry on every update and re-inserts only `WHERE new.deleted = 0`; `attach()` drops + recreates triggers (upgrade-safe) and purges pre-existing tombstoned rows' term entries; `SchemaCallback.onOpen` always attaches. Key finding while fixing: for FTS5 external-content tables the `'delete'` marker removes *term entries* (`MATCH` → 0) but the rowid still enumerates in full scans until segment merge — so `COUNT(*)` is not a valid hygiene measure; the regression test asserts MATCH-absence without the `deleted=0` join (verified sensitive: fails on the old trigger, passes on the new). Schema KDoc records the precise guarantee; unused `ftsDocCountRaw`/`CountRow` removed.
- Left open (documented): production `KeystoreKeyCipher` path is JVM-untestable — needs on-device instrumentation tests (later ticket); v1 near-dup stays exact-pHash equality, hamming-distance matching deferred as originally noted.
