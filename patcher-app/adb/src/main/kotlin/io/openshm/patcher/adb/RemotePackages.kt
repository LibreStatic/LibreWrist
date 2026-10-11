package io.openshm.patcher.adb

/** Version of an installed package as reported by `dumpsys package`. */
data class PackageVersion(val versionCode: Long, val versionName: String?)

/** Parsers for `pm` and `dumpsys` output. Pure functions, tested without a device. */
object PmParsers {
    /**
     * Parses `pm list packages [-f]` output. Accepts `package:name` and `package:/path.apk=name`.
     */
    fun parsePackageList(output: String): List<String> = output.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("package:") }
        .map { line ->
            val body = line.removePrefix("package:").substringBefore(' ')
            if (body.startsWith("/")) body.substringAfterLast('=') else body
        }
        .filter { Names.isPackage(it) }
        .toList()

    /** Parses `pm path <pkg>`: every `package:<path>` line, base APK first as printed. */
    fun parsePaths(output: String): List<String> = output.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("package:") }
        .map { it.removePrefix("package:") }
        .filter { it.isNotEmpty() }
        .toList()

    /** True when `pm uninstall` printed `Success` and exited cleanly. */
    fun isSuccess(result: ShellResult): Boolean =
        result.isSuccess && result.stdout.lineSequence().any { it.trim() == "Success" }

    private val versionCode = Regex("""\bversionCode=(\d+)""")
    private val versionName = Regex("""\bversionName=(\S+)""")

    /** First `versionCode`/`versionName` in `dumpsys package <pkg>`, or null if not installed. */
    fun parseVersion(dumpsys: String): PackageVersion? {
        val code = versionCode.find(dumpsys)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val name = versionName.find(dumpsys)?.groupValues?.get(1)?.takeIf { it != "null" }
        return PackageVersion(code, name)
    }
}

object Names {
    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    fun isPackage(value: String) = PACKAGE.matches(value)

    fun requirePackage(value: String): String {
        require(isPackage(value)) { "Not a valid package name: ${value.take(80)}" }
        return value
    }
}

/** Package queries and enable/disable over an [AdbClient]. */
class RemotePackages(private val client: AdbClient) {

    /** Installed package names. [thirdPartyOnly] maps to `-3`; [userId] to `--user`. */
    suspend fun listPackages(thirdPartyOnly: Boolean = false, userId: Int? = null): List<String> {
        val command = buildString {
            append("pm list packages")
            if (thirdPartyOnly) append(" -3")
            if (userId != null) append(" --user ").append(requireUser(userId))
        }
        return PmParsers.parsePackageList(client.shell(command).requireSuccess(command).stdout)
    }

    suspend fun isInstalled(pkg: String): Boolean = pathsOf(pkg).isNotEmpty()

    /** Base APK and split APK paths, empty if the package is not installed. */
    suspend fun pathsOf(pkg: String): List<String> {
        val command = "pm path ${Names.requirePackage(pkg)}"
        val result = client.shell(command)
        return if (result.isSuccess) PmParsers.parsePaths(result.stdout) else emptyList()
    }

    /** Version of [pkg], or null if it is not installed. */
    suspend fun versionOf(pkg: String): PackageVersion? {
        val result = client.shell("dumpsys package ${Names.requirePackage(pkg)}")
        return if (result.isSuccess) PmParsers.parseVersion(result.stdout) else null
    }

    /** True when [pkg] is a system package (`pm list packages -s` lists updated system apps too). */
    suspend fun isSystem(pkg: String): Boolean {
        val command = "pm list packages -s"
        return Names.requirePackage(pkg) in PmParsers.parsePackageList(client.shell(command).requireSuccess(command).stdout)
    }

    /**
     * `pm uninstall --user N pkg`: removes the package and its data (no `-k`). The result is
     * returned as is; see [PmParsers.isSuccess].
     */
    suspend fun uninstall(pkg: String, userId: Int = 0): ShellResult =
        client.shell("pm uninstall --user ${requireUser(userId)} ${Names.requirePackage(pkg)}")

    /** `pm disable-user --user N pkg`; reversible with [enable]. */
    suspend fun disableUser(pkg: String, userId: Int = 0): ShellResult {
        val command = "pm disable-user --user ${requireUser(userId)} ${Names.requirePackage(pkg)}"
        return client.shell(command).requireSuccess(command)
    }

    suspend fun enable(pkg: String, userId: Int = 0): ShellResult {
        val command = "pm enable --user ${requireUser(userId)} ${Names.requirePackage(pkg)}"
        return client.shell(command).requireSuccess(command)
    }

    private fun requireUser(userId: Int): Int {
        require(userId in 0..9999) { "Invalid user id" }
        return userId
    }
}
