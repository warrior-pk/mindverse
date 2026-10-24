# Mindverse v1 — Spec

Status of this document: ratified by grilling session (Rounds 1–6, Oct 24 2026). Vocabulary is normative per `GLOSSARY.md` at the repo root.

## What Mindverse is

A standalone system-wide second-brain app for Android (native Kotlin/Compose), inspired by a commercial phone-maker's memory-companion app but built within third-party sandbox limits. It captures content from the user's device, processes it into structured Memories, and makes them searchable and Ask-able locally, with time-sensitive Pins surfaced proactively. Local-first; the phone is the source of truth.

Reference teardown of the inspiration app (a commercial OEM memory app): `docs/private/reference-app-analysis.md` (local analysis of a decompiled APK; vendor identities genericized to `refvendor`/`RefAssist`/`refcloud` placeholders; **gitignored — never committed, consultable locally only**).

## Ratified decisions (round by round)

| # | Fork | Decision |
|---|---|---|
| R1-1 | Platform | Native Kotlin + Compose, Android-first; iOS = separate future native app |
| R1-2 | Economics | Hybrid: BYOK (Gemini + OpenAI-compatible base URL) + on-device light paths; hosted subscription later behind flag; app usable keyless |
| R1-3 | Privacy | Egress chokepoint from day one (ADR-0002) |
| R1-4 | Sync | Local-first, no cloud sync; sync-ready schema (ADR-0003) |
| R1-5 | Triggers v1 | Share sheet, QSTile, screenshot watcher, voice memo; edge handle excluded |
| R1-6 | Modules v1 | Auto-tagging/extraction foundation +(ticket fields on card); **no OTP**; plus **Import** of existing gallery albums with user permission |
| R1-7 | Distribution | Sideload-first (ADR-0001); GitHub Releases + in-app update check AND F-Droid |
| R1-8 | Vocabulary | Capture / Memory / Pin / Collection (see GLOSSARY.md) |
| R2-1 | Tier-2 triggers | Edge handle + volume chord (AccessibilityService) UNLOCKED into v1 behind opt-in, because sideload-first (ADR-0004) |
| R2-2 | Updates | GitHub Releases + in-app check, F-Droid as second channel |
| R2-3 | Import | Opt-in per album; on-device OCR evaluates everything; VLM enrichment deferred |
| R2-4 | minSdk | **34** (target 36). Watcher = `ScreenCaptureCallback`, no fallback branch |
| R2-5 | Storage | Room + FTS5 + sqlite-vec, WorkManager for jobs |
| R2-6 | STT | Gemini one-shot: audio file → single structured-output call returning transcript+summary+tasks[] |
| R2-7 | LLM adapter | Provider-neutral interface; Gemini first |
| R3-1 | Live transcript | Live words while recording via on-device `SpeechRecognizer` (API 31+, minSdk 34 satisfies) |
| R4-1 | Memory card fields | Core card + Generic entities + Ticket fields + People fields. No Code/TTL fields in v1 |
| R4-2 | Embeddings | On-device small model (MiniLM-class ONNX) → sqlite-vec |
| R4-3 | Retrieval | 1 card = 1 chunk in v1; schema chunk-ready (long OCR text stays whole; future chunk table fragments transparently) |
| R4-4 | Watcher default | Prompt-first: local notification "Add to Mindverse?" one-tap; auto-ingest mode exists but defaults OFF |
| R4-5 | Processing pill | Notification chip; renders as Live Update on API 36; no overlay in v1 |
| R4-6 | BYOK surface | Gemini AI Studio key + OpenAI-compatible custom base URL (OpenRouter/DeepSeek/Ollama/LM Studio) |
| R4-7 | Identity | Name **Mindverse**, author **warrior-pk** (github.com/warrior-pk), repo `github.com/warrior-pk/mindverse`, license **Apache-2.0** (copyright line: `Copyright 2026 warrior-pk`), applicationId **`dev.shipyu.mindverse`**; deep-link scheme **`mindverse://`**; showcase site **`https://mindverse.shipyu.dev`**; privacy policy hosted on the same domain later |
| R5-1 | Egress UX | User-facing Privacy screen: chronological outbound-call log + per-feature toggles (e.g. disable voice-audio egress) |
| R5-2 | Capture context | Accessibility captures record package + best-effort browser URL scrape (node traversal), silent failure elsewhere |
| R5-3 | Dedupe | Content hash (byte hash for files, perceptual hash for screenshots): exact dupes silently ignored, near-dupes → in-app merge prompt |
| R5-4 | Ask output | Streaming answers, strictly grounded, source-Memory citation chips (tappable, deep-link to card) |
| R5-5 | OCR engine | Gradle flavor split: `play` = ML Kit text+barcode; `fdroid` = FOSS OCR (PaddleOCR-ONNX or Tesseract) behind one interface |
| R5-6 | Topology | Single `:app` module, package-by-feature, Hilt; boundaries = packages until real pain demands modules |
| R5-7 | Milestone 1 | Spine-first (below) |
| R6-1 | Config | No config server, ever: signed `config.json` in the GitHub repo, fetched via the update-check code path, cached locally, built-in defaults as fallback (ADR-0005) |
| R6-2 | Kill-switches | OFF-only: remote Config may force a feature off (overrides local opt-in), never on |
| R6-3 | Refresh | Config piggybacks the update check + a 24h-debounced cold-start check; no background poller, no WorkManager periodic |
| R6-4 | Integrity | Ed25519-signed config, pubkey baked into APK; invalid/missing → silent discard, defaults used |
| R6-5 | Updates | In-app update: GitHub Releases API `versionCode` → newer → cached APK → installer intent (user confirms; no Play In-App Updates API). No code hot-fix path on Android |
| R6-6 | Egress | Config fetch logged on the Privacy screen as a non-AI outbound row with a per-feature toggle |

## Technical defaults (decided, not grilled)

- Key storage: Android Keystore-encrypted persistence (mirrors the OEM app's `SpeechEngineManagerKeyAlias` AES+Keystore pattern from the teardown).
- Voice memo recording runs behind a `microphone`-type foreground service.
- Extraction jobs run behind `dataSync` (API 34) / `mediaProcessing` (API 35+) FGS types as required.
- Voice memo cap: 60s default, adjustable.

## Feature scope v1

### Triggers (all in v1)
1. **Share Sheet** (`ACTION_SEND` receiver): URL / image / PDF / text from any app.
2. **Quick Settings Tile** (`TileService`): triggers voice memo or opens dashboard.
3. **Screenshot watcher** (`ScreenCaptureCallback`, minSdk 34): prompt-first notification.
4. **Voice memo** (tile/edge-handle trigger): 60s record, live words via on-device SpeechRecognizer, Gemini one-shot processing.
5. **Floating edge handle** (SYSTEM_ALERT_WINDOW, opt-in): tap = capture, swipe-in-hold = voice memo.
6. **Volume chord** (AccessibilityService `onKeyEvent`, opt-in): simultaneous VolUp+VolDown 400ms hold → capture.

All produce uniform **Captures** into the same deduped pipeline queue (content-hash dedupe; perceptual hash for screenshots). Milestone slicing (which triggers ship in M1 vs M2) is defined under Milestones below.

### Import
Opt-in per album (e.g. Screenshots). OCR-first on-device pass for all; VLM enrichment deferred until the user's own BYOK budget allows; same Capture→Memory pipeline.

### Processing pipeline
Capture stored (row + blob + source context) → WorkManager queue → Egress-policy-gated extraction (VLM structured output: title, summary, category, tags, entities {dates, prices, places+lat/lng, events/deadlines, ticket fields{PNR, QR/barcode coords, seat, gate, departure}, people{name/handle}}) → on-device embedding → FTS+vec index. Notification chip while processing; deterministic retry/backoff.

### Ask
Local hybrid retrieval (FTS5 BM25 + sqlite-vec cosine + metadata filters) → top-K Memories as context → streaming response, strictly grounded, citation chips. No query rewriting agent in v1.

### Egress & Privacy screen
Single chokepoint module: PII masking, per-request reason codes, chronological log (which Capture, what content classes, which provider, when). Per-feature toggles (e.g. voice audio egress on/off). User-facing Privacy screen is the UI of this log.

### Support screen
Static, zero-backend, zero-egress support/donations screen in the nav (decided post-grill, added to v1):

1. **India card → UPI** (0% fee): QR rendered locally from `upi://pay?pa=<VPA>&pn=warrior-pk&cu=INR&tn=Mindverse+support`, "Pay via UPI app" button firing `ACTION_VIEW` in `createChooser` (system chooser lists GPay/PhonePe/Paytm/…), amount left empty for the payer, plus a "Copy VPA" fallback for apps with flaky `upi://` handling.
2. **Global card → GitHub Sponsors** (0% fee, GitHub covers payment processing): button opening `github.com/sponsors/warrior-pk` (one-time sponsorships supported) + "Copy link" fallback. Setup is a one-time human step: Stripe Connect Express onboarding *through GitHub's dashboard* (India is on GitHub's supported-countries list — Sponsors does not require the standalone Stripe account, which is invite-only in India).
- **Buttons ship as data (remote-config-able URL strings), so fallbacks are config swaps, not code changes** — mechanism ratified in Round 6: signed remote Config per ADR-0005; these URLs are its built-in defaults.
- **Buy Me a Coffee is the recorded international fallback (5% fee) — deliberately deferred to a later update**, enabled by swapping the Sponsors URL if Sponsors onboarding ever fails. (Standalone Stripe in India is invite-only since May 2024 per RBI PA-CB rules — do not plan on direct Stripe onboarding.)

### Post-v1 backlog (not in v1)
Travel Map module; Flash Memory TTL auto-destruct; OTP notification pinning (NotificationListenerService); warranty/subscription radar; read-it-later TTS briefing; Reel-to-checklist; full-page document capture (ACTION_SCROLL + OpenCV stitch — note FLAG_SECURE surfaces are impossible to capture, incl. banking/UPI/Incognito); short-form: **Direct SMS scraping is a Play/redaction landmine — use NotificationListenerService instead**; hosted-subscription backend; E2EE backup (M3). See ADRs.

## Milestones

- **M1 "Spine"** (first usable install-on-own-phone build): Share + Tile + watcher prompt → pipeline → card UI → Ask with FTS+embeds + static Support screen + in-app update check + Config fetch/verify (ADR-0005). No voice, no edge handle, no accessibility tier, no Vault.
- **M2**: Voice memo (STT one-shot + live words), edge handle, volume chord + a11y opt-in wizard, Import gallery scan.
- **M3**: Vault (tickets: barcode crop via ML Kit/play / FOSS alternative, Live Updates pre-departure, transient surfaced Pins as a unified temporal-pin system), Privacy screen hardening, E2EE backup.

## Open items carried forward

- fdroid OCR engine concrete pick (PaddleOCR-ONNX vs Tesseract) — decide at M2.
- F-Droid inclusion metadata (reproducible-build recipe) — needed at M2, not M1.
