# TODO — Mindverse M1 spine

Progress list for the `@implement all tickets one by one` run. Ticket details live in
`.scratch/mindverse-v1/issues/`; this file is the rolling checklist.

- [x] 01 — Gradle scaffold (resolved)
- [x] 02 — Local storage core (resolved)
- [x] 03 — Egress chokepoint + provider adapters (resolved)
- [x] 04 — Processing pipeline: WorkManager extract/embed, pins, dead-letter (resolved · `217f578` · v0.4.0)
- [x] 05 — Capture receivers: Share sheet, QSTile, screenshot watcher (resolved · `190df38`)
- [x] 06 — Memory surfaces: Gallery, capture review sheet, Memory detail, nav (resolved)
- [x] 07 — Ask mode: hybrid retrieval + streaming grounded chat + keyless fallback (resolved)
- [x] 08 — M1 hardening: agent parts done (update check, first-run onboarding, release recipe) · `828fa9f` · v0.5.0
      HUMAN GATES: keystore generation + `keystore.properties`, GitHub Releases publish (tag v0.5.0, body `versionCode: 8`)
- [x] 09 — Support screen: UPI (`piyu8h@slc`) + GitHub Sponsors (resolved) · v0.6.0
- [ ] 10 — Config + update spine: signed config.json, kill-switches, updater — DEFERRED (do later)

Versioning note: each `feat:` ticket commits as `0.x.0` bumps already approved
(0.4.0 for 04). Ask before the next minor bump.
