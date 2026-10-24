package dev.shipyu.mindverse.core.egress

/**
 * Reason code AND per-feature egress-toggle axis (one entry per outbound-AI feature;
 * GLOSSARY vocabulary). The Privacy screen renders one toggle per entry (spec R5-1).
 */
enum class EgressFeature(
    val displayLabel: String,
    val reasonLabel: String,
) {
    /** VLM extraction of Captures into structured Memories (ticket 04 pipeline). */
    MEMORY_EXTRACTION("Memory extraction", "Extract structured Memories from Captures"),

    /** Ask mode grounded answers over retrieved Memories (ticket 07). */
    ASK("Ask mode", "Answer questions over your Memories"),

    /** Voice memo audio one-shot STT (M2); the canonical "disable voice audio egress". */
    VOICE_TRANSCRIPTION("Voice memo processing", "Transcribe voice-memo audio"),
}
