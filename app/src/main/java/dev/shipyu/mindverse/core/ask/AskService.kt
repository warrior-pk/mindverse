package dev.shipyu.mindverse.core.ask

import dev.shipyu.mindverse.core.data.db.MemoryEntity
import dev.shipyu.mindverse.core.egress.AiChatRequest
import dev.shipyu.mindverse.core.egress.AiFailure
import dev.shipyu.mindverse.core.egress.AiGateway
import dev.shipyu.mindverse.core.egress.AiMessage
import dev.shipyu.mindverse.core.egress.AiRole
import dev.shipyu.mindverse.core.egress.AiStreamEvent
import dev.shipyu.mindverse.core.egress.EgressFeature
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** One source Memory chip attached to an answer block (deep-links to Memory detail). */
data class AskCitation(
    val index: Int,
    val memoryId: String,
    val title: String,
)

/** One streaming block in the answer: prose plus the citation chips it referenced. */
data class AskBlock(
    val text: String,
    val citations: List<AskCitation>,
)

/** Live state emitted while an Ask turn runs. */
data class AskResponse(
    val query: String,
    /** Top-K retrieved Memories, numbered from 1 (matching `[1]` markers in the prompt). */
    val sources: List<AskCitation>,
    val answerText: String,
    val blocks: List<AskBlock>,
    val streaming: Boolean,
    /** Non-null when the answer degraded to pure retrieval (keyless / toggle off / error). */
    val fallbackReason: String? = null,
)

/**
 * Grounded Ask engine (ticket 07): retrieves top-K Memories locally, then streams an
 * answer through the egress chokepoint with a prompt that strictly forbids answering from
 * model knowledge. When no key is configured, the Ask egress toggle is off, or the call
 * fails before producing text, it degrades to a paragraph-keyword summary of the retrieved
 * Memories so the app is usable keyless (spec R1-2).
 */
class AskService(
    private val retriever: HybridRetriever,
    private val ai: AiGateway,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {

    fun ask(query: String, topK: Int = HybridRetriever.DEFAULT_TOP_K): Flow<AskResponse> = flow {
        val trimmed = query.trim()
        val hits = retriever.retrieve(trimmed, topK)
        if (hits.isEmpty()) {
            emit(
                AskResponse(
                    query = trimmed,
                    sources = emptyList(),
                    answerText = "No matching Memories found on this device.",
                    blocks = emptyList(),
                    streaming = false,
                ),
            )
            return@flow
        }

        val citations = hits.mapIndexed { index, hit ->
            AskCitation(index = index + 1, memoryId = hit.memory.id, title = hit.memory.title)
        }
        val byIndex = citations.associateBy { it.index }

        emit(
            AskResponse(
                query = trimmed,
                sources = citations,
                answerText = "",
                blocks = emptyList(),
                streaming = true,
            ),
        )

        val request = AiChatRequest(
            feature = EgressFeature.ASK,
            requestId = idFactory(),
            systemPrompt = SYSTEM_PROMPT,
            messages = listOf(
                AiMessage(
                    role = AiRole.USER,
                    text = buildPrompt(trimmed, hits.map { it.memory }),
                ),
            ),
            temperature = 0.2,
        )

        val buffer = StringBuilder()
        var failed: AiFailure? = null
        ai.chatStreaming(request).collect { event ->
            when (event) {
                is AiStreamEvent.Delta -> {
                    buffer.append(event.text)
                    val current = buffer.toString()
                    emit(
                        AskResponse(
                            query = trimmed,
                            sources = citations,
                            answerText = current,
                            blocks = splitBlocks(current, byIndex),
                            streaming = true,
                        ),
                    )
                }
                is AiStreamEvent.Completed -> {
                    if (buffer.isEmpty()) buffer.append(event.reply.text)
                }
                is AiStreamEvent.Failed -> failed = event.failure
            }
        }

        val finalText = buffer.toString().trim()
        if (finalText.isNotEmpty()) {
            emit(
                AskResponse(
                    query = trimmed,
                    sources = citations,
                    answerText = finalText,
                    blocks = splitBlocks(finalText, byIndex),
                    streaming = false,
                ),
            )
        } else {
            // Keyless / toggle-off / network failure: pure local keyword-paragraph fallback.
            val fallback = keywordFallback(hits.map { it.memory })
            emit(
                AskResponse(
                    query = trimmed,
                    sources = citations,
                    answerText = fallback,
                    blocks = splitBlocks(fallback, byIndex),
                    streaming = false,
                    fallbackReason = failed?.shortDescription ?: "answered locally from matching Memories",
                ),
            )
        }
    }

    companion object {
        const val SYSTEM_PROMPT =
            "You answer questions over the user's own local Memories. " +
                "STRICT GROUNDING: use ONLY the numbered Memories provided in the prompt. " +
                "Never answer from general or training knowledge, never guess, never extrapolate. " +
                "If the Memories do not contain the answer, say so plainly. " +
                "Cite every factual statement with the bracketed number of its source Memory, e.g. [1] or [2]."

        private val CITATION_MARKER = Regex("""\[(\d+)]""")
        private const val MAX_EXTRACTED_CHARS = 1_200

        internal fun buildPrompt(query: String, memories: List<MemoryEntity>): String = buildString {
            appendLine("Memories on this device:")
            memories.forEachIndexed { index, memory ->
                appendLine()
                appendLine("[${index + 1}] ${memory.title}")
                memory.category?.let { appendLine("Category: $it") }
                if (memory.tags.isNotEmpty()) appendLine("Tags: ${memory.tags.joinToString(", ")}")
                appendLine("Summary: ${memory.summary}")
                memory.extractedText?.takeIf { it.isNotBlank() }?.let {
                    appendLine("Content: ${it.take(MAX_EXTRACTED_CHARS)}")
                }
            }
            appendLine()
            append("Question: ")
            append(query)
        }

        /**
         * Splits the answer into paragraph blocks and resolves `[n]` markers in each
         * block against the numbered retrieved sources. If the model omitted markers on a
         * single-source answer, the lone source is attached so the chip still shows.
         */
        internal fun splitBlocks(
            text: String,
            sourcesByIndex: Map<Int, AskCitation>,
        ): List<AskBlock> {
            val paragraphs = text.split(Regex("""\n\s*\n"""))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (paragraphs.isEmpty()) return emptyList()
            val singleSource = sourcesByIndex.values.singleOrNull()
            return paragraphs.map { paragraph ->
                val cited = CITATION_MARKER.findAll(paragraph)
                    .mapNotNull { match -> match.groupValues[1].toIntOrNull()?.let(sourcesByIndex::get) }
                    .distinctBy { it.memoryId }
                    .toList()
                AskBlock(
                    text = paragraph,
                    citations = cited.ifEmpty { listOfNotNull(singleSource) },
                )
            }
        }

        /** Keyless / offline paragraph fallback: one cited paragraph per retrieved Memory. */
        internal fun keywordFallback(memories: List<MemoryEntity>): String =
            memories.mapIndexed { index, memory ->
                val snippet = memory.extractedText?.lineSequence()?.firstOrNull { it.isNotBlank() }
                buildString {
                    append("${memory.title} — ${memory.summary}")
                    if (!snippet.isNullOrBlank() && snippet != memory.summary) {
                        append(" (“${snippet.take(140)}”)")
                    }
                    append(" [${index + 1}]")
                }
            }.joinToString("\n\n")
    }
}
