package io.openshm.patcher.ui.screens.acquire

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.openshm.patcher.R
import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.DownloadRequest
import io.openshm.patcher.data.ImportResult
import io.openshm.patcher.data.InputApp
import io.openshm.patcher.data.InputStore
import io.openshm.patcher.data.RejectReason
import io.openshm.patcher.data.SAMSUNG_HEALTH_INPUT
import io.openshm.patcher.data.SetupSession
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun AcquireUiState.updateCard(inputId: String, change: (AcquireCard) -> AcquireCard) =
    copy(cards = cards.map { if (it.input.id == inputId) change(it) else it })

internal fun AcquireUiState.reduce(event: AcquireEvent): AcquireUiState = when (event) {
    is AcquireEvent.ToggleMoreWays -> updateCard(event.inputId) { it.copy(moreWaysExpanded = !it.moreWaysExpanded) }
    is AcquireEvent.UseFile -> updateCard(event.inputId) { it.copy(moreWaysExpanded = false) }
    is AcquireEvent.OpenMirror -> this
    is AcquireEvent.ToggleSamsungHealth -> this
}

/**
 * The cards for [inputIds], in order. A card already in [existing] keeps its progress; a new one
 * starts from [initialProgress].
 */
internal fun buildCards(
    inputIds: List<String>,
    existing: List<AcquireCard>,
    inputOf: (String) -> InputApp,
    initialProgress: (InputApp) -> AcquireProgress,
): List<AcquireCard> = inputIds.map { id ->
    existing.firstOrNull { it.input.id == id } ?: inputOf(id).let { AcquireCard(it, initialProgress(it)) }
}

internal fun RejectReason.messageRes(): Int = when (this) {
    RejectReason.WrongApp -> R.string.acquire_rejected_wrong_app
    RejectReason.WrongVersion -> R.string.acquire_rejected_wrong_version
    RejectReason.NotExpectedFile -> R.string.acquire_rejected_not_expected
    RejectReason.Unreadable -> R.string.acquire_rejected_unreadable
    RejectReason.DownloadFailed -> R.string.acquire_rejected_download_failed
    RejectReason.TooLarge -> R.string.acquire_rejected_too_large
}

/**
 * Cards for the inputs the chosen watch needs. "Use a file I already have" asks the screen to open the
 * document picker ([pickRequests]); the chosen file is streamed, hashed and checked against the
 * catalogue by [InputStore].
 */
class AcquireViewModel(application: Application) : AndroidViewModel(application) {
    private val store = InputStore(application)
    private val _state = MutableStateFlow(AcquireUiState(emptyList()))
    val state: StateFlow<AcquireUiState> = _state.asStateFlow()

    private val picks = Channel<String>(Channel.BUFFERED)

    /** Input ids for which the screen should open the document picker. */
    val pickRequests: Flow<String> = picks.receiveAsFlow()

    private var pendingPick: String? = null

    init {
        _state.value = AcquireUiState(
            cards = cardsFor(emptyList()),
            samsungHealthOffered = AppCatalogue.current.hasInput(SAMSUNG_HEALTH_INPUT),
            samsungHealth = SetupSession.state.value.samsungHealth,
        )
    }

    private fun cardsFor(existing: List<AcquireCard>) = buildCards(
        SetupSession.state.value.inputIds, existing, AppCatalogue.current::input,
    ) { input -> store.verifiedSize(input)?.let { AcquireProgress.Verified(it) } ?: AcquireProgress.Missing }

    fun onEvent(event: AcquireEvent) {
        if (event is AcquireEvent.ToggleSamsungHealth) {
            SetupSession.update { it.copy(samsungHealth = !it.samsungHealth) }
            _state.update { it.copy(cards = cardsFor(it.cards), samsungHealth = SetupSession.state.value.samsungHealth) }
            return
        }
        _state.update { it.reduce(event) }
        if (event is AcquireEvent.UseFile) {
            pendingPick = event.inputId
            picks.trySend(event.inputId)
        }
    }

    /** The picker's result; null when the user backed out. */
    fun onFilePicked(uri: Uri?) {
        val inputId = pendingPick ?: return
        pendingPick = null
        if (uri == null) return
        val input = AppCatalogue.current.input(inputId)
        runImport(inputId) { onProgress -> store.import(input, uri, onProgress) }
    }

    /** A download the guided browser intercepted; shown and verified exactly like a picked file. */
    fun downloadFrom(inputId: String, request: DownloadRequest) {
        val input = AppCatalogue.current.input(inputId)
        if (_state.value.cards.firstOrNull { it.input.id == inputId }?.progress is AcquireProgress.Downloading) return
        runImport(inputId) { onProgress -> store.download(input, request, onProgress) }
    }

    private fun runImport(inputId: String, import: suspend ((Long, Long) -> Unit) -> ImportResult) {
        viewModelScope.launch {
            _state.update { s -> s.updateCard(inputId) { it.copy(progress = AcquireProgress.Downloading(0, 0)) } }
            val result = import { done, total ->
                _state.update { s -> s.updateCard(inputId) { it.copy(progress = AcquireProgress.Downloading(done, total)) } }
            }
            _state.update { s ->
                s.updateCard(inputId) {
                    it.copy(
                        progress = when (result) {
                            is ImportResult.Verified -> AcquireProgress.Verified(result.sizeBytes)
                            is ImportResult.Rejected -> AcquireProgress.Rejected(result.reason.messageRes())
                        },
                    )
                }
            }
        }
    }
}
