# 08 — Milestone-1 hardening: signing, updates, first-run, release APKs

Status: ready-for-human
Spec: .scratch/mindverse-v1/spec.md (Milestones, updates row)

## Body

Close out M1 into an install-on-own-phone build (spec R1-7, R2-2):

- Signing: keystore generated, signing config wired, Keystore-backed alias for the app keys (storage was done in issue 02; here we produce release signing) — agent cannot create this; human review + store of password/keystore is the human step.
- In-app update check: fetch GitHub Releases `latest` (owner: `warrior-pk`, repo: `mindverse`), compare versionCode, deep-link to APK asset (no store APIs).
- Publish under `github.com/warrior-pk/mindverse/releases` (manual step by human owner).
- First-run onboarding: grant-what's-needed flow (notification permission, no accidental over-permission), egress/privacy preface (points into Privacy screen from issue 03), BYOK key entry (Gemini or OpenAI-compat URL + key) with skip path (app remains usable keyless per R1-2).
- Build both flavors' release APKs via CI-less local recipe.
- Crash reporting: **none** (privacy posture); rely on user-provided logcat until a local crash-log screen lands in M3.

## Accepted when
Two flavors' release APKs zip-installable from Releases (draft), first-run completes without mandatory key, no permission requested that spec's M1 doesn't need.

## Comments

- 2026-10-25 (agent parts done): status returns to `ready-for-human` — the human gates
  below are all that remain.
  - **Signing wiring**: `app/build.gradle.kts` reads a gitignored `keystore.properties`
    (storeFile / storePassword / keyAlias / keyPassword); absent → unsigned release build
    (verified: both flavors' release APKs build).
  - **Update check** (`core/updates`): GitHub `releases/latest` → versionCode compare →
    "Download update" deep-links to the APK asset (no store APIs). versionCode comes from
    the `versionCode: N` body marker, else `mindverse-<N>-…apk` asset names, else a tag
    `v<versionName>` comparison. Cold-start check debounced 24h (`AppSettings` stamp,
    spec R6-3); manual check on the About card (Support tab). Ticket 10 rides this path.
  - **First-run onboarding** (`firstrun/`): Welcome → the only two runtime permissions M1
    needs (notifications + media read, both skippable, features degrade) → privacy preface
    with a jump to the Privacy screen → BYOK entry (Gemini key or OpenAI-compatible URL +
    model + key) with an explicit "Skip — stay keyless" path (R1-2).
  - **Release recipe**: `scripts/build-release.sh` (lint + both release APKs →
    `dist/mindverse-<code>-<flavor>.apk`). Crash reporting: none, by design.
  - Tests: `ReleaseUpdateCheckerTest` (MockWebServer fixtures incl. malformed/404/
    prerelease/tag-fallback), `UpdateControllerTest` (24h debounce + manual),
    `FirstRunControllerTest` (keyless skip + BYOK persistence). Lint + tests green.
  - **HUMAN STEPS remaining**: (1) generate the keystore + write `keystore.properties`
    (keytool line is in `scripts/build-release.sh`), (2) run the script and publish the
    two APKs as a GitHub Release (tag `v0.5.0`, body with `versionCode: 8`), (3) confirm
    first-run + update check on the device before publishing.
