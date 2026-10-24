package dev.shipyu.mindverse.core.data.ingest

import android.content.Context
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Object storage for Capture blobs (ticket 05; ADR-0003: blobs live outside the schema,
 * referenced by `captures.blob_path`). Files land in app-private storage so no media
 * permission is needed to read them back; the pipeline owns their lifecycle (dead-lettered
 * blobs are kept, never silently deleted).
 *
 * Content hash = lowercase hex SHA-256 of the stored bytes — the dedupe key (spec R5-3).
 */
class CaptureBlobStore(
    private val rootDir: File,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    data class StoredBlob(
        val path: String,
        val sha256: String,
        val size: Long,
    )

    fun store(bytes: ByteArray, extension: String): StoredBlob {
        val hash = sha256Hex(bytes)
        rootDir.mkdirs()
        val file = File(rootDir, "${idFactory()}.${extension.trimStart('.')}")
        file.writeBytes(bytes)
        return StoredBlob(file.absolutePath, hash, bytes.size.toLong())
    }

    fun storeStream(stream: InputStream, extension: String): StoredBlob =
        stream.use { store(it.readBytes(), extension) }

    /** Text shares/URLs are stored as UTF-8 text blobs (extraction reads them back). */
    fun storeText(text: String): StoredBlob = store(text.toByteArray(Charsets.UTF_8), "txt")

    fun storeBitmap(bitmap: Bitmap): StoredBlob {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return store(out.toByteArray(), "png")
    }

    companion object {
        fun forApp(context: Context): CaptureBlobStore =
            CaptureBlobStore(File(context.filesDir, "captures"))

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { byte -> "%02x".format(byte) }
    }
}

/** Extension used for stored blobs; defaults to `bin` when the MIME type is unknown. */
fun extensionForMime(mimeType: String?): String = when {
    mimeType == null -> "bin"
    mimeType.startsWith("image/png") -> "png"
    mimeType.startsWith("image/jpeg") -> "jpg"
    mimeType.startsWith("image/webp") -> "webp"
    mimeType.startsWith("image/gif") -> "gif"
    mimeType == "application/pdf" -> "pdf"
    mimeType.startsWith("text/") -> "txt"
    else -> "bin"
}
