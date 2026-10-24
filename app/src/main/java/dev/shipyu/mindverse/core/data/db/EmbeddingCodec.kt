package dev.shipyu.mindverse.core.data.db

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FloatArray <-> sqlite-vec float32 BLOB codec. sqlite-vec serializes float vectors as
 * little-endian float32 blobs (native serialized format); every embedding written or
 * queried through `memory_vec` goes through this codec.
 */
object EmbeddingCodec {
    fun dimensions(blob: ByteArray): Int = blob.size / Float.SIZE_BYTES

    fun encode(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        for (value in vector) buffer.putFloat(value)
        return buffer.array()
    }

    fun decode(blob: ByteArray): FloatArray {
        require(blob.size % Float.SIZE_BYTES == 0) { "vector blob size must divide float size" }
        val buffer = ByteBuffer.wrap(blob)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(blob.size / Float.SIZE_BYTES)
        for (index in out.indices) out[index] = buffer.float
        return out
    }
}
