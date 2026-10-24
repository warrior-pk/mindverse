package dev.shipyu.mindverse.core.egress

/**
 * Privacy-relevant content classes carried on the wire, detected by [PiiMasker] and
 * logged as `payload classes` in the egress log (ticket 03; spec R5-1).
 */
enum class PayloadClass {
    PHONE,
    EMAIL,
    ID,
    CARD,
    OTP,
}

/** Result of one masking pass: sanitized text + the union of classes detected. */
data class MaskedText(
    val text: String,
    val classes: Set<PayloadClass>,
)

/**
 * PII masking at the egress chokepoint (ADR-0002, ticket 03): regex families for
 * phone/email/ID/generic card/OTP-like numbers replace matches with typed placeholders
 * (`<phone>`, `<email>`, `<id>`, `<card>`, `<otp>`) before any payload leaves the app.
 *
 * Detection model (documented tradeoff, ADR-0002: "model accuracy on masked content is
 * accepted as a trade for the invariant"):
 * - Email first, then PAN-style IDs, then maximal digit chains (`+`/parens as optional
 *   boundaries, single spaces/dashes inside) classified as a whole:
 *   CARD (13–19 digits, Luhn-valid, but ALSO 16+ digit runs remain unmasked when Luhn
 *   fails), ID (exactly 12 digits as three 4-digit groups — the Aadhaar shape), then
 *   PHONE (leading `+` with 7–15 digits, or 2+ sortable groups totalling 8–14 digits).
 * - OTP-like numbers run LAST on what remains: bare 4–8-digit groups only, so they must
 *   be context-anchored to an OTP/code/pin keyword (anything phone-shaped was already
 *   masked by the chain pass).
 * - Consequences: decimal amounts with `.` separators survive (dot is not a digit
 *   separator); dash-forms of dates and multi-number structures can be over-masked as
 *   phone; non-Luhn 13+ digit runs survive. Both directions are pinned by the snapshot
 *   corpus (egress/masking_corpus.txt) so behavior changes are review-visible.
 */
class PiiMasker {
    fun mask(text: String): MaskedText {
        if (text.isEmpty()) return MaskedText("", emptySet())
        val classes = LinkedHashSet<PayloadClass>()
        var current = text

        current = EMAIL.replace(current) {
            classes.add(PayloadClass.EMAIL)
            "<email>"
        }

        current = DIGIT_CHAIN.replace(current) { match ->
            classifyDigitChain(match.value)?.let { (clazz, placeholder) ->
                classes.add(clazz)
                placeholder
            } ?: match.value
        }

        current = PAN_ID.replace(current) {
            classes.add(PayloadClass.ID)
            "<id>"
        }

        // OTP-like numbers last: keyword-anchored bare digit groups only.
        current = OTP_AHEAD.replace(current) { match ->
            val digits = match.groupValues[2]
            if (digits.length in 4..8) {
                classes.add(PayloadClass.OTP)
                match.value.replace(digits, "<otp>")
            } else {
                match.value
            }
        }
        current = OTP_BEHIND.replace(current) { match ->
            val digits = match.groupValues[1]
            classes.add(PayloadClass.OTP)
            match.value.replace(digits, "<otp>")
        }

        return MaskedText(current, classes)
    }

    /**
     * Classify one maximal digit chain. Returns (class, placeholder) or null to keep the
     * text unchanged. Classification precedence: CARD → ID → PHONE (first hit wins; a
     * chain is masked by exactly one rule).
     */
    internal fun classifyDigitChain(raw: String): Pair<PayloadClass, String>? {
        val groups = raw.split(NON_DIGITS).filter { it.isNotEmpty() }
        val digits = groups.joinToString("")

        // Generic card: 13–19 digits, Luhn-valid (all real cards pass; random long
        // digit runs usually don't — they stay unmasked, see class KDoc).
        if (digits.length in 13..19 && luhnValid(digits)) {
            return PayloadClass.CARD to "<card>"
        }

        // Aadhaar shape: exactly 12 digits as three 4-digit groups.
        if (digits.length == 12 && groups.size == 3 && groups.all { it.length == 4 }) {
            return PayloadClass.ID to "<id>"
        }

        // Phone: international form ("+…" forgiving of separators) or grouped landline/
        // mobile digits (2+ groups, 8–14 digits total; >14-digit chains are IDs/tracking).
        val international = raw.startsWith("+")
        if ((international && digits.length in 7..15) ||
            (groups.size >= 2 && digits.length in 8..14)
        ) {
            return PayloadClass.PHONE to "<phone>"
        }
        return null
    }

    private fun luhnValid(digits: String): Boolean {
        var sum = 0
        var double = false
        for (i in digits.length - 1 downTo 0) {
            val value = digits[i] - '0'
            if (double) {
                sum += if (value * 2 > 9) value * 2 - 9 else value * 2
            } else {
                sum += value
            }
            double = !double
        }
        return sum % 10 == 0
    }

    private companion object {
        val EMAIL = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")

        /**
         * Maximal digit chain: optional leading `+` or `(`, at least 2 digits, with
         * digits/parens/spaces/dashes inside, ending on a digit. Masquerading spans that
         * contain letters break here, so prose stays intact.
         */
        val DIGIT_CHAIN = Regex("(?:\\+|\\()?\\d[\\d()\\- ]*\\d")
        val PAN_ID = Regex("\\b[A-Z]{5}\\d{4}[A-Z]\\b")
        val OTP_AHEAD = Regex("(?i)\\b(otp|passcode|verification code|code|pin)\\b[^\\d]{0,12}\\b(\\d{4,8})\\b")
        val OTP_BEHIND = Regex("(?i)(?<!\\d)(\\d{4,8})\\b[^\\d]{0,12}\\b(?:verification code|passcode|otp|code|pin)\\b")
        private val NON_DIGITS = Regex("[^0-9]+")
    }
}
