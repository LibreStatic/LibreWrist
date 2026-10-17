package io.openshm.patcher.device.guide

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.openshm.patcher.R
import io.openshm.patcher.data.Automation
import io.openshm.patcher.device.LaunchRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Starts the Settings screen behind a [LaunchRequest], with Tic on it when the request names a guide and
 * the interactive guide is on. When the accessibility service is still off, a dialog first offers to
 * turn it on (that step is always the user's). Once it is on, Tic brings the user back and greets them; the
 * screen opens when the user comes back, guided or not.
 */
@Composable
fun rememberSettingsLauncher(): (LaunchRequest) -> Unit {
    val context = LocalContext.current
    var asking by remember { mutableStateOf<LaunchRequest?>(null) }
    var pending by remember { mutableStateOf<LaunchRequest?>(null) }
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val request = pending ?: return@LifecycleEventEffect
        pending = null
        GuideSession.greetOnConnect = false
        when {
            !GuideAccessibilityService.isEnabled(context) -> startPlain(context, request)
            // Tic just said hello over the app: let that be read before the next screen covers it.
            SystemClock.uptimeMillis() - GuideSession.greetedAt < GuideSession.GREETING_MS -> scope.launch {
                delay(GuideSession.greetedAt + GuideSession.GREETING_MS - SystemClock.uptimeMillis())
                startGuided(context, request)
            }
            else -> startGuided(context, request)
        }
    }
    asking?.let { request ->
        EnableGuideDialog(
            onEnable = {
                asking = null
                pending = request
                GuideSession.greetOnConnect = true
                if (!openAccessibilitySettings(context)) {
                    pending = null
                    GuideSession.greetOnConnect = false
                    startPlain(context, request)
                }
            },
            onNotNow = {
                asking = null
                startPlain(context, request)
            },
        )
    }
    return remember(context) {
        { request ->
            when {
                request.guide == null || !Automation.state.value.guidePet -> startPlain(context, request)
                GuideAccessibilityService.isEnabled(context) -> startGuided(context, request)
                else -> asking = request
            }
        }
    }
}

/** False when neither the screen nor its fallback could be started. */
private fun startPlain(context: Context, request: LaunchRequest): Boolean =
    try {
        context.startActivity(request.intent)
        true
    } catch (e: ActivityNotFoundException) {
        request.fallback?.let { runCatching { context.startActivity(it) }.isSuccess } ?: false
    }

private fun startGuided(context: Context, request: LaunchRequest) {
    val guide = request.guide ?: run {
        startPlain(context, request)
        return
    }
    // Hidden developer options: Wireless debugging is not reachable yet, so Tic starts at About phone and
    // unhides them first. Debugging blocked by policy can't be unhidden; the plain screen explains itself.
    val start = if (guide == GuideRequest.WirelessDebugging && !developerOptionsShown(context) && !debuggingBlocked(context)) {
        LaunchRequest(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS), Intent(Settings.ACTION_SETTINGS), guide)
    } else {
        request
    }
    GuideSession.start(guide)
    if (!startPlain(context, freshTask(start))) GuideSession.finish()
}

/**
 * Settings in a task of its own, cleared first. On large screens its deep links land in whatever Settings task
 * is already open (often the accessibility page left from turning Tic on), and Tic would start there.
 */
private fun freshTask(request: LaunchRequest): LaunchRequest {
    val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    return request.copy(intent = Intent(request.intent).addFlags(flags), fallback = request.fallback?.let { Intent(it).addFlags(flags) })
}

internal fun developerOptionsShown(context: Context): Boolean =
    Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0

internal fun debuggingBlocked(context: Context): Boolean =
    context.getSystemService(UserManager::class.java)?.hasUserRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES) == true

/**
 * The accessibility list, scrolled to our row where Settings honours the highlight extras (its own
 * details page needs a permission only the system holds).
 */
private fun openAccessibilitySettings(context: Context): Boolean {
    val key = ComponentName(context, GuideAccessibilityService::class.java).flattenToString()
    // Its own task: once the service is on, Tic moves ours to the front, which must show LibreWrist, not this list.
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra(EXTRA_FRAGMENT_ARG_KEY, key)
        .putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, key) })
    return runCatching { context.startActivity(intent) }.isSuccess
}

private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
private const val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"

/** App info, where Android 13+ hides "Allow restricted settings" behind the ⋮ menu for sideloaded apps. */
private fun openAppInfo(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    }
}

@Composable
private fun EnableGuideDialog(onEnable: () -> Unit, onNotNow: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onNotNow,
        icon = { Icon(Icons.Rounded.Accessibility, contentDescription = null) },
        title = { Text(stringResource(R.string.guide_enable_title), textAlign = TextAlign.Center) },
        text = {
            Text(
                stringResource(R.string.guide_enable_message) + "\n\n" + stringResource(R.string.guide_enable_restricted),
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        confirmButton = { TextButton(onClick = onEnable) { Text(stringResource(R.string.guide_enable_open)) } },
        dismissButton = {
            TextButton(onClick = { openAppInfo(context) }) { Text(stringResource(R.string.guide_enable_app_info)) }
            TextButton(onClick = onNotNow) { Text(stringResource(R.string.guide_enable_not_now)) }
        },
    )
}
