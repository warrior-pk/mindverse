package dev.shipyu.mindverse.core.ocr

interface OcrEngine {
    /** Runs optical text recognition on arbitrary image bytes. */
    fun recognize(bytes: ByteArray): String
}
