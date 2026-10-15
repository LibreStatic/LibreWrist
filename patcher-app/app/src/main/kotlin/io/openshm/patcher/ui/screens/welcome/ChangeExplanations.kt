package io.openshm.patcher.ui.screens.welcome

import androidx.annotation.StringRes
import io.openshm.patcher.R
import io.openshm.patcher.data.Grant
import io.openshm.patcher.data.GrantKind

/*
 * Names for the "what will change" sheet. What an app is for and why a grant is needed come from the
 * catalogue (`OutputApp.purpose`, `Grant.why`); a grant without a reason still shows, under its kind and
 * technical name, so a newer catalogue never hides anything. Android permission titles are platform
 * facts and live here.
 */

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
        GrantKind.GlobalSetting -> R.string.grant_title_hidden_api
    }
}

/** The name as an engineer would look it up: the permission/appop/setting, or the listener component. */
internal fun grantTechnicalName(grant: Grant): String = when (grant.kind) {
    GrantKind.BatteryExemption -> "deviceidle whitelist"
    GrantKind.SecureSetting -> "settings secure ${grant.name}"
    GrantKind.GlobalSetting -> "settings global ${grant.name}"
    else -> grant.name
}
