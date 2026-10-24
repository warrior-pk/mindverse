package dev.shipyu.mindverse.core.ocr

import javax.inject.Inject
/**
 * Flavor stub for the `play` distribution: real engine is ML Kit text+barcode
 * (spec R5-5); wired in during M2 OCR work.
 */
class PlayOcrEngine @Inject constructor() : OcrEngine {
    override fun recognize(bytes: ByteArray): String = ""
}
