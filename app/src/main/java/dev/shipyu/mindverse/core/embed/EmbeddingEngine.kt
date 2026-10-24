package dev.shipyu.mindverse.core.embed

/**
 * On-device text embedding (spec R4-2): local-only, never egresses, never logs. Every
 * embedding path in the app (pipeline ticket 04, Ask ticket 07) resolves through this
 * interface so the query and document spaces stay consistent.
 */
interface EmbeddingEngine {
    /** Stable identity of the model that produced vectors (for registry/debug surfaces). */
    val modelId: String

    /** Vector width; must equal the sqlite-vec column width (384 in v1). */
    val dimension: Int

    suspend fun embed(text: String): FloatArray
}
