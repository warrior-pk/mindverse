package dev.shipyu.mindverse.core.embed

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** Embedding seam: shape, determinism, and lexical closeness of the built-in engine. */
class FeatureHashingEmbeddingEngineTest {

    private val engine = FeatureHashingEmbeddingEngine(384)

    @Test
    fun `vectors are 384-wide and unit length`() = runBlocking {
        val vector = engine.embed("boarding pass to tokyo, gate 12")
        assertEquals(384, vector.size)
        val norm = sqrt(vector.sumOf { it.toDouble() * it })
        assertEquals(1.0, norm, 1e-6)
    }

    @Test
    fun `same text yields identical vectors and different text differs`() = runBlocking {
        val first = engine.embed("flight to tokyo")
        val again = engine.embed("flight to tokyo")
        val other = engine.embed("grocery receipt for milk")
        assertTrue(first.contentEquals(again))
        assertNotEquals(first.toList(), other.toList())
    }

    @Test
    fun `shared wording is closer than unrelated wording`() = runBlocking {
        val query = engine.embed("boarding pass flight tokyo gate")
        val related = engine.embed("flight to tokyo boarding gate 12")
        val unrelated = engine.embed("milk eggs bread receipt")

        assertTrue(cosine(query, related) > cosine(query, unrelated))
    }

    @Test
    fun `empty text yields a zero vector without crashing`() = runBlocking {
        val vector = engine.embed("   ")
        assertEquals(384, vector.size)
        assertTrue(vector.all { it == 0f })
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double =
        a.indices.sumOf { a[it].toDouble() * b[it] } // both unit length
}
