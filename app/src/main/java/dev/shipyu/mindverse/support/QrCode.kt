package dev.shipyu.mindverse.support

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Local QR rendering (ticket 09): encoded on-device with ZXing (Apache-2.0, FOSS-friendly
 * for both flavors), no network. [encodeMatrix] is the pure seam (round-trip tested);
 * [toBitmap] converts for display.
 */
object QrCode {

    fun encodeMatrix(payload: String, requestedSizePx: Int = 512): BitMatrix {
        require(payload.isNotBlank()) { "QR payload must not be blank" }
        return QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, requestedSizePx, requestedSizePx)
    }

    fun toBitmap(matrix: BitMatrix, quietZonePx: Int = 8): Bitmap {
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val inQuietZone = x < quietZonePx || x >= width - quietZonePx ||
                y < quietZonePx || y >= height - quietZonePx
            if (!inQuietZone && matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        return createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }
}
