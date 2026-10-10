package io.openshm.patcher.device

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import io.openshm.patcher.data.Grant
import io.openshm.patcher.data.GrantKind
import io.openshm.patcher.ui.components.CheckStatus
import io.openshm.patcher.ui.screens.install.GrantRow

/**
 * What can be read about another app's access on this phone. Android-free so the decisions in
 * [PhoneGrants] run in JVM tests; [AndroidPhoneProbe] is the real one. `null` means "cannot tell".
 */
interface PhoneProbe {
    fun permissionGranted(pkg: String, permission: String): Boolean
    fun appOpAllowed(pkg: String, op: String): Boolean?
    fun listenerEnabled(pkg: String, component: String): Boolean
}

/** Which system screen lets the user change a grant. */
enum class SettingsTarget { AppDetails, Overlay, ListenerDetail }

/**
 * The phone grants the app cannot apply by itself. Each row is checked against the system when
 * the screen comes back to the front; the user (or the Wireless debugging fallback) changes them.
 */
class PhoneGrants(private val probe: PhoneProbe) {

    /** [rows] with their status re-read from the system. Rows being applied are left alone. */
    fun refresh(pkg: String, rows: List<GrantRow>): List<GrantRow> {
        val listeners = rows.filter { it.grant.kind == GrantKind.NotificationListener }
        val listenerOn = listeners.isNotEmpty() && listeners.all { probe.listenerEnabled(pkg, componentOf(pkg, it.grant)) }
        return rows.map { row ->
            if (row.status == CheckStatus.Running) row else row.copy(status = statusOf(pkg, row, listenerOn))
        }
    }

    private fun statusOf(pkg: String, row: GrantRow, listenerOn: Boolean): CheckStatus {
        val g = row.grant
        val missing = if (g.optional) CheckStatus.Warning else CheckStatus.Pending
        return when (g.kind) {
            GrantKind.Permission ->
                if (probe.permissionGranted(pkg, g.engine?.fields?.get("name") ?: g.name)) CheckStatus.Passed else missing
            GrantKind.NotificationListener -> if (listenerOn) CheckStatus.Passed else missing
            GrantKind.AppOp -> {
                val op = g.engine?.fields?.get("op") ?: g.name
                when {
                    // Restricted settings only matter while the listener switch is greyed out.
                    op == RESTRICTED_SETTINGS && listenerOn -> CheckStatus.Passed
                    probe.appOpAllowed(pkg, op) == true -> CheckStatus.Passed
                    probe.appOpAllowed(pkg, op) == null && row.status == CheckStatus.Passed -> CheckStatus.Passed
                    else -> missing
                }
            }
            // Not readable from here: whatever was last applied stands.
            GrantKind.SecureSetting, GrantKind.BatteryExemption -> row.status
        }
    }

    companion object {
        const val RESTRICTED_SETTINGS = "ACCESS_RESTRICTED_SETTINGS"
        const val OVERLAY = "SYSTEM_ALERT_WINDOW"

        fun targetFor(grant: Grant): SettingsTarget = when {
            grant.kind == GrantKind.NotificationListener -> SettingsTarget.ListenerDetail
            grant.kind == GrantKind.AppOp && (grant.engine?.fields?.get("op") ?: grant.name) == OVERLAY -> SettingsTarget.Overlay
            else -> SettingsTarget.AppDetails
        }

        /** The listener class of [grant] as a full class name (the catalogue may write `.Name` relative to the package). */
        fun componentOf(pkg: String, grant: Grant): String {
            val c = grant.engine?.fields?.get("component") ?: grant.name
            return if (c.startsWith(".")) pkg + c else c
        }
    }
}

/** The flattened `pkg/class` forms the system stores in `enabled_notification_listeners`. */
internal fun listenerEntries(pkg: String, cls: String): Set<String> =
    setOf("$pkg/$cls", "$pkg/${cls.removePrefix(pkg)}")

internal fun isListenerEnabled(enabledSetting: String?, pkg: String, cls: String): Boolean {
    val entries = listenerEntries(pkg, cls)
    return enabledSetting.orEmpty().split(':').any { it in entries }
}

class AndroidPhoneProbe(context: Context) : PhoneProbe {
    private val context = context.applicationContext
    private val pm: PackageManager get() = context.packageManager

    override fun permissionGranted(pkg: String, permission: String): Boolean =
        pm.checkPermission(permission, pkg) == PackageManager.PERMISSION_GRANTED

    override fun appOpAllowed(pkg: String, op: String): Boolean? {
        if (op == PhoneGrants.RESTRICTED_SETTINGS && android.os.Build.VERSION.SDK_INT < 33) return true // does not exist before Android 13
        return try {
            @Suppress("DEPRECATION")
            val uid = pm.getPackageUid(pkg, 0)
            val appOps = context.getSystemService(AppOpsManager::class.java)
            when (appOps.unsafeCheckOpNoThrow(opString(op), uid, pkg)) {
                AppOpsManager.MODE_ALLOWED -> true
                // Overlay access defaults to the manifest permission until the user changes it.
                AppOpsManager.MODE_DEFAULT ->
                    if (op == PhoneGrants.OVERLAY) permissionGranted(pkg, "android.permission.SYSTEM_ALERT_WINDOW") else false
                else -> false
            }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        } catch (e: IllegalArgumentException) {
            null // an op this Android version does not know
        } catch (e: SecurityException) {
            null
        }
    }

    override fun listenerEnabled(pkg: String, component: String): Boolean =
        isListenerEnabled(Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners"), pkg, component)

    private fun opString(op: String) = "android:" + op.lowercase()
}

/** The system screens behind [SettingsTarget]; [fallback] is tried when the first one is not available. */
data class LaunchRequest(val intent: Intent, val fallback: Intent? = null)

object PhoneSettings {
    /**
     * Developer options scrolled to (and on most builds highlighting) the Wireless debugging entry,
     * the same deep link Shizuku uses; plain Developer options where the extras are ignored.
     */
    fun wirelessDebugging(): LaunchRequest {
        val target = Bundle().apply { putString(FRAGMENT_ARGS_KEY, WIRELESS_DEBUGGING_KEY) }
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .putExtra(FRAGMENT_ARGS_KEY, WIRELESS_DEBUGGING_KEY)
            .putExtra(SHOW_FRAGMENT_ARGS, target)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return LaunchRequest(intent, Intent(Settings.ACTION_SETTINGS))
    }

    private const val FRAGMENT_ARGS_KEY = ":settings:fragment_args_key"
    private const val SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"
    private const val WIRELESS_DEBUGGING_KEY = "toggle_adb_wireless"

    fun intentFor(target: SettingsTarget, pkg: String, listenerClass: String?): LaunchRequest {
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        return when (target) {
            SettingsTarget.AppDetails -> LaunchRequest(details)
            SettingsTarget.Overlay ->
                LaunchRequest(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$pkg")), details)
            SettingsTarget.ListenerDetail -> LaunchRequest(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, "$pkg/${listenerClass ?: ""}"),
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
            )
        }
    }

    fun unknownSources(context: Context): LaunchRequest =
        LaunchRequest(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
}
