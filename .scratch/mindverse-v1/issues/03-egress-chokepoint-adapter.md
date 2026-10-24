# 03 — Egress chokepoint + provider adapter (Gemini + OpenAI-compatible)

Status: resolved
ADR: docs/adr/0002-egress-chokepoint-invariant.md
Spec: .scratch/mindverse-v1/spec.md (Egress & Privacy screen rows)

## Body

Implement the Egress Policy module and the LLM adapter:

- Single chokepoint API every outbound AI call must use: input = requestId + reason code + payload type (chat/vision/embed), applies PII masking regex families (phone/email/ID/generic card/OTP-like numbers) + logs `{captureId?, feature, payload classes, provider, bytes-out, at}` to a Room `egress_log` table.
- Per-feature toggles (e.g. voice audio egress) checked at the chokepoint; a denied toggle short-circuits with a typed failure the feature can degrade on.
- Provider-neutral interface: Chat(stream, structured-output), Vision(structured-output), Embed (Embed stays a **local no-op stub here**; real one is issue 04/07).
- Implementations: Google Gemini (AI Studio key) and OpenAI-compatible HTTP with custom base URL (OpenRouter/DeepSeek/Ollama/LM Studio). Keys read from the Keystore-encrypted store.
- Privacy screen UI: chronological egress log grouped by day, per-feature toggles. (This is the only UI in this issue.)

## Accepted when
Every call site compiles only via the chokepoint (make raw provider types internal), masking unit-tested on a snapshot corpus, log rows written on both success and failure, Privacy screen renders live from `egress_log`.

## Comments

All acceptance behaviors covered by JVM unit tests (Robolectric, in-memory DB on the BundledSQLiteDriver). Verified: full unit suites both flavors (104 tests, 0 failures), `assemblePlayDebug` + `assembleFdroidDebug`, strict lint both flavors.

- **Structure** (ADR-0002 held structurally, not by convention): public surface = `AiGateway` interface + request/reply/failure/stream types in `core/egress`; the chokepoint implementation `EgressAiGateway` and all wire types (`AiTransport`, `TransportRequest/Message/Image`, `TransportRole`, `TransportCompletion/Event/Failure`, both transports) are `internal` and live in `core/ai`. DI (`EgressModule`) exposes only `AiGateway` — transport construction happens inside the provider so no internal type ever appears in a signature; OkHttp imports exist only in `core/ai` + the DI module. A call site cannot compile a provider path without the chokepoint.
- **Chokepoint pipeline order** (per call): per-feature toggle gate (DENIED row, 0 bytes, transport never touched) → provider config check (empty Gemini model / OpenAI-compatible base URL+model → `Unconfigured`, FAILED row) → BYOK key from `EncryptedSettingsStore` (`MissingKey`, FAILED row) → PII masking (`PiiMasker`, detected payload classes recorded) → transport dispatch → one `egress_log` row per terminal state. Payload classes are the classes of what was actually processed, so early-gate failure rows truthfully carry none; wire failures after masking carry them.
- **Logging**: every terminal state writes a row — SUCCESS, FAILED (wire failures carry the attempted `bytes_out`; structured-output parse failures carry the real size), DENIED (toggle), and user-cancelled streams (FAILED "cancelled by user before completion (bytes out unknown)" — the wire payload size is transport-owned, so no invented figure; ADR-0002 sign-off: partial egress is never invisible). `StructuredOutput` is a lenient fence-stripping, string-aware balanced-brace extractor shared by streaming + non-streaming.
- **Transports**: Gemini = `x-goog-api-key` header (never in URL) over `…/v1beta/models/{model}:generateContent|:streamGenerateContent?alt=sse`; structured output = `responseMimeType` + `responseSchema` with a neutral→OpenAPI dialect translator (uppercase types, `additionalProperties` dropped). OpenAI-compatible = custom base URL + `/chat/completions`, Bearer auth, `response_format: json_schema` (schema-guided, deliberately not strict-mode for cross-server compatibility), SSE `delta.content` until `[DONE]`; vision images travel data-URL. Shared plumbing in `TransportHttp` (post, status→HTTP failure w/ bounded body summary, IOException→Unreachable, blocking SSE loop) + `SseSupport`. OkHttp pinned 5.3.2 (5.5.0's `okhttp-android` AAR needs compileSdk 37; repo is AGP 8.13.2/compileSdk 36).
- **Providers/settings**: `AiProvider` (GEMINI / OPENAI_COMPATIBLE) + `ProviderSettingsStore` (plain DataStore `mindverse_ai_settings`; default `gemini-flash-latest` refresh alias). Keys stay in ticket 02's `EncryptedSettingsStore` under ids `gemini` / `openai_compatible`. `EgressToggles` (plain DataStore `mindverse_privacy`, defaults enabled) gates at call time. `embed` = local no-op stub; the on-device model replaces it in tickets 04/07.
- **DB**: `egress_log` entity registered, schema 1→2 via `@AutoMigration` (committed `2.json`), index on `at`; `ListConverters` for payload classes. UI: `PrivacyScreen` (Material3, live `observeRecent(300)` flow joined with toggles through `PrivacyController` — Compose-free, day grouping is a pure JVM-tested function), hosted temporarily in `MainActivity` until the nav shell (ticket 06). `MainActivity` got `@AndroidEntryPoint` + `@Inject` controller.
- **Manifest/network**: INTERNET permission; `network_security_config.xml` permits cleartext only for `localhost`/`127.0.0.1` (Ollama/LM Studio on this device). Known limitation: a LAN Ollama host (e.g. `http://192.168.x.x`) stays blocked in v1 — deliberate TLS-everywhere-except-loopback stance (ADR-0002 aligned).
- **Tests** (seams as pre-agreed): `PiiMaskerSnapshotCorpusTest` (committed 20-record corpus), `EgressAiGatewayTest` ×10 (fake `AiTransport` at the wire boundary: denial, mask+log, wire failure, missing key, structured parse/failure, streaming success/failure/cancellation-free paths, embed no-op, vision image encoding), `EgressLogDaoTest` ×4, transport wire tests ×8 vs MockWebServer (URL/auth/envelope/SSE/schema dialect/error mapping — the only sanctioned mocking spot), `PrivacyControllerTest` ×2 (day groups newest-first, toggles live). Test-only `EncryptedSettingsStore`/DataStore file names are parameterized for per-test isolation.
- **Versioning**: `feat:` → minor bump 0.2.1 → 0.3.0 (versionCode 4), per the versioning policy.

## Review follow-ups (code-review pass, two-axis sub-agents: standards + spec; fixed)

- Standards (Duplicated Code): 60-line `httpExecute`/`httpExecuteStream` pairs in both transports collapsed into shared `TransportHttp` (status mapping, IOException→Unreachable, SSE read loop); transports keep only their headers/URL/delta decoding.
- Standards (repetition/clumps): unified gateway terminal path `finish()` (structured parse + one log row) shared by streaming + non-streaming; `logRow` now takes `CallRef` instead of 8 spread primitives; wire-building extracted (`chatWire`/`visionWire`); `Gate.Ready` object used directly; masker hoisted (stateless, one instance).
- Spec/ADR-0002: user-cancelled streams now log an explicit FAILED row (previously silent) — "every egress attempt is user-visible."
- Standards: `EgressToggles.states()` narrowed its catch to `IOException`/`CorruptionException` (was swallowing all throwables into empty prefs, which would silently reset toggles on any bug — vs `ProviderSettingsStore`'s correct filter); `TransportRole` made `internal` to match the rest of the wire types; FQN import in `MainActivity` fixed.
- Spec notes (no change needed): payload classes legitimately empty on early-gate failure rows (masking runs only after gates pass — truthful), `recent()` DAO helper retained for validation despite the screen being `observeRecent`-driven.
- Left open (documented): the BYOK provider/key selection UI is intentionally absent from this ticket (privacy screen is the only UI here) — no production caller exists for `setProvider`/`setOpenAiCompatible` yet, so the OpenAI-compatible path is Unconfigured-by-default until the settings surface lands (ticket 08/09); LAN-host Ollama needs a follow-up decision before voice-transcription testing (M2).
