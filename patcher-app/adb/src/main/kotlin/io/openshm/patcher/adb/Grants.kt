package io.openshm.patcher.adb

/**
 * Minimal model of the catalogue's `grants` entries. The engine module owns the full catalogue
 * model; the app maps its entries to these. Values are validated when a command is built.
 */
sealed class Grant {
    abstract val optional: Boolean

    data class Permission(val name: String, override val optional: Boolean = false) : Grant()
    data class AppOp(val op: String, val mode: String = "allow", override val optional: Boolean = false) : Grant()
    data class NotificationListener(val component: String, override val optional: Boolean = false) : Grant()
    data class DeviceIdleWhitelist(override val optional: Boolean = false) : Grant()
    data class SecureSetting(val key: String, val value: String, override val optional: Boolean = false) : Grant()
}

/** Which device the commands are for; the phone needs an explicit user argument in places. */
enum class GrantTarget { WATCH, PHONE }

/** Thrown for a value outside the catalogue's allowed character sets. */
class UnsafeGrantValueException(message: String) : IllegalArgumentException(message)

/**
 * Turns grants into `adb shell` command lines. Every value must match the same character sets the
 * catalogue validator enforces; anything else is rejected rather than escaped, so no quoting is
 * ever needed.
 */
class GrantCommandBuilder(private val target: GrantTarget) {

    fun build(pkg: String, grant: Grant): String {
        check(Names.isPackage(pkg), "package name", pkg)
        return when (grant) {
            is Grant.Permission -> {
                check(PERMISSION.matches(grant.name), "permission", grant.name)
                "pm grant $pkg ${grant.name}"
            }
            is Grant.AppOp -> {
                check(APPOP.matches(grant.op), "appop", grant.op)
                check(grant.mode == "allow", "appop mode", grant.mode)
                "appops set $pkg ${grant.op} allow"
            }
            is Grant.NotificationListener -> {
                check(COMPONENT.matches(grant.component), "listener component", grant.component)
                val base = "cmd notification allow_listener $pkg/${grant.component}"
                if (target == GrantTarget.PHONE) "$base 0" else base
            }
            is Grant.DeviceIdleWhitelist -> "cmd deviceidle whitelist +$pkg"
            is Grant.SecureSetting -> {
                check(SETTING_KEY.matches(grant.key), "setting key", grant.key)
                check(SETTING_VALUE.matches(grant.value), "setting value", grant.value)
                "settings put secure ${grant.key} ${grant.value}"
            }
        }
    }

    private fun check(ok: Boolean, what: String, value: String) {
        // The offending value is truncated and never trusted; it is only for the error message.
        if (!ok) throw UnsafeGrantValueException("Unsafe $what: ${value.take(60)}")
    }

    internal companion object {
        val PERMISSION = Regex("[A-Za-z][A-Za-z0-9_.]*")
        val APPOP = Regex("[A-Z][A-Z0-9_]*")
        val COMPONENT = Regex("(\\.[A-Za-z][A-Za-z0-9_]*)+|[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        val SETTING_KEY = Regex("[a-z][a-z0-9_]*")
        val SETTING_VALUE = Regex("[A-Za-z0-9_.][A-Za-z0-9_.-]{0,63}") // no leading "-": it would be read as an option
    }
}

/** What happened to one grant. */
data class GrantOutcome(val grant: Grant, val command: String, val result: ShellResult) {
    val applied: Boolean get() = result.isSuccess
}

/** Outcomes in order. Processing stops at the first failed required grant. */
data class GrantReport(val outcomes: List<GrantOutcome>) {
    val failedRequired: GrantOutcome? get() = outcomes.firstOrNull { !it.applied && !it.grant.optional }
    val failedOptional: List<GrantOutcome> get() = outcomes.filter { !it.applied && it.grant.optional }
    val ok: Boolean get() = failedRequired == null
}

/** Applies grants over an [AdbClient]. */
class GrantApplier(private val client: AdbClient, private val target: GrantTarget) {
    private val builder = GrantCommandBuilder(target)

    /**
     * Applies [grants] to [pkg]. All commands are built (and so validated) before any runs. An
     * optional grant that fails is recorded and skipped; a failed required grant stops the run.
     */
    suspend fun apply(pkg: String, grants: List<Grant>): GrantReport {
        val commands = grants.map { it to builder.build(pkg, it) }
        val outcomes = ArrayList<GrantOutcome>(commands.size)
        for ((grant, command) in commands) {
            val outcome = GrantOutcome(grant, command, client.shell(command))
            outcomes += outcome
            if (!outcome.applied && !grant.optional) break
        }
        return GrantReport(outcomes)
    }
}
