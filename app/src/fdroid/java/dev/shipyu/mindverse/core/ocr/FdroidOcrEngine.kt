package dev.shipyu.mindverse.core.ocr

/**
 * Flavor stub for the `fdroid` distribution: real engine is FOSS OCR
 * (PaddleOCR-ONNX or Tesseract, spec R5-5); wired in during M2 OCR work.
 */
class FdroidOcrEngine : OcrEngine {
    override fun recognize(bytes: ByteArray): String = ""
}
