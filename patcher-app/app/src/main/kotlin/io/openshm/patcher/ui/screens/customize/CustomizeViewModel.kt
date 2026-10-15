package io.openshm.patcher.ui.screens.customize

import androidx.lifecycle.ViewModel
import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.PackagePrefix
import io.openshm.patcher.data.RebuildScope
import io.openshm.patcher.data.SessionState
import io.openshm.patcher.data.SetupSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Builds the screen state from the profile, prefix and switched-off patches chosen earlier. */
internal fun customizeStateFor(session: SessionState, focusInputIds: Set<String>? = null): CustomizeUiState {
    val catalogue = AppCatalogue.current
    val disabled = catalogue.effectiveDisabled(session.disabledPatches)
    return CustomizeUiState(
        groups = session.inputIds.map { inputId ->
            val input = catalogue.input(inputId)
            CustomizeGroup(
                inputId = inputId,
                label = input.label,
                device = input.device,
                patches = catalogue.patches(inputId).map {
                    CustomizePatch(it, required = !it.optional, enabled = it.id !in disabled)
                },
            )
        },
        packagePrefix = session.packagePrefix,
        prefixLocked = session.prefixLocked,
        prefixProblem = PackagePrefix.validate(session.packagePrefix),
        focusInputIds = focusInputIds?.takeIf { it.isNotEmpty() },
    )
}

/**
 * The optional patches a focused screen may change: those of the focused groups, plus the members of
 * any option group they belong to (an option group switches as a whole, across apps). Everything when
 * nothing is focused.
 */
internal fun CustomizeUiState.editableIds(): Set<String> {
    val all = groups.flatMap { it.patches }.filter { !it.required }
    val focus = focusInputIds ?: return all.mapTo(mutableSetOf()) { it.info.id }
    val focused = visibleGroups.flatMap { it.patches }.filter { !it.required }
    val optionGroups = focused.mapNotNull { it.info.optionGroup }.toSet()
    return all.filter { p -> p in focused || p.info.optionGroup in optionGroups }.mapTo(mutableSetOf()) { it.info.id }
}

/**
 * The disabled set to store after this screen's change: [previous] with the editable patches taken
 * from the screen, so the patches of the groups left out of focus stay exactly as they were.
 */
internal fun CustomizeUiState.disabledIdsOver(previous: Set<String>): Set<String> {
    if (focusInputIds == null) return disabledIds
    val editable = editableIds()
    return previous.filterTo(mutableSetOf()) { it !in editable } + disabledIds.filter { it in editable }
}

internal fun CustomizeUiState.reduce(event: CustomizeEvent, regenerate: () -> String = PackagePrefix::generate): CustomizeUiState =
    when (event) {
        is CustomizeEvent.TogglePatch -> {
            val target = groups.firstOrNull { it.inputId == event.inputId }?.patches
                ?.firstOrNull { it.info.id == event.patchId && !it.required }
            if (target == null) this else {
                val enable = !target.enabled
                // an option group (e.g. sleep apnea) switches as a whole, in every app that has a member
                copy(
                    groups = groups.map { group ->
                        group.copy(
                            patches = group.patches.map { p ->
                                val sameGroup = target.info.optionGroup != null && p.info.optionGroup == target.info.optionGroup
                                if (!p.required && (p.info.id == target.info.id || sameGroup)) p.copy(enabled = enable) else p
                            },
                        )
                    },
                )
            }
        }
        is CustomizeEvent.SelectPreset -> editableIds().let { editable ->
            copy(
                groups = groups.map { group ->
                    group.copy(
                        patches = group.patches.map { p ->
                            if (p.required || p.info.id !in editable) p else p.copy(enabled = event.preset != Preset.Minimal)
                        },
                    )
                },
            )
        }
        CustomizeEvent.ToggleAdvanced -> copy(advancedExpanded = !advancedExpanded)
        is CustomizeEvent.PrefixChanged ->
            if (prefixLocked) this else copy(packagePrefix = event.prefix, prefixProblem = PackagePrefix.validate(event.prefix))
        CustomizeEvent.RegeneratePrefix ->
            if (prefixLocked) this else regenerate().let { copy(packagePrefix = it, prefixProblem = PackagePrefix.validate(it)) }
    }

/** During a rebuild, the inputs of the requested outputs; null for the normal flow. */
private fun rebuildFocus(): Set<String>? {
    val requested = RebuildScope.state.value?.requested ?: return null
    return requested.mapNotNullTo(mutableSetOf()) { AppCatalogue.current.output(it).inputId }
}

class CustomizeViewModel : ViewModel() {
    private val _state = MutableStateFlow(customizeStateFor(SetupSession.state.value, rebuildFocus()))
    val state: StateFlow<CustomizeUiState> = _state.asStateFlow()

    fun onEvent(event: CustomizeEvent) {
        val next = _state.value.reduce(event)
        _state.value = next
        if (event is CustomizeEvent.TogglePatch || event is CustomizeEvent.SelectPreset) {
            SetupSession.update { it.copy(disabledPatches = next.disabledIdsOver(it.disabledPatches)) }
        }
        if (event is CustomizeEvent.PrefixChanged || event is CustomizeEvent.RegeneratePrefix) {
            if (next.prefixProblem == null) SetupSession.update { it.copy(packagePrefix = next.packagePrefix) }
        }
    }
}
