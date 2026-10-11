package io.openshm.patcher.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/** Performs [type] once when the screen first shows; a rotation does not repeat it. */
@Composable
fun HapticOnce(type: HapticFeedbackType) {
    val haptic = LocalHapticFeedback.current
    var done by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!done) haptic.performHapticFeedback(type)
        done = true
    }
}

/**
 * Performs [type] when [condition] turns true. Being true on first composition, or again after a
 * rotation, does not fire: only the change does.
 */
@Composable
fun HapticWhen(condition: Boolean, type: HapticFeedbackType) {
    val haptic = LocalHapticFeedback.current
    var last by rememberSaveable { mutableStateOf(condition) }
    LaunchedEffect(condition) {
        if (condition && !last) haptic.performHapticFeedback(type)
        last = condition
    }
}
