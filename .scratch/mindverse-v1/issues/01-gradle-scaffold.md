# 01 — Gradle project scaffold

Status: ready-for-agent
Spec: .scratch/mindverse-v1/spec.md (M1 "Spine")

## Body

Create the Android project skeleton for Mindverse per spec:

- Single `:app` Gradle module, Kotlin + Jetpack Compose, Hilt DI, KSP.
- `minSdk 34`, `targetSdk 36`, compile SDK 36.
- Product flavors: `play` (ML Kit deps) and `fdroid` (no closed blobs) — both buildable and **empty of feature code** for now; one `OcrEngine` interface with a no-op stub each.
- ApplicationId `dev.shipyu.mindverse` (per spec R4-7).
- Apache-2.0 `LICENSE` (copyright line: `Copyright 2026 warrior-pk (https://github.com/warrior-pk)`), `README.md` stub with author + links block (repo `github.com/warrior-pk/mindverse`, showcase `https://mindverse.shipyu.dev`) pointing at spec/ADRs/glossary.
- Strict lint config + basic unit-test wiring; CI-less for a sideload-first repo (no store constraints).
- Verify both flavors assemble (`assemblePlayDebug`, `assembleFdroidDebug`).

## Accepted when
Running both flavor debug builds from a clean checkout succeeds, and the future seams listed in the spec (core/ingest, core/egress, core/ai, core/data packages) exist as empty packages.
