package dev.shipyu.mindverse.core.embed

/**
 * Embedding-model registry (ticket 04: "bundles with a `ModelRegistry` so a better model
 * can swap later"). One place names every model, its vector width, and how to build it.
 *
 * Current state: the MiniLM-class ONNX entry is declared with its asset slot but has no
 * factory yet (onnxruntime + weights are not bundled in this build), so [select] falls
 * through to the deterministic feature-hashing engine. Shipping the real model is:
 *   1. drop `models/minilm-l6-v2.onnx` (+ tokenizer assets) into `app/src/main/assets/`,
 *   2. add `com.microsoft.onnxruntime:onnxruntime-android`,
 *   3. set [minilm.engineFactory] to the ONNX runner and declare its tokenizer files.
 * The pipeline and Ask code resolve engines only through this registry, so no call site
 * changes. Dimensions MUST stay 384 to match the `memory_vec` column
 * (MindverseDatabaseSchema.EMBEDDING_DIMENSION); changing width is a schema+reindex event.
 */
object ModelRegistry {
    /** sqlite-vec column width (single source: MindverseDatabaseSchema). */
    const val DIMENSION = 384

    /** A selectable embedding model. [engineFactory] null = declared but not bundled yet. */
    data class ModelSpec(
        val id: String,
        val dimension: Int,
        /** Asset path that must exist for the model to be usable; null for built-ins. */
        val assetPath: String?,
        val engineFactory: (() -> EmbeddingEngine)?,
    )

    /** MiniLM-class ONNX target (spec R4-2); asset slot reserved, factory pending. */
    val minilm: ModelSpec = ModelSpec(
        id = "minilm-l6-v2-onnx",
        dimension = DIMENSION,
        assetPath = "models/minilm-l6-v2.onnx",
        engineFactory = null,
    )

    /** Always-available built-in fallback (see [FeatureHashingEmbeddingEngine]). */
    val featureHashing: ModelSpec = ModelSpec(
        id = "feature-hashing-v1",
        dimension = DIMENSION,
        assetPath = null,
        engineFactory = { FeatureHashingEmbeddingEngine(DIMENSION) },
    )

    /** Preference order: first usable entry wins. */
    val entries: List<ModelSpec> = listOf(minilm, featureHashing)

    /**
     * Selects the best usable engine. [assetAvailable] reports whether a bundled asset is
     * present (device AssetManager in DI; tests pass `{ false }`).
     */
    fun select(assetAvailable: (String) -> Boolean = { false }): EmbeddingEngine {
        for (entry in entries) {
            val factory = entry.engineFactory ?: continue
            val path = entry.assetPath
            if (path == null || assetAvailable(path)) return factory()
        }
        error("no embedding engine available — registry misconfigured")
    }
}
