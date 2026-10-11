package io.openshm.patcher.ui.screens.watchpair

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
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Wifi
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
import io.openshm.patcher.ui.theme.PatcherTheme

const val PAIRING_CODE_LENGTH = 6

data class WatchPairUiState(
    /** `host:port` of the Wireless debugging pairing service, null while no watch has been found. */
    val foundAddress: String? = null,
    val code: String = "",
    val editingAddress: Boolean = false,
    val pairing: Boolean = false,
    /** The watch accepted the code; the host navigates on. */
    val paired: Boolean = false,
    /** Resource id of the last pairing failure, shown under the code. */
    val errorRes: Int? = null,
    val prerequisitesDone: List<Boolean> = listOf(false, false, false, false),
    /** The watch disagrees with the chosen profile: shown for confirmation before moving on. */
    val correction: WatchProfileCorrection? = null,
) {
    val codeComplete: Boolean get() = code.length == PAIRING_CODE_LENGTH
    val canPair: Boolean get() = codeComplete && !foundAddress.isNullOrBlank() && !pairing &&
        parseAddress(foundAddress) != null
}

/** The profile the watch's own Android version led to, replacing the one chosen in step 1. */
data class WatchProfileCorrection(val profile: WatchProfile, val androidVersion: String)

sealed interface WatchPairEvent {
    data object ConfirmCorrection : WatchPairEvent
    data class CodeChanged(val code: String) : WatchPairEvent
    data class AddressChanged(val address: String) : WatchPairEvent
    data object ToggleEditAddress : WatchPairEvent
    data object Pair : WatchPairEvent
}

private val prerequisiteTexts = listOf(
    R.string.watchpair_check_wifi,
    R.string.watchpair_check_developer,
    R.string.watchpair_check_wireless,
    R.string.watchpair_check_code,
)

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
                FooterHint(stringResource(if (state.codeComplete) R.string.watchpair_ready else R.string.watchpair_hint))
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
            // Already paired: the form and its checklist are done, only the profile change needs a look.
            PairedCard()
            CorrectionCard(state.correction)
            return@StepScreenScaffold
        }
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    PhoneWatchIllustration(scale = 0.8f, linkColor = MaterialTheme.colorScheme.tertiary)
                }
                Text(
                    stringResource(R.string.watchpair_on_watch),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    stringResource(R.string.watchpair_on_watch_steps),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                FoundRow(state, onEvent)
                OutlinedTextField(
                    value = state.code,
                    onValueChange = { onEvent(WatchPairEvent.CodeChanged(it.filter(Char::isDigit).take(PAIRING_CODE_LENGTH))) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.watchpair_code_label)) },
                    singleLine = true,
                    textStyle = PatcherTheme.text.mono,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = state.errorRes != null,
                    supportingText = state.errorRes?.let { res -> { Text(stringResource(res)) } },
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            prerequisiteTexts.forEachIndexed { index, res ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusShape(
                        if (state.prerequisitesDone.getOrNull(index) == true) CheckStatus.Passed else CheckStatus.Pending,
                        size = 24.dp,
                    )
                    Text(stringResource(res), style = MaterialTheme.typography.bodyMedium)
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            TextButton(onClick = { onEvent(WatchPairEvent.ToggleEditAddress) }) {
                androidx.compose.material3.Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.action_edit), modifier = Modifier.padding(start = 4.dp))
            }
        }
        if (state.editingAddress) {
            OutlinedTextField(
                value = state.foundAddress.orEmpty(),
                onValueChange = { onEvent(WatchPairEvent.AddressChanged(it.trim())) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.watchpair_address_label)) },
                singleLine = true,
                textStyle = PatcherTheme.text.mono,
            )
        }
    }
}
