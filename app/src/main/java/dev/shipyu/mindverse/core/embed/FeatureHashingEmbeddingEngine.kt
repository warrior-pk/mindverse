package dev.shipyu.mindverse.core.embed

/**
 * Deterministic, dependency-free on-device embedder: signed feature hashing of word
 * unigrams + bigrams into [dimension] buckets, L2-normalized. Serves as the built-in
 * engine until the MiniLM-class ONNX weights ship (see [ModelRegistry]).
 *
 * It is a lexical embedder, not a semantic one: paraphrase retrieval leans on the FTS/BM25
 * half of hybrid search (ticket 07), while exact/near-exact wording still lands close in
 * vector space. That is the documented, honest degradation path — the alternative was
 * shipping the pipeline unindexed.
 */
class FeatureHashingEmbeddingEngine(
    override val dimension: Int = 384,
) : EmbeddingEngine {
    override val modelId: String = "feature-hashing-v1"

    init {
        require(dimension > 0) { "dimension must be positive" }
    }

    override suspend fun embed(text: String): FloatArray {
        val vector = FloatArray(dimension)
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return vector

        for (index in tokens.indices) {
            add(tokens[index], weight = 1f, vector)
            if (index > 0) {
                // Bigrams capture short phrases ("boarding pass") without a vocabulary.
                add(tokens[index - 1] + " " + tokens[index], weight = 0.5f, vector)
            }
        }
        return l2Normalize(vector)
    }

    /** Unicode-aware lowercase tokenization: letters/digits runs, 2+ chars. */
    private fun tokenize(text: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.length >= 2) out.add(current.toString())
            current.clear()
        }
        for (ch in text.lowercase()) {
            if (ch.isLetterOrDigit()) {
                current.append(ch)
            } else {
                flush()
            }
        }
        flush()
        return out
    }

    private fun add(token: String, weight: Float, vector: FloatArray) {
        val hash = token.hashCode()
        val bucket = (hash and Int.MAX_VALUE) % dimension
        // Independent sign bit so collisions cancel instead of always reinforcing.
        val sign = if ((hash ushr 16) and 1 == 0) 1f else -1f
        vector[bucket] += sign * weight
    }

    private fun l2Normalize(vector: FloatArray): FloatArray {
        var sumSquares = 0.0
        for (value in vector) sumSquares += value.toDouble() * value
        val norm = kotlin.math.sqrt(sumSquares).toFloat()
        if (norm == 0f) return vector
        for (index in vector.indices) vector[index] /= norm
        return vector
    }
}
