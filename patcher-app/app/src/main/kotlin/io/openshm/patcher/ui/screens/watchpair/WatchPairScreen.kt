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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.openshm.patcher.R
import io.openshm.patcher.data.WatchProfile
import io.openshm.patcher.ui.components.Accent
import io.openshm.patcher.ui.components.CheckStatus
import io.openshm.patcher.ui.components.FooterHint
import io.openshm.patcher.ui.components.PackageId
import io.openshm.patcher.ui.components.PhoneWatchIllustration
import io.openshm.patcher.ui.components.PrimaryAction
import io.openshm.patcher.ui.components.SetupStep
import io.openshm.patcher.ui.components.StatusShape
import io.openshm.patcher.ui.components.StepScreenScaffold
import io.openshm.patcher.ui.theme.LocalReducedMotion
import io.openshm.patcher.ui.theme.PatcherTheme

const val PAIRING_CODE_LENGTH = 6

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
) {
    val codeComplete: Boolean get() = code.length == PAIRING_CODE_LENGTH

    /** The typed host and port as `host:port`, null until both are filled in. */
    val manualAddress: String? get() = if (host.isNotBlank() && port.isNotBlank()) "${host.trim()}:${port.trim()}" else null

    /** The address pairing would use: the typed one in manual mode, the discovered one otherwise. */
    val address: String? get() = if (manual) manualAddress else foundAddress

    val hostInvalid: Boolean get() = host.isNotBlank() && parseAddress("${host.trim()}:1") == null
    val portInvalid: Boolean get() = port.isNotBlank() && port.trim().toIntOrNull()?.let { it in 1..65535 } != true

    /** The code entry (and the host and port fields in manual mode) shows once there is somewhere to pair with. */
    /** The step-by-step guide shows: automatic mode, not dismissed, not pairing. */
    val guideVisible: Boolean get() = !manual && guideOpen && !pairing

    val entryVisible: Boolean get() = !pairing && (manual || foundAddress != null)

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
    StepScreenScaffold(
        step = SetupStep.WatchPair,
        title = stringResource(R.string.watchpair_title),
        onBack = onBack,
        modifier = modifier,
        footer = {
            if (state.correction != null) {
                PrimaryAction(stringResource(R.string.action_continue), { onEvent(WatchPairEvent.ConfirmCorrection) })
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
        if (state.correction != null) {
            // Already paired: the form and its guide are done, only the profile change needs a look.
            PairedCard()
            CorrectionCard(state.correction)
            return@StepScreenScaffold
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < SPLIT_MIN_WIDTH) {
                PairPanel(state, onEvent, guideInline = true)
            } else {
                // Wide screens (tablets, open foldables): the guide beside the status and the code, not above them.
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                    PairPanel(state, onEvent, guideInline = false, modifier = Modifier.weight(1f))
                    AnimatedVisibility(state.guideVisible, modifier = Modifier.weight(1f), enter = fadeIn(), exit = fadeOut()) {
                        Guide(state, onEvent)
                    }
                }
            }
        }
    }
}

/** Below this width the guide sits inside the pairing card; from it on, beside the card. */
private val SPLIT_MIN_WIDTH = 600.dp

@Composable
private fun Guide(state: WatchPairUiState, onEvent: (WatchPairEvent) -> Unit) {
    WatchPairGuide(
        step = state.guideStep,
        onStep = { onEvent(WatchPairEvent.GuideStep(it)) },
        onDone = { onEvent(WatchPairEvent.HideGuide) },
    )
}

/** Discovery status, the guide when [guideInline], the address and code entry, and the manual switch. */
@Composable
private fun PairPanel(
    state: WatchPairUiState,
    onEvent: (WatchPairEvent) -> Unit,
    guideInline: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
        val reduced = LocalReducedMotion.current
        Column(
            Modifier.padding(16.dp).then(if (reduced) Modifier else Modifier.animateContentSize()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                PhoneWatchIllustration(scale = 0.8f, linkColor = MaterialTheme.colorScheme.tertiary)
            }
            if (state.manual) {
                Text(
                    stringResource(R.string.watchpair_manual_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            } else {
                // Above the guide, so "looking for your watch" stays in sight while the steps are followed.
                FoundRow(state, onEvent)
                if (guideInline) {
                    AnimatedVisibility(state.guideVisible, enter = enterTransition(), exit = exitTransition()) {
                        Guide(state, onEvent)
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
                                label = { Text(stringResource(R.string.watchpair_host_label)) },
                                singleLine = true,
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
                                label = { Text(stringResource(R.string.watchpair_port_label)) },
                                singleLine = true,
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
                    OutlinedTextField(
                        value = state.code,
                        onValueChange = { onEvent(WatchPairEvent.CodeChanged(it.filter(Char::isDigit).take(PAIRING_CODE_LENGTH))) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.pairing,
                        label = { Text(stringResource(R.string.watchpair_code_label)) },
                        singleLine = true,
                        textStyle = PatcherTheme.text.mono,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        isError = state.errorRes != null,
                        supportingText = state.errorRes?.let { res -> { Text(stringResource(res)) } },
                    )
                }
            }
            AnimatedVisibility(state.pairing, enter = enterTransition(), exit = exitTransition()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.tertiary)
                    Text(
                        stringResource(R.string.watchpair_pairing),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            AnimatedVisibility(!state.pairing && (state.manual || state.foundAddress == null), enter = enterTransition(), exit = exitTransition()) {
                TextButton(onClick = { onEvent(WatchPairEvent.ToggleManual) }) {
                    Text(stringResource(if (state.manual) R.string.watchpair_automatic else R.string.watchpair_manual))
                }
            }
        }
    }
}

@Composable
private fun PairedCard() {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PhoneWatchIllustration(scale = 0.8f, linkColor = MaterialTheme.colorScheme.tertiary)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusShape(CheckStatus.Passed, size = 24.dp)
                Text(
                    stringResource(R.string.watchpair_paired),
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
                    if (state.foundAddress != null) R.string.watchpair_found else R.string.watchpair_searching,
                ),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            state.foundAddress?.let { PackageId(it, color = MaterialTheme.colorScheme.onTertiaryContainer) }
        }
        if (!state.guideOpen && !state.pairing) {
            TextButton(onClick = { onEvent(WatchPairEvent.ShowGuide) }) {
                Text(stringResource(R.string.watchpair_guide_again))
            }
        }
    }
}
