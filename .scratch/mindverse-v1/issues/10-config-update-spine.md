# 10 — Config + in-app update spine

Status: ready-for-agent
Blocked by: 03, 08
Spec: .scratch/mindverse-v1/spec.md (ratification rows R6-1..R6-6), docs/adr/0005-github-signed-config-no-server.md

Remote Config (ADR-0005) and the in-app updater: one fetch code path, one trust story, no server.

## Body

### Remote Config
- `config.json` (+ `config.json.sig`) committed at the repo root; contents: `schemaVersion` (int), Support URLs (`sponsors_url`, `upi_vpa`, `bmc_fallback_url` — defaults mirror the constants ticket 09 uses), and OFF-only `kill_switches` map (per-trigger hard-off, e.g. `screenshot_watcher`).
- Ed25519 verify with the pubkey baked in as a build constant; invalid/missing/unsigned → silent discard, built-in defaults used. Ed25519 must be FOSS-flavor-safe (`java.security` or Tink-class, no GMS dependency).
- Fetch rides the existing update-check HTTPS path: manual check + cold-start check debounced to once per 24h. Never `WorkManager` periodic. Egress-free failure handling (no retry storm).
- OFF-only semantics: kill-switch forces the feature off even when locally opted in; config can never enable anything.
- Fetch is logged via the egress chokepoint (ticket 03) as a non-AI outbound row, with a per-feature toggle on the Privacy screen; toggled off → skip fetch, defaults continue.
- Unknown keys silently ignored (old APKs read only keys they know); `schemaVersion` bump ships together with the APK release that understands the new schema. No generic migration layer.

### In-app updater
- Query the GitHub Releases API on the same fetch path; compare `versionCode`. If newer: stream the release APK to cache, then fire the installer `ACTION_VIEW` intent (user confirms — the sideload contract). No Play In-App Updates API. No download/retry running behind a foreground service that isn't already required.

### Non-goals
- No config server, no Firebase Remote Config, no per-user targeting/rollout telemetry.
- No two-way kill-switches, no remote-enabling of features.
- No code hot-fix channel (does not exist on Android); code fixes = APK updates only.

## Accepted when
- Fresh install, airplane mode: Support screen renders from defaults, no crash, no egress attempt.
- TAMPERED/unsigned config (test fixture) is discarded silently and defaults apply; valid signed config values appear in the app.
- Privacy-screen toggle off blocks the config fetch (defaults continue answering).
- Kill-switch set in config forces the chosen trigger off despite local opt-in; un-set restores local user choice.
- Update path detects a higher `versionCode` fixture and fires the installer intent; equal/lower does nothing.
- Builds and passes on both `play` and `fdroid` flavors.
