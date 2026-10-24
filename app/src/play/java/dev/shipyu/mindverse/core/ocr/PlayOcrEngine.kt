package dev.shipyu.mindverse.core.ocr

/**
 * Flavor stub for the `play` distribution: real engine is ML Kit text+barcode
 * (spec R5-5); wired in during M2 OCR work.
 */
class PlayOcrEngine : OcrEngine {
    override fun recognize(bytes: ByteArray): String = ""
}
