package dev.shipyu.mindverse.core.data.ingest

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Perceptual-hash seam (spec R5-3): stable for identical pixels, distinct for different. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageFingerprintTest {

    @Test
    fun `identical images hash equally and different images differ`() {
        val gradient = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val inverted = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                val shade = (x * 4 + y) % 256
                gradient.setPixel(x, y, 0xFF000000.toInt() or (shade shl 16) or (shade shl 8) or shade)
                inverted.setPixel(x, y, 0xFF000000.toInt() or ((255 - shade) shl 16) or ((255 - shade) shl 8) or (255 - shade))
            }
        }

        val first = ImageFingerprint.dhash(gradient)
        val second = ImageFingerprint.dhash(gradient)
        val other = ImageFingerprint.dhash(inverted)
        assertEquals(first, second)
        assertNotEquals(first, other)

        gradient.recycle()
        inverted.recycle()
    }

    @Test
    fun `unreadable bytes degrade to null`() {
        assertNull(ImageFingerprint.dhashBytes(byteArrayOf(1, 2, 3)))
    }
}
