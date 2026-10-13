package io.openshm.patcher.ui.screens.welcome

import androidx.annotation.StringRes
import io.openshm.patcher.R
import io.openshm.patcher.data.Grant
import io.openshm.patcher.data.GrantKind

/*
 * Plain-language text for the "what will change" sheet: what each installed app is for, and what
 * each grant lets it do. Keyed by catalogue output id and grant name; a grant the app has no text
 * for still shows, under its kind and technical name, so a newer catalogue never hides anything.
 */

/** What an installed app is for, by catalogue output id. */
@StringRes
internal fun outputPurpose(outputId: String): Int? = when (outputId) {
    "shm-phone" -> R.string.change_purpose_shm_phone
    "shm-watch" -> R.string.change_purpose_shm_watch
    "bridge-phone" -> R.string.change_purpose_bridge_phone
    "bridge-watch" -> R.string.change_purpose_bridge_watch
    "alarm-fork" -> R.string.change_purpose_alarm_fork
    "shealth-phone" -> R.string.change_purpose_shealth_phone
    else -> null
}

/** Short name of a grant, close to the name Android Settings uses for it. */
@StringRes
internal fun grantTitle(grant: Grant): Int = when (grant.name) {
    "ANSWER_PHONE_CALLS" -> R.string.grant_title_calls
    "READ_PHONE_STATE" -> R.string.grant_title_phone_state
    "POST_NOTIFICATIONS" -> R.string.grant_title_notifications
    "BLUETOOTH_CONNECT" -> R.string.grant_title_bluetooth
    "ACCESS_RESTRICTED_SETTINGS" -> R.string.grant_title_restricted
    "SYSTEM_ALERT_WINDOW" -> R.string.grant_title_overlay
    "SCHEDULE_EXACT_ALARM" -> R.string.grant_title_exact_alarm
    "USE_FULL_SCREEN_INTENT" -> R.string.grant_title_full_screen
    "MANAGE_ONGOING_CALLS" -> R.string.grant_title_ongoing_calls
    "doze_always_on" -> R.string.grant_title_always_on
    else -> when (grant.kind) {
        GrantKind.NotificationListener -> R.string.grant_title_listener
        GrantKind.BatteryExemption -> R.string.grant_title_battery
        GrantKind.Permission -> R.string.grant_permission
        GrantKind.AppOp -> R.string.grant_appop
        GrantKind.SecureSetting -> R.string.grant_setting
    }
}

/** Why [outputId] needs [grant], or null when the app has no text for it. */
@StringRes
internal fun grantReason(outputId: String, grant: Grant): Int? {
    val watch = outputId != "bridge-phone" && outputId != "shm-phone" && outputId != "shealth-phone"
    return when (grant.kind) {
        GrantKind.NotificationListener -> if (watch) R.string.grant_why_listener_watch else R.string.grant_why_listener_phone
        GrantKind.BatteryExemption -> if (outputId == "alarm-fork") R.string.grant_why_battery_alarm else R.string.grant_why_battery_bridge
        else -> when (grant.name) {
            "ANSWER_PHONE_CALLS" -> R.string.grant_why_calls
            "READ_PHONE_STATE" -> R.string.grant_why_phone_state
            "POST_NOTIFICATIONS" -> when (outputId) {
                "bridge-phone" -> R.string.grant_why_notifications_phone
                "alarm-fork" -> R.string.grant_why_notifications_alarm
                else -> R.string.grant_why_notifications_watch
            }
            "BLUETOOTH_CONNECT" -> if (watch) R.string.grant_why_bluetooth_watch else R.string.grant_why_bluetooth_phone
            "ACCESS_RESTRICTED_SETTINGS" -> R.string.grant_why_restricted
            "SYSTEM_ALERT_WINDOW" -> if (watch) R.string.grant_why_overlay_watch else R.string.grant_why_overlay_phone
            "SCHEDULE_EXACT_ALARM" -> if (outputId == "shm-watch") R.string.grant_why_exact_alarm_shm else R.string.grant_why_exact_alarm_bridge
            "USE_FULL_SCREEN_INTENT" -> if (outputId == "alarm-fork") R.string.grant_why_full_screen_alarm else R.string.grant_why_full_screen_bridge
            "MANAGE_ONGOING_CALLS" -> R.string.grant_why_ongoing_calls
            "doze_always_on" -> R.string.grant_why_always_on
            else -> null
        }
    }
}

/** The name as an engineer would look it up: the permission/appop/setting, or the listener component. */
internal fun grantTechnicalName(grant: Grant): String = when (grant.kind) {
    GrantKind.BatteryExemption -> "deviceidle whitelist"
    GrantKind.SecureSetting -> "settings secure ${grant.name}"
    else -> grant.name
}
