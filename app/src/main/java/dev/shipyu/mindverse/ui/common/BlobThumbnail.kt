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
 * Thumbnail for a capture blob (ticket 06). Decoded off the main thread and
 * downscaled aggressively (512px max) so masonry scrolling stays smooth;
 * unreadable/missing blobs degrade to the placeholder rather than crashing.
 */
@Composable
fun BlobThumbnail(path: String?, modifier: Modifier = Modifier, placeholder: String = "No image") {
    val bitmap = androidx.compose.runtime.produceState<android.graphics.Bitmap?>(
        initialValue = null,
        key1 = path,
    ) {
        value = path?.let {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(it, bounds)
                    val maxDim = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
                    var sample = 1
                    while (maxDim / sample > 512) sample *= 2
                    BitmapFactory.decodeFile(
                        it,
                        BitmapFactory.Options().apply { inSampleSize = sample },
                    )
                }.getOrNull()
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
