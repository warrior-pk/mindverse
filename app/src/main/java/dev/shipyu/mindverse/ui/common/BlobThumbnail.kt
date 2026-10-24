package dev.shipyu.mindverse.ui.common

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale

/**
 * Thumbnail for a capture blob (ticket 06). Decoded off the main thread,
 * cached in memory, downscaled to ~384px max in RGB_565 so masonry scrolling
 * stays smooth; unreadable/missing blobs show the placeholder.
 */
private object ThumbnailCache : android.util.LruCache<String, android.graphics.Bitmap>(48) {
    override fun sizeOf(key: String, value: android.graphics.Bitmap): Int =
        value.byteCount / 1024
}

@Composable
fun BlobThumbnail(path: String?, modifier: Modifier = Modifier, placeholder: String = "No image") {
    val bitmap = androidx.compose.runtime.produceState<android.graphics.Bitmap?>(
        initialValue = ThumbnailCache.get(path ?: ""),
        key1 = path,
    ) {
        if (path == null) {
            value = null
        } else {
            ThumbnailCache.get(path)?.let {
                value = it
            } ?: run {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(path, bounds)
                        val maxDim = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
                        var sample = 1
                        while (maxDim / sample > 384) sample *= 2
                        BitmapFactory.decodeFile(
                            path,
                            BitmapFactory.Options().apply {
                                inSampleSize = sample
                                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                            },
                        )?.also { ThumbnailCache.put(path, it) }
                    }.getOrNull()
                }
            }
        }
    }.value
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
