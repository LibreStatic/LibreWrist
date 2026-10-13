package io.openshm.patcher.adb

/** What a read-back says about one grant. */
enum class GrantState {
    /** The device reports the grant as applied. */
    Granted,

    /** The device reports it as not applied. */
    Missing,

    /** The device did not answer in a way that can be read (command failed, op with no explicit mode). */
    Unknown,
}

/** The read-back of one [grant]; [detail] is a short technical note (the value found), never a secret. */
data class GrantCheck(val grant: Grant, val state: GrantState, val detail: String? = null)

/** Parsers for the output of the read-back commands. Pure functions, tested without a device. */
object GrantReadback {
    private val permissionLine = Regex("""^\s*([A-Za-z][A-Za-z0-9_.]*): granted=(true|false)\b""")

    /**
     * Permission name to granted flag from `dumpsys package <pkg>` (the `install permissions:` and
     * `runtime permissions:` lines, `android.permission.X: granted=true, flags=[...]`). A permission
     * listed more than once (one entry per user) counts as granted when any entry is.
     */
    fun permissions(dumpsys: String): Map<String, Boolean> {
        val out = LinkedHashMap<String, Boolean>()
        for (line in dumpsys.lineSequence()) {
            val m = permissionLine.find(line) ?: continue
            val name = m.groupValues[1]
            out[name] = (out[name] ?: false) || m.groupValues[2] == "true"
        }
        return out
    }

    /**
     * The mode of [op] from `appops get <pkg> <OP>` (`SCHEDULE_EXACT_ALARM: allow`, optionally
     * followed by `; time=+1d2h ago`). A `Uid mode:` line wins over the package line. Null when the
     * op has no explicit mode (`No operations.`) or the output has no line for it.
     */
    fun appOpMode(output: String, op: String): String? {
        var uidMode: String? = null
        var packageMode: String? = null
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            val isUid = line.startsWith(UID_MODE)
            val body = if (isUid) line.removePrefix(UID_MODE).trim() else line
            if (!body.startsWith("$op:")) continue
            val mode = body.removePrefix("$op:").trim().substringBefore(';').trim().substringBefore(' ').lowercase()
            if (mode.isEmpty()) continue
            if (isUid) uidMode = mode else packageMode = mode
        }
        return uidMode ?: packageMode
    }

    private const val UID_MODE = "Uid mode:"

    /** The value printed by `settings get <namespace> <key>`; null when unset (`null`) or empty. */
    fun settingValue(output: String): String? =
        output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.takeIf { it != "null" }

    /**
     * True when [value] is an exact comma-separated entry of [setting] (the value of a
     * list-valued setting, or null when unset). A longer entry that merely contains [value]
     * as a prefix or substring does not count.
     */
    fun listContainsEntry(setting: String?, value: String): Boolean {
        if (setting.isNullOrBlank() || setting == "null") return false
        return setting.split(',').any { it.trim() == value }
    }

    /**
     * [setting] with [value] appended as a comma-separated entry: just [value] when the
     * setting is unset (`null`, empty or the literal `null`), [setting] unchanged when the
     * entry is already present, and foreign entries preserved verbatim otherwise.
     */
    fun appendListEntry(setting: String?, value: String): String {
        if (setting.isNullOrBlank() || setting == "null") return value
        if (listContainsEntry(setting, value)) return setting
        return "$setting,$value"
    }

    /**
     * [setting] with every exact (trimmed) occurrence of [value] removed, the other entries kept
     * verbatim and in order; null when nothing remains (unset, blank, the literal `null`, or only [value]).
     */
    fun removeListEntry(setting: String?, value: String): String? {
        if (setting.isNullOrBlank() || setting == "null") return null
        val kept = setting.split(',').filter { it.trim() != value }
        return kept.joinToString(",").takeIf { it.isNotBlank() }
    }

    /**
     * True when [component] of [pkg] is in the `enabled_notification_listeners` setting
     * (`pkg/pkg.Class:pkg2/.Class2`). [component] may be written `.Class` (relative to the package)
     * or as a full class name; the system stores either form.
     */
    fun listenerEnabled(setting: String?, pkg: String, component: String): Boolean {
        val full = if (component.startsWith(".")) pkg + component else component
        val short = if (full.startsWith("$pkg.")) full.removePrefix(pkg) else full
        val accepted = setOf("$pkg/$full", "$pkg/$short")
        return setting.orEmpty().split(':').any { it.trim() in accepted }
    }

    /**
     * True when [pkg] is on the Doze allow-list in `dumpsys deviceidle whitelist` output (lines like
     * `user,com.example.app,10190` or `system,com.example.app,10102`).
     */
    fun deviceIdleWhitelisted(dump: String, pkg: String): Boolean =
        dump.lineSequence().any { line ->
            val parts = line.trim().split(',')
            parts.size >= 2 && parts[1].trim() == pkg
        }
}

/**
 * Reads back the grants of [GrantApplier] from the device: the same catalogue grants, the opposite
 * direction. [shell] runs one `adb shell` command line. Commands are built from values that passed
 * the same character-set checks as the apply commands; the dumps are fetched at most once per call.
 */
class GrantChecker(private val shell: suspend (String) -> ShellResult) {
    constructor(client: AdbClient) : this({ command -> client.shell(command) })

    suspend fun check(pkg: String, grants: List<Grant>): List<GrantCheck> {
        Names.requirePackage(pkg)
        var packageDump: ShellResult? = null
        var whitelist: ShellResult? = null
        var listeners: ShellResult? = null
        suspend fun packageDump() = packageDump ?: shell("dumpsys package $pkg").also { packageDump = it }
        suspend fun whitelist() = whitelist ?: shell("dumpsys deviceidle whitelist").also { whitelist = it }
        suspend fun listeners() =
            listeners ?: shell("settings get secure enabled_notification_listeners").also { listeners = it }

        return grants.map { grant ->
            when (grant) {
                is Grant.Permission -> {
                    require(GrantCommandBuilder.PERMISSION.matches(grant.name)) { "Unsafe permission" }
                    val result = packageDump()
                    if (!result.isSuccess) {
                        GrantCheck(grant, GrantState.Unknown, "dumpsys failed")
                    } else {
                        val granted = GrantReadback.permissions(result.stdout)[grant.name]
                        GrantCheck(grant, if (granted == true) GrantState.Granted else GrantState.Missing, granted?.let { "granted=$it" })
                    }
                }
                is Grant.AppOp -> {
                    require(GrantCommandBuilder.APPOP.matches(grant.op)) { "Unsafe appop" }
                    val result = shell("appops get $pkg ${grant.op}")
                    if (!result.isSuccess) {
                        GrantCheck(grant, GrantState.Unknown, "appops failed")
                    } else {
                        val mode = GrantReadback.appOpMode(result.stdout, grant.op)
                        val state = when (mode) {
                            null, "default" -> GrantState.Unknown
                            "allow" -> GrantState.Granted
                            else -> GrantState.Missing
                        }
                        GrantCheck(grant, state, mode)
                    }
                }
                is Grant.SecureSetting -> {
                    require(GrantCommandBuilder.SETTING_KEY.matches(grant.key)) { "Unsafe setting key" }
                    val result = shell("settings get secure ${grant.key}")
                    if (!result.isSuccess) {
                        GrantCheck(grant, GrantState.Unknown, "settings failed")
                    } else {
                        val value = GrantReadback.settingValue(result.stdout)
                        GrantCheck(grant, if (value == grant.value) GrantState.Granted else GrantState.Missing, value ?: "unset")
                    }
                }
                is Grant.GlobalSetting -> {
                    require(GrantCommandBuilder.SETTING_KEY.matches(grant.key)) { "Unsafe setting key" }
                    require(GrantCommandBuilder.GLOBAL_SETTING_VALUE.matches(grant.value)) { "Unsafe global setting value" }
                    val result = shell("settings get global ${grant.key}")
                    if (!result.isSuccess) {
                        GrantCheck(grant, GrantState.Unknown, "settings failed")
                    } else {
                        val value = GrantReadback.settingValue(result.stdout)
                        val granted = GrantReadback.listContainsEntry(value, grant.value)
                        GrantCheck(grant, if (granted) GrantState.Granted else GrantState.Missing, value ?: "unset")
                    }
                }
                is Grant.NotificationListener -> {
                    require(GrantCommandBuilder.COMPONENT.matches(grant.component)) { "Unsafe listener component" }
                    val result = listeners()
                    if (!result.isSuccess) {
                        GrantCheck(grant, GrantState.Unknown, "settings failed")
                    } else {
                        val on = GrantReadback.listenerEnabled(GrantReadback.settingValue(result.stdout), pkg, grant.component)
                        GrantCheck(grant, if (on) GrantState.Granted else GrantState.Missing)
                    }
                }
                is Grant.DeviceIdleWhitelist -> {
                    val result = whitelist()
                    if (!result.isSuccess) {
                        GrantCheck(grant, GrantState.Unknown, "deviceidle failed")
                    } else {
                        val on = GrantReadback.deviceIdleWhitelisted(result.stdout, pkg)
                        GrantCheck(grant, if (on) GrantState.Granted else GrantState.Missing)
                    }
                }
            }
        }
    }
}
