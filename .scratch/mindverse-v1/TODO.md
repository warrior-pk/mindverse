# TODO — Mindverse M1 spine

Progress list for the `@implement all tickets one by one` run. Ticket details live in
`.scratch/mindverse-v1/issues/`; this file is the rolling checklist.

- [x] 01 — Gradle scaffold (resolved)
- [x] 02 — Local storage core (resolved)
- [x] 03 — Egress chokepoint + provider adapters (resolved)
- [x] 04 — Processing pipeline: WorkManager extract/embed, pins, dead-letter (resolved · `217f578` · v0.4.0)
- [x] 05 — Capture receivers: Share sheet, QSTile, screenshot watcher (resolved · `190df38`)
- [x] 06 — Memory surfaces: Gallery, capture review sheet, Memory detail, nav (resolved)
- [ ] 07 — Ask mode: hybrid retrieval + streaming grounded chat + keyless fallback — **IN PROGRESS**
- [ ] 08 — M1 hardening: agent parts only (in-app update check, first-run onboarding, release recipe)
      HUMAN GATES: keystore generation + signing config, GitHub Releases publish
- [ ] 09 — Support screen: UPI + GitHub Sponsors
      BLOCKED: owner's UPI VPA (`needs-info`); placeholder `REPLACE-ME-VPA` path needs owner approval
- [ ] 10 — Config + update spine: signed config.json, kill-switches, updater (blocked by 08)

Versioning note: each `feat:` ticket commits as `0.x.0` bumps already approved
(0.4.0 for 04). Ask before the next minor bump.
