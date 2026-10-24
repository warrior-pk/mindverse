package dev.shipyu.mindverse.ui.ask

import dev.shipyu.mindverse.core.ask.AskBlock
import dev.shipyu.mindverse.core.ask.AskCitation
import dev.shipyu.mindverse.core.ask.AskService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One rendered turn in the Ask conversation. */
data class AskTurn(
    val query: String,
    val blocks: List<AskBlock>,
    val sources: List<AskCitation>,
    /** Set when the answer came from the keyless local fallback instead of the model. */
    val fallbackReason: String?,
    val streaming: Boolean,
    val error: String? = null,
)

/** Ask screen state (ticket 07): the conversation plus the in-flight turn, if any. */
data class AskUiState(
    val turns: List<AskTurn> = emptyList(),
    val pending: AskTurn? = null,
    val busy: Boolean = false,
)

/**
 * Ask conversation controller: runs one streaming turn at a time, flattening [AskService]'s
 * incremental emissions into the [AskTurn] list. Tap handling for citation chips is the
 * screen's job (deep-link to Memory detail).
 */
class AskController(
    private val service: AskService,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(AskUiState())
    val state: StateFlow<AskUiState> = _state.asStateFlow()

    private var job: Job? = null

    fun ask(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || _state.value.busy) return
        job = scope.launch {
            _state.value = _state.value.copy(busy = true)
            try {
                service.ask(trimmed).collect { response ->
                    _state.value = _state.value.copy(
                        pending = AskTurn(
                            query = response.query,
                            blocks = response.blocks,
                            sources = response.sources,
                            fallbackReason = response.fallbackReason,
                            streaming = response.streaming,
                        ),
                    )
                    if (!response.streaming) {
                        // Final emission: fold the pending turn into history.
                        val done = _state.value.pending ?: return@collect
                        _state.value = _state.value.copy(
                            turns = _state.value.turns + done,
                            pending = null,
                            busy = false,
                        )
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val pending = _state.value.pending
                _state.value = _state.value.copy(
                    turns = _state.value.turns + (pending ?: AskTurn(trimmed, emptyList(), emptyList(), null, streaming = false))
                        .copy(streaming = false, error = error.message ?: "Something went wrong"),
                    pending = null,
                    busy = false,
                )
            }
        }
    }

    /** User cancel of an in-flight turn (e.g. leaving the screen). */
    fun cancel() {
        job?.cancel()
        val pending = _state.value.pending
        if (pending != null) {
            _state.value = _state.value.copy(
                turns = _state.value.turns + pending.copy(streaming = false),
                pending = null,
            )
        }
        _state.value = _state.value.copy(busy = false)
    }
}
