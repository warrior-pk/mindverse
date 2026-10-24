# 09 — Support screen: UPI (India) + GitHub Sponsors (global)

Status: needs-info
Blocked by: 06
Spec: .scratch/mindverse-v1/spec.md (Support screen)
Missing info: the owner's real UPI VPA — build ahead using the `REPLACE-ME-VPA` constant (single `BuildConfig`/const), then fill when the owner provides it. Status flips to `ready-for-agent` once the VPA lands or the owner approves the placeholder path.

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
