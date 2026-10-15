package io.openshm.patcher.ui.screens.watchpair

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.openshm.patcher.R
import io.openshm.patcher.data.WatchProfile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.openshm.patcher.data.Automation
import io.openshm.patcher.ui.components.Accent
import io.openshm.patcher.ui.components.CheckStatus
import io.openshm.patcher.ui.components.ContainerTextAction
import io.openshm.patcher.ui.components.FooterHint
import io.openshm.patcher.ui.components.HapticWhen
import io.openshm.patcher.ui.components.InstructionText
import io.openshm.patcher.ui.components.LocalTwoPane
import io.openshm.patcher.ui.components.PAIRING_CODE_LENGTH
import io.openshm.patcher.ui.components.PackageId
import io.openshm.patcher.ui.components.PairingCodeField
import io.openshm.patcher.ui.components.PhoneWatchIllustration
import io.openshm.patcher.ui.components.PrimaryAction
import io.openshm.patcher.ui.components.SetupStep
import io.openshm.patcher.ui.components.StatusShape
import io.openshm.patcher.ui.components.StepScreenScaffold
import io.openshm.patcher.ui.components.ATTENTION_GRACE_MILLIS
import io.openshm.patcher.ui.components.UnattendedAttention
import io.openshm.patcher.ui.components.containerFieldColors
import io.openshm.patcher.ui.theme.LocalReducedMotion
import io.openshm.patcher.ui.theme.PatcherTheme

data class WatchPairUiState(
    /** `host:port` of the Wireless debugging pairing service found by discovery, null while none was found. */
    val foundAddress: String? = null,
    val code: String = "",
    /** The step-by-step guide on a fake watch is showing; it closes by itself when the watch is found. */
    val guideOpen: Boolean = true,
    val guideStep: Int = 0,
    /** The user skipped the search and types the address (host and port) by hand. */
    val manual: Boolean = false,
    val host: String = "",
    val port: String = "",
    val pairing: Boolean = false,
    /** The watch accepted the code; the host navigates on. */
    val paired: Boolean = false,
    /** Resource id of the last pairing failure, shown under the code. */
    val errorRes: Int? = null,
    /** The watch disagrees with the chosen profile: shown for confirmation before moving on. */
    val correction: WatchProfileCorrection? = null,
    /** Failed pairing attempts so far; each one shakes the code field. */
    val failures: Int = 0,
    /** The remembered watch is being reconnected with the key it already trusts. */
    val reconnecting: Boolean = false,
    /** The pairing was reused instead of a new code; [reconnectedModel] is the model the watch announced, if any. */
    val reconnected: Boolean = false,
    val reconnectedModel: String? = null,
    /** The remembered watch did not answer: Wireless debugging is probably off on it. */
    val rememberedNotFound: Boolean = false,
) {
    val codeComplete: Boolean get() = code.length == PAIRING_CODE_LENGTH

    /** The typed host and port as `host:port`, null until both are filled in. */
    val manualAddress: String? get() = if (host.isNotBlank() && port.isNotBlank()) "${host.trim()}:${port.trim()}" else null

    /** The address pairing would use: the typed one in manual mode, the discovered one otherwise. */
    val address: String? get() = if (manual) manualAddress else foundAddress

    val hostInvalid: Boolean get() = host.isNotBlank() && parseAddress("${host.trim()}:1") == null
    val portInvalid: Boolean get() = port.isNotBlank() && port.trim().toIntOrNull()?.let { it in 1..65535 } != true

    /** The step-by-step guide shows: automatic mode, not dismissed, not pairing. */
    val guideVisible: Boolean get() = !manual && guideOpen && !pairing

    /** The code entry (and the host and port fields in manual mode) shows once there is somewhere to pair with; it stays, read-only, while pairing. */
    val entryVisible: Boolean get() = manual || foundAddress != null

    val canPair: Boolean get() = codeComplete && !pairing && address?.let { parseAddress(it) } != null
}

/** The profile the watch's own Android version led to, replacing the one chosen in step 1. */
data class WatchProfileCorrection(val profile: WatchProfile, val androidVersion: String)

sealed interface WatchPairEvent {
    data object ConfirmCorrection : WatchPairEvent
    data class CodeChanged(val code: String) : WatchPairEvent
    data class HostChanged(val host: String) : WatchPairEvent
    data class PortChanged(val port: String) : WatchPairEvent
    /** Discovery result: the watch's pairing address, or null when none is announced. */
    data class Discovered(val address: String?) : WatchPairEvent
    data class GuideStep(val step: Int) : WatchPairEvent
    data object ShowGuide : WatchPairEvent
    data object HideGuide : WatchPairEvent
    data object ToggleManual : WatchPairEvent
    data object Pair : WatchPairEvent

    /** A reconnect to the remembered watch started (shown; retries stay quiet). */
    data object ReconnectStarted : WatchPairEvent

    /** The remembered pairing still works; [correction] is the profile change the watch's version asks for, if any. */
    data class Reconnected(val model: String?, val correction: WatchProfileCorrection?) : WatchPairEvent

    /** The reconnect failed; [notFound] when the watch did not announce itself (rather than rejecting the key). */
    data class ReconnectFailed(val notFound: Boolean) : WatchPairEvent
}

@Composable
private fun enterTransition(): EnterTransition =
    if (LocalReducedMotion.current) fadeIn() else fadeIn() + expandVertically(spring(stiffness = 300f))

@Composable
private fun exitTransition(): ExitTransition =
    if (LocalReducedMotion.current) fadeOut() else fadeOut() + shrinkVertically(spring(stiffness = 300f))

@Composable
fun WatchPairScreen(
    state: WatchPairUiState,
    onEvent: (WatchPairEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val freeRunActive = Automation.state.collectAsStateWithLifecycle().value.freeRunActive
    HapticWhen(state.paired || state.correction != null, HapticFeedbackType.Confirm)
    // Searching, typing the code or after a refused one: only the user can enable Wireless debugging and pair.
    // A reconnect with the remembered pairing may still settle it, so it only waits longer before asking.
    UnattendedAttention(
        !state.pairing && !state.paired && state.correction == null,
        graceMillis = if (state.reconnecting) RECONNECT_ATTENTION_GRACE_MILLIS else ATTENTION_GRACE_MILLIS,
    )
    StepScreenScaffold(
        step = SetupStep.WatchPair,
        title = stringResource(R.string.watchpair_title),
        onBack = onBack,
        modifier = modifier,
        secondary = if (state.guideVisible && state.correction == null) {
            { Guide(state, onEvent) }
        } else {
            null
        },
        footer = {
            if (state.correction != null) {
                PrimaryAction(stringResource(R.string.action_continue), { onEvent(WatchPairEvent.ConfirmCorrection) }, auto = freeRunActive)
            } else {
                FooterHint(
                    stringResource(
                        when {
                            state.pairing -> R.string.watchpair_pairing
                            state.codeComplete -> R.string.watchpair_ready
                            else -> R.string.watchpair_hint
                        },
                    ),
                )
                PrimaryAction(
                    stringResource(R.string.watchpair_pair),
                    { onEvent(WatchPairEvent.Pair) },
                    enabled = state.canPair,
                    accent = Accent.Tertiary,
                )
            }
        },
    ) {
        Text(
            stringResource(R.string.watchpair_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.correction != null || state.reconnected) {
            // Already paired: the form and its guide are done, only the profile change needs a look.
            PairedCard(state)
            state.correction?.let { CorrectionCard(it) }
            return@StepScreenScaffold
        }
        PairPanel(state, onEvent)
    }
}

/**
 * The guide for the scaffold's secondary pane. Narrow, it renders nothing: the guide then sits
 * inside the pairing card ([PairPanel] reads [LocalTwoPane]).
 */
@Composable
private fun Guide(state: WatchPairUiState, onEvent: (WatchPairEvent) -> Unit) {
    if (!LocalTwoPane.current) return
    WatchPairGuide(
        step = state.guideStep,
        onStep = { onEvent(WatchPairEvent.GuideStep(it)) },
        onDone = { onEvent(WatchPairEvent.HideGuide) },
        compact = true,
    )
}

/** Discovery status, the guide when it is not in the second pane, the address and code entry, and the manual switch. */
@Composable
private fun PairPanel(
    state: WatchPairUiState,
    onEvent: (WatchPairEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val guideInline = !LocalTwoPane.current
    val fieldColors = containerFieldColors(MaterialTheme.colorScheme.onTertiaryContainer, MaterialTheme.colorScheme.tertiaryContainer)
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
        val reduced = LocalReducedMotion.current
        Column(
            Modifier.padding(16.dp).then(if (reduced) Modifier else Modifier.animateContentSize()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                PhoneWatchIllustration(scale = if (LocalTwoPane.current) 0.65f else 0.8f, linkColor = MaterialTheme.colorScheme.tertiary)
            }
            if (state.manual) {
                InstructionText(
                    stringResource(R.string.watchpair_manual_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            } else {
                // Above the guide, so "looking for your watch" stays in sight while the steps are followed.
                FoundRow(state, onEvent)
                if (state.rememberedNotFound && !state.reconnecting && state.foundAddress == null) {
                    InstructionText(
                        stringResource(R.string.watchpair_remembered_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                if (guideInline) {
                    AnimatedVisibility(state.guideVisible, enter = enterTransition(), exit = exitTransition()) {
                        WatchPairGuide(
                            step = state.guideStep,
                            onStep = { onEvent(WatchPairEvent.GuideStep(it)) },
                            onDone = { onEvent(WatchPairEvent.HideGuide) },
                        )
                    }
                }
            }
            AnimatedVisibility(state.entryVisible, enter = enterTransition(), exit = exitTransition()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.manual) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = state.host,
                                onValueChange = { onEvent(WatchPairEvent.HostChanged(it)) },
                                modifier = Modifier.weight(2f),
                                readOnly = state.pairing,
                                label = { Text(stringResource(R.string.watchpair_host_label)) },
                                singleLine = true,
                                colors = fieldColors,
                                textStyle = PatcherTheme.text.mono,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                isError = state.hostInvalid,
                                supportingText = if (state.hostInvalid) {
                                    { Text(stringResource(R.string.watchpair_error_bad_host)) }
                                } else {
                                    null
                                },
                            )
                            OutlinedTextField(
                                value = state.port,
                                onValueChange = { onEvent(WatchPairEvent.PortChanged(it)) },
                                modifier = Modifier.weight(1f),
                                readOnly = state.pairing,
                                label = { Text(stringResource(R.string.watchpair_port_label)) },
                                singleLine = true,
                                colors = fieldColors,
                                textStyle = PatcherTheme.text.mono,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                isError = state.portInvalid,
                                supportingText = if (state.portInvalid) {
                                    { Text(stringResource(R.string.watchpair_error_bad_port)) }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                    PairingCodeField(
                        code = state.code,
                        onCodeChange = { onEvent(WatchPairEvent.CodeChanged(it)) },
                        label = stringResource(R.string.watchpair_code_label),
                        modifier = Modifier.fillMaxWidth(),
                        error = state.errorRes?.let { stringResource(it) },
                        shakeKey = state.failures,
                        busy = state.pairing,
                        onColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    )
                }
            }
            AnimatedVisibility(!state.pairing && (state.manual || state.foundAddress == null), enter = enterTransition(), exit = exitTransition()) {
                ContainerTextAction(
                    stringResource(if (state.manual) R.string.watchpair_automatic else R.string.watchpair_manual),
                    { onEvent(WatchPairEvent.ToggleManual) },
                    onColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

@Composable
private fun PairedCard(state: WatchPairUiState) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PhoneWatchIllustration(scale = 0.8f, linkColor = MaterialTheme.colorScheme.tertiary)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusShape(CheckStatus.Passed, size = 24.dp)
                Text(
                    when {
                        !state.reconnected -> stringResource(R.string.watchpair_paired)
                        state.reconnectedModel != null -> stringResource(R.string.watchpair_reconnected_model, state.reconnectedModel)
                        else -> stringResource(R.string.watchpair_reconnected)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

@Composable
private fun CorrectionCard(correction: WatchProfileCorrection) {
    val text = when (correction.profile) {
        WatchProfile.WearOs5 -> R.string.watchpair_correct_newer
        WatchProfile.WearOs3Or4 -> R.string.watchpair_correct_older
    }
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Text(
            stringResource(text, correction.androidVersion),
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun FoundRow(state: WatchPairUiState, onEvent: (WatchPairEvent) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(32.dp).background(MaterialTheme.colorScheme.tertiary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                Icons.Rounded.Wifi, contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiary, modifier = Modifier.size(18.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(
                    when {
                        state.reconnecting -> R.string.watchpair_reconnecting
                        state.foundAddress != null -> R.string.watchpair_found
                        else -> R.string.watchpair_searching
                    },
                ),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            state.foundAddress?.let { PackageId(it, color = MaterialTheme.colorScheme.onTertiaryContainer) }
        }
        if (!state.guideOpen && !state.pairing) {
            ContainerTextAction(
                stringResource(R.string.watchpair_guide_again),
                { onEvent(WatchPairEvent.ShowGuide) },
                onColor = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

/** A reconnect attempt searches for up to 20 s; give it a head start before asking for the code. */
private const val RECONNECT_ATTENTION_GRACE_MILLIS = 10_000L
