package dev.shipyu.mindverse.core.egress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Snapshot corpus test for [PiiMasker] (ticket 03 seam 2): every record asserts the
 * detected payload classes AND the exact masked output, so both classification and
 * non-leakage regressions surface. The corpus lives at egress/masking_corpus.txt.
 */
class PiiMaskerSnapshotCorpusTest {

    private data class CorpusCase(
        val input: String,
        val expectedClasses: Set<PayloadClass>,
        val expectedOutput: String,
    )

    private val cases: List<CorpusCase> by lazy { readCorpus() }

    private fun readCorpus(): List<CorpusCase> {
        val resource = checkNotNull(javaClass.classLoader.getResource(CORPUS_PATH)) {
            "masking corpus missing on the test classpath: $CORPUS_PATH"
        }
        return resource.readText().lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapIndexed { index, line ->
                val parts = line.split('\t')
                require(parts.size == 3) { "corpus line ${index + 1} must have 3 tab-separated columns" }
                val classes = if (parts[1] == "-") {
                    emptySet()
                } else {
                    parts[1].split(',').map { PayloadClass.valueOf(it.trim().uppercase()) }.toSet()
                }
                CorpusCase(parts[0], classes, parts[2])
            }
            .toList()
    }

    @Test
    fun maskingMatchesSnapshotCorpus() {
        val masker = PiiMasker()
        cases.forEachIndexed { index, case ->
            val masked = masker.mask(case.input)
            assertEquals(
                "line ${index + 1} classes mismatch for input:\n  ${case.input}\n  masked: ${masked.text}",
                case.expectedClasses,
                masked.classes,
            )
            assertEquals(
                "line ${index + 1} output mismatch for input:\n  ${case.input}\n  masked: ${masked.text}",
                case.expectedOutput,
                masked.text,
            )
        }
    }

    @Test
    fun corpusCoversEveryPayloadFamily() {
        val covered = cases.flatMap { it.expectedClasses }.toSet()
        assertEquals(PayloadClass.entries.toSet(), covered)
    }

    @Test
    fun emptyInputStaysEmpty() {
        val masked = PiiMasker().mask("")
        assertEquals("", masked.text)
        assertTrue(masked.classes.isEmpty())
    }

    private companion object {
        const val CORPUS_PATH = "egress/masking_corpus.txt"
    }
}
