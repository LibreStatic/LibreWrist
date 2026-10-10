package io.openshm.patcher.ui.screens.selftest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.openshm.patcher.R
import io.openshm.patcher.ui.components.CheckStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun selfTestSections(status: CheckStatus) = listOf(
    SelfTestSection(
        R.string.selftest_section_phone,
        listOf(
            SelfTestRow("phone-installed", R.string.selftest_phone_installed, status),
            SelfTestRow("phone-listener", R.string.selftest_phone_listener, status, fixable = true),
            SelfTestRow("phone-bluetooth", R.string.selftest_phone_bluetooth, status, fixable = true),
        ),
    ),
    SelfTestSection(
        R.string.selftest_section_watch,
        listOf(
            SelfTestRow("watch-shm", R.string.selftest_watch_shm, status),
            SelfTestRow("watch-bridge", R.string.selftest_watch_bridge, status),
            SelfTestRow("watch-platform", R.string.selftest_watch_platform, status),
        ),
    ),
    SelfTestSection(
        R.string.selftest_section_link,
        listOf(
            SelfTestRow("link-datalayer", R.string.selftest_link_datalayer, status),
            SelfTestRow("link-alarm", R.string.selftest_link_alarm, status),
        ),
    ),
)

private val humanChecks = listOf(R.string.selftest_human_buzz, R.string.selftest_human_ecg)

/** Pure answer handling: "yes" moves to the next question, "no" leaves the question for another try. */
internal fun SelfTestUiState.answer(yes: Boolean): SelfTestUiState {
    val current = humanCheck ?: return this
    if (!yes) return this
    val next = current.index + 1
    return copy(humanCheck = if (next > current.total) null else HumanCheck(next, current.total, humanChecks[next - 1]))
}

/**
 * Shell-stage view model: the automated rows resolve on a timer, then the two questions only the
 * user can answer. The real checks (package state, grants, Data Layer round trip) replace the timer.
 */
class SelfTestViewModel : ViewModel() {
    private val _state = MutableStateFlow(SelfTestUiState(selfTestSections(CheckStatus.Pending)))
    val state: StateFlow<SelfTestUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.update { SelfTestUiState(selfTestSections(CheckStatus.Running)) }
            delay(RUN_DELAY_MS)
            _state.update {
                SelfTestUiState(selfTestSections(CheckStatus.Passed), HumanCheck(1, humanChecks.size, humanChecks[0]))
            }
        }
    }

    fun onEvent(event: SelfTestEvent) {
        when (event) {
            is SelfTestEvent.Answer -> _state.update { it.answer(event.yes) }
            is SelfTestEvent.Fix, SelfTestEvent.Share -> Unit
        }
    }

    private companion object {
        const val RUN_DELAY_MS = 1200L
    }
}
