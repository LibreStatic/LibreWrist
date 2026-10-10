package io.openshm.patcher.ui.screens.customize

import androidx.lifecycle.ViewModel
import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.PackagePrefix
import io.openshm.patcher.data.SessionState
import io.openshm.patcher.data.SetupSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Builds the screen state from the profile and prefix chosen earlier. Every catalogue patch is required today. */
internal fun customizeStateFor(session: SessionState): CustomizeUiState = CustomizeUiState(
    groups = session.watchProfile.inputIds.map { inputId ->
        val input = AppCatalogue.current.input(inputId)
        CustomizeGroup(
            inputId = inputId,
            label = input.label,
            device = input.device,
            patches = AppCatalogue.current.patches(inputId).map { CustomizePatch(it) },
        )
    },
    packagePrefix = session.packagePrefix,
    prefixLocked = session.prefixLocked,
    prefixProblem = PackagePrefix.validate(session.packagePrefix),
)

internal fun CustomizeUiState.reduce(event: CustomizeEvent, regenerate: () -> String = PackagePrefix::generate): CustomizeUiState =
    when (event) {
        is CustomizeEvent.TogglePatch -> copy(
            groups = groups.map { group ->
                if (group.inputId != event.inputId) group
                else group.copy(
                    patches = group.patches.map { p ->
                        if (p.info.id == event.patchId && !p.required) p.copy(enabled = !p.enabled) else p
                    },
                )
            },
        )
        is CustomizeEvent.SelectPreset -> copy(
            preset = event.preset,
            groups = groups.map { group ->
                group.copy(
                    patches = group.patches.map { p ->
                        if (p.required) p else p.copy(enabled = event.preset != Preset.Minimal)
                    },
                )
            },
        )
        CustomizeEvent.ToggleAdvanced -> copy(advancedExpanded = !advancedExpanded)
        is CustomizeEvent.PrefixChanged ->
            if (prefixLocked) this else copy(packagePrefix = event.prefix, prefixProblem = PackagePrefix.validate(event.prefix))
        CustomizeEvent.RegeneratePrefix ->
            if (prefixLocked) this else regenerate().let { copy(packagePrefix = it, prefixProblem = PackagePrefix.validate(it)) }
    }

class CustomizeViewModel : ViewModel() {
    private val _state = MutableStateFlow(customizeStateFor(SetupSession.state.value))
    val state: StateFlow<CustomizeUiState> = _state.asStateFlow()

    fun onEvent(event: CustomizeEvent) {
        val next = _state.value.reduce(event)
        _state.value = next
        if (event is CustomizeEvent.PrefixChanged || event is CustomizeEvent.RegeneratePrefix) {
            if (next.prefixProblem == null) SetupSession.update { it.copy(packagePrefix = next.packagePrefix) }
        }
    }
}
