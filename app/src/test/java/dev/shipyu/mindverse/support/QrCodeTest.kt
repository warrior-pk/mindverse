package dev.shipyu.mindverse.support

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 09 acceptance seam: the QR renders and matches the URI string exactly, and the
 * URI carries the spec's shape (no `am` field — the payer chooses the amount).
 */
class QrCodeTest {

    @Test
    fun `upi uri matches the spec shape`() {
        val uri = SupportConfig.upiPaymentUri
        assertEquals(
            "upi://pay?pa=piyu8h@slc&pn=warrior-pk&cu=INR&tn=Mindverse+support",
            uri,
        )
        assertFalse("payer chooses the amount — no am field", uri.contains("am="))
    }

    @Test
    fun `qr decodes back to the exact upi uri`() {
        val payload = SupportConfig.upiPaymentUri
        val matrix = QrCode.encodeMatrix(payload)

        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height) { index ->
            if (matrix.get(index % width, index / width)) 0x000000 else 0xFFFFFF
        }
        val decoded = QRCodeReader().decode(
            BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels))),
        )
        assertEquals(payload, decoded.text)
    }

    @Test
    fun `qr matrix is a square with quiet zone room`() {
        val matrix = QrCode.encodeMatrix(SupportConfig.upiPaymentUri, requestedSizePx = 256)
        assertTrue(matrix.width == matrix.height)
        assertTrue(matrix.width >= 256)
        // Some module is actually dark — an all-white matrix is not a code.
        assertTrue((0 until matrix.height).any { y -> (0 until matrix.width).any { x -> matrix.get(x, y) } })
    }
}
