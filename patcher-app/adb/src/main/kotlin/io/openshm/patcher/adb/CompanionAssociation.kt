package io.openshm.patcher.adb

/** One Companion Device Manager association on the watch: [packageName] is linked to the device at [mac] (uppercase). */
data class CompanionAssociation(val packageName: String, val mac: String)

/**
 * The `adb shell` commands and parsers that link the watch bridge to the phone through the watch's
 * Companion Device Manager. Pure functions; every value is validated and never quoted.
 */
object CompanionCommands {
    /** Lists the associations of user 0. Older Android versions print `<package> <mac>` lines, newer ones a table. */
    const val LIST_COMMAND = "cmd companiondevice list 0"

    private val MAC = Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")
    private val PHONE_MAC = Regex("phone_mac=([^,\\s]*)")
    private val SEPARATORS = Regex("[\\s|]+")
    private val UNUSABLE = setOf("00:00:00:00:00:00", "02:00:00:00:00:00")

    /** The upper-case form of [value] when it is a usable Bluetooth MAC, else null. */
    fun normalizeMac(value: String): String? {
        if (!MAC.matches(value)) return null
        val mac = value.uppercase()
        return mac.takeIf { it !in UNUSABLE }
    }

    private fun requireMac(value: String): String =
        requireNotNull(normalizeMac(value)) { "Not a valid MAC address: ${value.take(40)}" }

    /** Reads the bridge's shell-readable link provider. */
    fun linkInfoCommand(pkg: String): String = "content query --uri content://${Names.requirePackage(pkg)}.link"

    /** The phone's MAC from the provider output, or null when it is empty, unusable or the provider is missing. */
    fun parsePhoneMac(stdout: String): String? {
        for (line in stdout.lineSequence()) {
            if (!line.trimStart().startsWith("Row:")) continue
            val value = PHONE_MAC.find(line)?.groupValues?.get(1) ?: continue
            return normalizeMac(value)
        }
        return null
    }

    /** Per line, the first token that is a MAC and the first that is a package name; other lines (headers, "Max ID") are ignored. */
    fun parseAssociations(stdout: String): List<CompanionAssociation> = stdout.lineSequence().mapNotNull { line ->
        val tokens = line.trim().split(SEPARATORS).filter { it.isNotEmpty() }
        val mac = tokens.firstNotNullOfOrNull { normalizeMac(it) } ?: return@mapNotNull null
        val pkg = tokens.firstOrNull { Names.isPackage(it) } ?: return@mapNotNull null
        CompanionAssociation(pkg, mac)
    }.toList()

    fun associateCommand(pkg: String, mac: String): String =
        "cmd companiondevice associate 0 ${Names.requirePackage(pkg)} ${requireMac(mac)}"

    fun disassociateCommand(pkg: String, mac: String): String =
        "cmd companiondevice disassociate 0 ${Names.requirePackage(pkg)} ${requireMac(mac)}"
}
