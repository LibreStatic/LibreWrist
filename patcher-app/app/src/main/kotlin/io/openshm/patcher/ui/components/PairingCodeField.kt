package io.openshm.patcher.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.openshm.patcher.ui.theme.LocalReducedMotion
import io.openshm.patcher.ui.theme.PatcherTheme

/** ADB pairing codes are always this many digits. */
const val PAIRING_CODE_LENGTH = 6

private const val SHAKE_AMPLITUDE_DP = 12f

/** WCAG contrast ratio between two colors, 1 (identical) to 21 (black on white). */
internal fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/** The red of the scheme that reads best on [containerColor]: `error` or `errorContainer`. */
@Composable
private fun readableError(containerColor: Color): Color {
    val scheme = MaterialTheme.colorScheme
    return if (contrastRatio(scheme.error, containerColor) >= contrastRatio(scheme.errorContainer, containerColor)) {
        scheme.error
    } else {
        scheme.errorContainer
    }
}

/**
 * Text field colors for a field sitting on a tinted container ([containerColor]), where the default
 * on-surface colors lose contrast: everything is [onColor], the error state a red readable on the container.
 */
@Composable
fun containerFieldColors(onColor: Color, containerColor: Color): TextFieldColors {
    val error = readableError(containerColor)
    val idle = onColor.copy(alpha = 0.7f)
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = onColor,
        unfocusedTextColor = onColor,
        errorTextColor = onColor,
        cursorColor = onColor,
        errorCursorColor = error,
        focusedBorderColor = onColor,
        unfocusedBorderColor = idle,
        errorBorderColor = error,
        focusedLabelColor = onColor,
        unfocusedLabelColor = onColor,
        errorLabelColor = error,
        focusedPlaceholderColor = idle,
        unfocusedPlaceholderColor = idle,
        errorPlaceholderColor = idle,
        focusedSupportingTextColor = onColor,
        unfocusedSupportingTextColor = onColor,
        errorSupportingTextColor = error,
        focusedTrailingIconColor = onColor,
        unfocusedTrailingIconColor = onColor,
        errorTrailingIconColor = error,
    )
}

/**
 * The field for a 6-digit pairing code. Input is reduced to digits and capped at
 * [PAIRING_CODE_LENGTH]; the caller decides what a complete code does.
 *
 * [busy] makes the field read-only (not disabled, so focus and the keyboard stay) with a spinner.
 * Each increase of [shakeKey] (a failed attempt) shakes the field with a rejecting haptic and hands
 * the focus and the keyboard back so a new code can be typed straight away. [autoFocus] takes the
 * focus and opens the keyboard when the field appears (or when it turns true). [onColor] and
 * [containerColor] are for a field inside a tinted container.
 */
@Composable
fun PairingCodeField(
    code: String,
    onCodeChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    shakeKey: Int = 0,
    busy: Boolean = false,
    autoFocus: Boolean = false,
    onColor: Color? = null,
    containerColor: Color? = null,
) {
    val offset = remember { Animatable(0f) }
    val focus = remember { FocusRequester() }
    val haptic = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    val reduced = LocalReducedMotion.current
    val amplitude = with(LocalDensity.current) { SHAKE_AMPLITUDE_DP.dp.toPx() }
    // The key already shown (first composition, rotation) is not a failure to report.
    val seen = remember { mutableIntStateOf(shakeKey) }
    LaunchedEffect(autoFocus) {
        if (!autoFocus) return@LaunchedEffect
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    LaunchedEffect(shakeKey) {
        if (shakeKey <= seen.intValue) return@LaunchedEffect
        seen.intValue = shakeKey
        haptic.performHapticFeedback(HapticFeedbackType.Reject)
        runCatching { focus.requestFocus() }
        keyboard?.show()
        if (!reduced) {
            offset.snapTo(0f)
            offset.animateTo(
                0f,
                keyframes {
                    durationMillis = 400
                    -amplitude at 50
                    amplitude at 120
                    -amplitude * 0.75f at 190
                    amplitude * 0.5f at 250
                    -amplitude * 0.25f at 310
                    amplitude * 0.1f at 355
                },
            )
        }
    }
    val colors = if (onColor != null && containerColor != null) {
        containerFieldColors(onColor, containerColor)
    } else {
        OutlinedTextFieldDefaults.colors()
    }
    OutlinedTextField(
        value = code,
        onValueChange = { onCodeChange(it.filter(Char::isDigit).take(PAIRING_CODE_LENGTH)) },
        modifier = modifier
            .focusRequester(focus)
            .graphicsLayer { translationX = offset.value },
        readOnly = busy,
        label = { Text(label) },
        singleLine = true,
        textStyle = PatcherTheme.text.mono,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        trailingIcon = if (busy) {
            { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = onColor ?: MaterialTheme.colorScheme.primary) }
        } else {
            null
        },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        colors = colors,
    )
}
