package dev.shipyu.mindverse.support

/**
 * Support-screen data strings (ticket 09 / spec "Support screen"). Each rail ships as a
 * single constant so ticket 10's remote Config can mirror them as its built-in defaults
 * (ADR-0005) — swapping a URL is a config change, never a code change.
 *
 * Rail economics are ratified in the spec: UPI (0% fee, India) and GitHub Sponsors (0%
 * fee, global); Buy Me a Coffee is the recorded international fallback (5% fee) and is
 * deliberately deferred — the constant exists for the future config swap, no card for it.
 */
object SupportConfig {
    /** Owner UPI VPA (provided 2026-10-25). Payer enters the amount — no `am` field. */
    const val UPI_VPA = "piyu8h@slc"

    /** Payee display name in the UPI URI. */
    const val UPI_PAYEE_NAME = "warrior-pk"

    /** Transaction note shown in UPI apps. */
    const val UPI_NOTE = "Mindverse support"

    /** Global sponsor rail (one-time sponsorships supported). */
    const val SPONSORS_URL = "https://github.com/sponsors/warrior-pk"

    /** Recorded international fallback (5% fee); deferred by decision — not rendered in v1. */
    const val BMC_FALLBACK_URL = "https://www.buymeacoffee.com/warrior-pk"

    /**
     * Canonical UPI payment URI (spec: `upi://pay?pa=<VPA>&pn=warrior-pk&cu=INR&tn=…`,
     * deliberately without `am`). Built exactly as specified — `@` stays literal, the note
     * is `+`-encoded — and this exact string is what the QR encodes.
     */
    val upiPaymentUri: String
        get() = "upi://pay" +
            "?pa=$UPI_VPA" +
            "&pn=$UPI_PAYEE_NAME" +
            "&cu=INR" +
            "&tn=" + UPI_NOTE.replace(' ', '+')
}
