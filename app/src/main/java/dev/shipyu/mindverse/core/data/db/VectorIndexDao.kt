package dev.shipyu.mindverse.core.data.db

/** KNN result row from `memory_vec` (sqlite-vec vec0): Memory UUID + cosine distance. */
data class MemoryVectorHit(
    val memoryId: String,
    val distance: Double,
)
