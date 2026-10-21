package io.openshm.patcher.ui.screens.acquire

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.openshm.patcher.R
import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.Automation
import io.openshm.patcher.data.DownloadRequest
import io.openshm.patcher.data.ImportResult
import io.openshm.patcher.data.InputApp
import io.openshm.patcher.data.InputStore
import io.openshm.patcher.data.LoadedCatalogue
import io.openshm.patcher.data.RejectReason
import io.openshm.patcher.data.Region
import io.openshm.patcher.data.SetupSession
import io.openshm.patcher.data.chinaModeActive
import io.openshm.patcher.data.effectiveChinaMode
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
    is AcquireEvent.ToggleApp -> this
}

/** [id] removed from this set when present, added when absent. */
internal fun Set<String>.symmetricDifference(id: String): Set<String> = if (id in this) this - id else this + id

/**
 * The switches of the optional catalogue apps that have at least one output for a watch with SDK
 * [watchSdk], on unless listed in [skipped].
 */
internal fun optionalToggles(catalogue: LoadedCatalogue, watchSdk: Int, skipped: Set<String>): List<OptionalAppToggle> {
    val available = catalogue.selectedOutputs(watchSdk, emptySet()).map { it.id }.toSet()
    val effective = catalogue.effectiveSkipped(skipped)
    return catalogue.apps.filter { app -> app.optional && app.outputIds.any { it in available } }
        .map { OptionalAppToggle(it, it.id !in effective) }
}

/**
 * [skipped] after the user flips [appId]: switching an app off also switches off every app that
 * requires it, switching it on also switches on the apps it requires. An app whose requirement is
 * off counts as off ([LoadedCatalogue.effectiveSkipped]).
 */
internal fun toggledSkips(catalogue: LoadedCatalogue, skipped: Set<String>, appId: String): Set<String> {
    val effective = catalogue.effectiveSkipped(skipped)
    return if (appId in effective) effective - catalogue.withRequirements(appId) else effective + catalogue.withDependents(appId)
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
            optionalApps = optionalToggles(AppCatalogue.current, SetupSession.state.value.watchProfile.sdk, SetupSession.state.value.skippedApps),
            chinaMode = Automation.state.value.chinaModeActive(Region.detected),
        )
        viewModelScope.launch {
            // China mode also swaps the official app Samsung Health+ is acquired from, so the cards follow it
            Automation.state.collect { a ->
                _state.update { it.copy(chinaMode = a.chinaModeActive(Region.detected), cards = cardsFor(it.cards)) }
            }
        }
    }

    private fun cardsFor(existing: List<AcquireCard>) = buildCards(
        SetupSession.state.value.inputIds(effectiveChinaMode()), existing, AppCatalogue.current::input,
    ) { input -> store.verifiedSize(input)?.let { AcquireProgress.Verified(it) } ?: AcquireProgress.Missing }

    fun onEvent(event: AcquireEvent) {
        if (event is AcquireEvent.ToggleApp) {
            SetupSession.update { it.copy(skippedApps = toggledSkips(AppCatalogue.current, it.skippedApps, event.appId)) }
            _state.update {
                it.copy(cards = cardsFor(it.cards), optionalApps = optionalToggles(AppCatalogue.current, SetupSession.state.value.watchProfile.sdk, SetupSession.state.value.skippedApps))
            }
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
