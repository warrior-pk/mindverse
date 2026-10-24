# 01 — Gradle project scaffold

Status: resolved
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

## Comments

- 2026-10-24: Implemented. Verified `clean` + `assemblePlayDebug` + `assembleFdroidDebug` + unit tests (both flavors) + `lintPlayDebug`/`lintFdroidDebug` all green. Version `local.properties` is gitignored; Android SDK installed at `~/.local/share/android-sdk` (API 36 + build-tools 36.0.0), Gradle 9.5.1 via wrapper. Pin notes: AGP 8.13.2 is the last AGP line released with features `minSdk 34`-era compat; Gradle must be <9.6 (AGP 8.x incompat) and Hilt 2.58 is the newest requiring AGP ≤8.x (2.59+ demands AGP 9.0); compose BOM 2025.09.00 (compose 1.9.1) is the newest BOM whose artifacts still accept `compileSdk 36` (1.10.x-…1.12.x target SDK 37/AGP 9.1). Build config: `buildFeatures.buildConfig = true` (needed for the smoke test), strict lint with `abortOnError`, JDK pinned to 21 via `org.gradle.java.home`. `OcrEngine` interface in `core/ocr` with per-flavor `@Binds` Hilt module and no-op stubs (`PlayOcrEngine`/`FdroidOcrEngine`). Seam packages `core/{ingest,egress,ai,data}` exist via `package-info.java`.
