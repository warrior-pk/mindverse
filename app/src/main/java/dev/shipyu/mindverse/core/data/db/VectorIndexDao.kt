package dev.shipyu.mindverse.core.data.db

/** KNN result row from `memory_vec` (sqlite-vec vec0): Memory UUID + cosine distance. */
data class MemoryVectorHit(
    val memoryId: String,
    val distance: Double,
)

/** Single-column helper POJO for raw `SELECT COUNT(*) AS count` queries. */
data class CountRow(val count: Int)
