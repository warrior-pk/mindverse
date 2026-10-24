package dev.shipyu.mindverse.core.data.ingest

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.abs

/**
 * Perceptual fingerprint for image Captures (spec R5-3: near-duplicate detection uses a
 * perceptual hash for screenshots). dHash: 9x8 grayscale, each bit = "left pixel brighter
 * than right" → 64 bits. Equal hashes flag a near-duplicate at the ingestion boundary;
 * hamming-distance thresholds are deferred (documented in ticket 02).
 */
object ImageFingerprint {

    fun dhash(bitmap: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        try {
            var hash = 0L
            var bit = 0
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    val left = luminance(scaled.getPixel(x, y))
                    val right = luminance(scaled.getPixel(x + 1, y))
                    if (left > right) hash = hash or (1L shl bit)
                    bit++
                }
            }
            return hash
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    /** Best-effort decode; unreadable bytes yield null (source context degrades, never throws). */
    fun dhashBytes(bytes: ByteArray): Long? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bitmap ->
            try {
                dhash(bitmap)
            } finally {
                bitmap.recycle()
            }
        }

    private fun luminance(pixel: Int): Int {
        val red = (pixel shr 16) and 0xFF
        val green = (pixel shr 8) and 0xFF
        val blue = pixel and 0xFF
        // Integer approximation of Rec. 601 luma; abs keeps sign irrelevant for comparisons.
        return abs(299 * red + 587 * green + 114 * blue) / 1000
    }
}
