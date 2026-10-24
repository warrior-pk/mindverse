package dev.shipyu.mindverse.core.pipeline

import dev.shipyu.mindverse.core.data.MemoryDraft
import dev.shipyu.mindverse.core.data.db.CaptureEntity
import dev.shipyu.mindverse.core.data.db.CaptureKind
import dev.shipyu.mindverse.core.ocr.OcrEngine

/**
 * Keyless local fallback (spec R1-2: app usable keyless). When egress cannot run
 * (no key, provider unconfigured, feature toggled off), extraction still produces a
 * searchable local-only Memory from on-device text/OCR so Share → Gallery works.
 * AI enrichment can re-run later via Capture review confirm.
 */
internal object LocalFallback {
    fun draftFor(
        capture: CaptureEntity,
        readBlob: (CaptureEntity) -> ByteArray?,
        readText: (CaptureEntity) -> String?,
        ocr: OcrEngine?,
    ): MemoryDraft? {
        val isVisual = capture.mimeType?.startsWith("image/") == true ||
            capture.mimeType == "application/pdf" ||
            (capture.mimeType == null && (capture.kind == CaptureKind.IMAGE || capture.kind == CaptureKind.PDF))
        return if (isVisual) visualDraft(capture, readBlob, ocr) else textDraft(capture, readText)
    }

    private fun textDraft(
        capture: CaptureEntity,
        readText: (CaptureEntity) -> String?,
    ): MemoryDraft? {
        val body = readText(capture)?.trim().orEmpty()
        val link = capture.deepLink?.trim().orEmpty()
        val content = body.ifEmpty { link }
        if (content.isEmpty()) {
            // Nothing readable locally: keep the dead-letter path (no data loss).
            return null
        }
        val title = firstLine(content, 80)
        return MemoryDraft(
            sourceCaptureIds = listOf(capture.id),
            title = title,
            summary = content.take(280),
            category = null,
            tags = listOf("local-only"),
            extractedText = content,
        )
    }

    private fun visualDraft(
        capture: CaptureEntity,
        readBlob: (CaptureEntity) -> ByteArray?,
        ocr: OcrEngine?,
    ): MemoryDraft {
        val bytes = readBlob(capture)
        val ocrText = if (bytes != null && ocr != null) {
            runCatching { ocr.recognize(bytes) }.getOrNull()?.trim().orEmpty()
        } else {
            ""
        }
        if (ocrText.isNotEmpty()) {
            return MemoryDraft(
                sourceCaptureIds = listOf(capture.id),
                title = firstLine(ocrText, 80),
                summary = ocrText.take(280),
                category = null,
                tags = listOf("local-only"),
                extractedText = ocrText,
            )
        }
        val isPdf = capture.mimeType == "application/pdf" || capture.kind == CaptureKind.PDF
        val label = if (isPdf) "PDF capture" else "Image capture"
        val link = capture.deepLink?.trim().orEmpty()
        return MemoryDraft(
            sourceCaptureIds = listOf(capture.id),
            title = link.ifEmpty { label },
            summary = if (link.isNotEmpty()) link else "$label — add an API key for AI titles and tags.",
            category = null,
            tags = listOf("local-only"),
            extractedText = null,
        )
    }

    private fun firstLine(text: String, max: Int): String {
        val line = text.lineSequence().firstOrNull()?.trim().orEmpty().ifEmpty { text.trim() }
        return if (line.length <= max) line else line.take(max).trimEnd() + "…"
    }
}
