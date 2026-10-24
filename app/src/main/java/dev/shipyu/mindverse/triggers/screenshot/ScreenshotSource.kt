package dev.shipyu.mindverse.triggers.screenshot

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One detected screenshot, identified by its MediaStore row. */
data class Screenshot(
    val uri: Uri,
    val mimeType: String?,
)

/**
 * Finds the screenshot behind a `ScreenCaptureCallback` event (the callback carries no
 * bitmap, so the MediaStore row is looked up). Reads require READ_MEDIA_IMAGES; without the
 * grant the watcher stays silent (first-run onboarding, ticket 08, asks for it).
 */
interface ScreenshotSource {
    suspend fun latest(): Screenshot?
}

class MediaStoreScreenshotSource(
    private val context: Context,
    private val clock: Clock,
) : ScreenshotSource {

    override suspend fun latest(): Screenshot? = withContext(Dispatchers.IO) {
        val since = clock.millis() / 1000 - LOOKBACK_SECONDS
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.MIME_TYPE,
        )
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND " +
            "${MediaStore.Images.Media.DATE_ADDED} >= ?"
        val args = arrayOf("%Screenshots%", since.toString())
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val mime = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE))
                Screenshot(
                    uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                    mimeType = mime,
                )
            }
        }.getOrNull()
    }

    private companion object {
        /** ScreenCaptureCallback fires immediately; allow for MediaStore write lag. */
        const val LOOKBACK_SECONDS = 30L
    }
}

/** Reads the bytes behind a screenshot URI; abstraction keeps the handler testable. */
interface ScreenshotReader {
    fun read(uri: Uri): ByteArray?
    fun mimeType(uri: Uri): String?
}

class ContentResolverScreenshotReader(
    private val context: Context,
) : ScreenshotReader {
    override fun read(uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull()

    override fun mimeType(uri: Uri): String? =
        runCatching { context.contentResolver.getType(uri) }.getOrNull()
}
