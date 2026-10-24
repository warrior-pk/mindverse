# 09 — Support screen: UPI (India) + GitHub Sponsors (global)

Status: resolved
Blocked by: 06
Spec: .scratch/mindverse-v1/spec.md (Support screen)
Missing info: none — owner provided the real UPI VPA (`piyu8h@slc`) on 2026-10-25; it replaces `REPLACE-ME-VPA` in the single constant.

## Body

Implement the static Support screen (decided post-grill; spec "Support screen") on the same bottom nav as Gallery / Ask / Privacy. Zero backend, zero network, zero egress interaction.

### India card — UPI (0%)
- Local QR render (ZXing or similar tiny lib) from the UPI payment URI: `upi://pay?pa=<VPA>&pn=warrior-pk&cu=INR&tn=Mindverse+support`. No `am` field — payer enters the amount.
- "Pay via UPI app" button: `Intent(ACTION_VIEW, uri)` wrapped in `Intent.createChooser` so the system chooser lists installed UPI apps (GPay/PhonePe/Paytm/BHIM).
- "Copy VPA" fallback button (clipboard) — covers UPI apps with flaky `upi://` scheme handling.
- VPA lives in one constant (`REPLACE-ME-VPA` until provided).

### Global card — GitHub Sponsors (0%)
- "♥ Sponsor on GitHub" button → Custom Tab/browser to `https://github.com/sponsors/warrior-pk` (one-time sponsorships supported).
- "Copy link" fallback button for webviews that block external app opening.
- URL ships as a single data string (remote-config-able); documented fallback swap: Buy Me a Coffee URL in a later update (deferred by decision — see spec).

### Non-goals
- No payment SDK, no network calls, no analytics on taps beyond optional local egress-free counters.
- No BMC card in v1 (deferred by design; do not add it).
- No fee/checkout code — all rails are external.

## Accepted when
- QR renders and matches the URI string.
- Chooser intent opens GPay/PhonePe/Paytm on emulator/device; Copy VPA and Copy link work.
- Screen is reachable from first-run onboarding AND the nav rail.
- No permission requests added to the manifest.
- Builds in both `play` and `fdroid` flavors (ZXing choice must be FOSS-friendly for both).

## Comments

- Oct 24 2026 (grilling R6): the "remote-config-able URL string" promise is now ratified — these constants become the built-in defaults beneath remote Config (ADR-0005, ticket 10). Keep the VPA/URLs as single constants so ticket 10 can mirror them into the default config; the `Status:` / missinginfo story is unchanged.

## Comments

- 2026-10-25 (implemented): Support screen ships with the owner's real VPA
  (`piyu8h@slc`), replacing the placeholder constant.
  - **Constants as data** (`support/SupportConfig.kt`): `UPI_VPA`, payee name, note,
    `SPONSORS_URL`, and the recorded-but-deferred `BMC_FALLBACK_URL` — single constants so
    ticket 10's remote Config mirrors them as built-in defaults. `upiPaymentUri` is built
    exactly per spec (`upi://pay?pa=<VPA>&pn=warrior-pk&cu=INR&tn=Mindverse+support`, no
    `am` field) and is the exact string the QR encodes.
  - **QR**: local ZXing encoding (`com.google.zxing:core` 3.5.3, Apache-2.0 —
    FOSS-friendly for both flavors), rendered on-device, zero network.
  - **India card**: QR + "Pay via UPI app" (`Intent.createChooser` ACTION_VIEW so the
    system chooser lists installed UPI apps) + "Copy VPA" clipboard fallback.
  - **Global card**: "♥ Sponsor on GitHub" (ACTION_VIEW browser to the Sponsors URL, one-
    time sponsorships supported) + "Copy link" fallback.
  - **Reachable from first-run onboarding** (welcome-page link completes onboarding and
    opens the Support tab) **and the nav rail** (ticket 06 nav).
  - No manifest permissions added; no payment SDK; no analytics; BMC card deliberately
    absent (deferred by decision).
  - Tests: `QrCodeTest` — URI matches the spec string byte-for-byte, QR decodes back to
    that exact URI via a ZXing round-trip, matrix shape sanity. Lint + tests green on both
    flavors; version 0.6.0.
