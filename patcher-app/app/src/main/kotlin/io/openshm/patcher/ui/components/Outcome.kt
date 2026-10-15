package io.openshm.patcher.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.openshm.patcher.R
import io.openshm.patcher.ui.theme.LocalReducedMotion
import io.openshm.patcher.ui.theme.PatcherTheme
import kotlinx.coroutines.delay

/** How long [SuccessOverlay] stays up before it reports [SuccessOverlay]'s `onFinished`. */
internal const val SUCCESS_OVERLAY_MILLIS = 2000L

/**
 * A blocking error the user must acknowledge: it cannot be dismissed by tapping outside or going
 * back. [message] says what happened in plain words; [detail] is the technical text (an exception,
 * the missing grants), shown small, monospace and selectable so it can be copied into a report.
 */
@Composable
fun AppErrorDialog(title: String, message: String, detail: String?, onAcknowledge: () -> Unit) {
    LaunchedEffect(Unit) { UnattendedRun.fail() }
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
        containerColor = colors.errorContainer,
        iconContentColor = colors.error,
        titleContentColor = colors.onErrorContainer,
        textContentColor = colors.onErrorContainer,
        icon = { Icon(Icons.Rounded.ErrorOutline, contentDescription = null, modifier = Modifier.size(32.dp)) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                detail?.let {
                    SelectionContainer {
                        Text(it, style = PatcherTheme.text.packageId, color = colors.onErrorContainer)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAcknowledge) { Text(stringResource(R.string.outcome_acknowledge), color = colors.error) }
        },
    )
}

/**
 * A full-screen "it worked" confirmation: a large green check in a circle with a headline and a
 * line below. It scales and fades in (a plain fade when animations are off), gives a confirm
 * haptic, stays [SUCCESS_OVERLAY_MILLIS] and then calls [onFinished] once. Not dismissable.
 */
@Composable
fun SuccessOverlay(title: String, message: String, onFinished: () -> Unit) {
    val finished by rememberUpdatedState(onFinished)
    val haptic = LocalHapticFeedback.current
    val reduced = LocalReducedMotion.current
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        progress.animateTo(1f, tween(if (reduced) 250 else 450))
    }
    LaunchedEffect(Unit) {
        delay(SUCCESS_OVERLAY_MILLIS)
        finished()
    }
    val status = PatcherTheme.status
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false, usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).semantics { liveRegion = LiveRegionMode.Polite },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.padding(32.dp).alpha(progress.value),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    Modifier
                        .size(144.dp)
                        .scale(if (reduced) 1f else 0.6f + 0.4f * progress.value)
                        .clip(CircleShape)
                        .background(status.successContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = status.onSuccessContainer, modifier = Modifier.size(88.dp))
                }
                Text(title, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
                Text(
                    message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
