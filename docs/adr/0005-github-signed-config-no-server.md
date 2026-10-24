# GitHub-transported signed Config; no config server

Mindverse ships no config server and will not add one. Values that must change without an app release — Support-screen URLs (UPI VPA, GitHub Sponsors, Buy-Me-a-Coffee fallback) and feature kill-switches — live in a `config.json` committed to the app's own GitHub repo, fetched over the same HTTPS path as the update check, cached locally forever, with built-in hard defaults as the fallback (first run and fully offline work identically). This honors the local-first, zero-backend stance (ADR-0003) and the zero-egress Support screen, avoids permanently hosting and securing a backend for a handful of knobs, and keeps the system working even if the project goes dormant.

Consequences:

- The Ed25519-pubkey is baked into the APK; config is signed; unsigned or mismatched config is silently discarded and defaults are used. Reason: Support URLs are money-paying URLs — a repo compromise would otherwise redirect every user's donations (scam QR).
- Kill-switches are OFF-only: remote config may force a feature off (overriding a local opt-in), never on. A compromised repo can disable the app, never silently enable watchers or access.
- Fetch piggybacks the update check (manual, or a 24h-debounced cold start); no background poller. The fetch is an outbound call, so it is logged on the Privacy screen as a non-AI row with a per-feature toggle.
- `config.json` carries an integer `schemaVersion`; old APKs read only keys they know and ignore the rest. Schema renames ship in the same APK release that understands the new schema (a coordinated repo+APK push) — there is no generic migration layer.
- There is no code hot-fix path on Android sideloads. Code fixes ship as APK updates via the in-app updater (GitHub Releases API `versionCode` → cached APK → installer intent); "controlled on update" means APK releases can redefine config keys in the same push.

Rejected alternative: a hosted config endpoint (Firebase Remote Config or self-hosted) — rejected as contradicted by local-first/zero-backend, one more cost/key/attack surface, and a privacy-policy liability for trivial value.
