package dev.shipyu.mindverse.core.ocr

import javax.inject.Inject
/**
 * Flavor stub for the `fdroid` distribution: real engine is FOSS OCR
 * (PaddleOCR-ONNX or Tesseract, spec R5-5); wired in during M2 OCR work.
 */
class FdroidOcrEngine @Inject constructor() : OcrEngine {
    override fun recognize(bytes: ByteArray): String = ""
}
