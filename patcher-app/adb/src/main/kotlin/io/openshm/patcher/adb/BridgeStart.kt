package io.openshm.patcher.adb

/**
 * The "start once" request sent to a bridge after it is installed and granted. A freshly installed
 * app is in the stopped state and receives no BOOT_COMPLETED until something starts it; an explicit
 * broadcast with FLAG_INCLUDE_STOPPED_PACKAGES clears that state, and the receiver starts the
 * bridge's service on it. Which receiver and action an app uses comes from the catalogue (`start`).
 */
object BridgeStart {
    /** `Intent.FLAG_INCLUDE_STOPPED_PACKAGES`. */
    const val INCLUDE_STOPPED = 0x20

    private val EXTRA_NAME = Regex("[a-z][A-Za-z0-9]*")

    private val RECEIVER = Regex("\\.?[A-Za-z_][A-Za-z0-9_$]*(\\.[A-Za-z_][A-Za-z0-9_$]*)*")

    /** [action] when it is a dotted identifier (the catalogue action with the prefix filled in); throws otherwise. */
    fun requireAction(action: String): String = Names.requirePackage(action)

    /**
     * The `adb shell` command that starts the bridge [pkg] on the watch: broadcasts [action] to
     * [receiver] (a class name, relative to [pkg] when it starts with a dot), with a boolean extra per
     * entry of [extras] (`--ez name value`). Values are validated, never quoted.
     */
    fun watchCommand(pkg: String, receiver: String, action: String, extras: Map<String, Boolean> = emptyMap()): String {
        require(RECEIVER.matches(receiver)) { "Not a valid receiver name: ${receiver.take(80)}" }
        val ez = extras.entries.joinToString("") { (name, value) ->
            require(EXTRA_NAME.matches(name)) { "Not a valid extra name: ${name.take(80)}" }
            " --ez $name $value"
        }
        return "am broadcast -a ${requireAction(action)} -n ${Names.requirePackage(pkg)}/$receiver -f $INCLUDE_STOPPED$ez"
    }
}
