package io.openshm.patcher.adb

/**
 * The "start once" request sent to a bridge after it is installed and granted. A freshly installed
 * app is in the stopped state and receives no BOOT_COMPLETED until something starts it; an explicit
 * broadcast with FLAG_INCLUDE_STOPPED_PACKAGES clears that state, and the receivers start the
 * bridge's service on it.
 */
object BridgeStart {
    /** Catalogue output ids of the two bridges. */
    const val WATCH_OUTPUT = "bridge-watch"
    const val PHONE_OUTPUT = "bridge-phone"

    /** Receivers of the START action, relative to the bridge package. */
    const val WATCH_RECEIVER = ".BridgeAlarmReceiver"
    const val PHONE_RECEIVER = ".CommsBootReceiver"

    /** `Intent.FLAG_INCLUDE_STOPPED_PACKAGES`. */
    const val INCLUDE_STOPPED = 0x20

    fun isBridge(outputId: String) = outputId == WATCH_OUTPUT || outputId == PHONE_OUTPUT

    /** The START action under the user's package prefix (the bridges' `shminterop` placeholder). */
    fun action(prefix: String): String = "${Names.requirePackage(prefix)}.shmbridge.START"

    /** The `adb shell` command that starts the watch bridge [pkg]; values are validated, never quoted. */
    fun watchCommand(pkg: String, prefix: String): String =
        "am broadcast -a ${action(prefix)} -n ${Names.requirePackage(pkg)}/$WATCH_RECEIVER -f $INCLUDE_STOPPED"
}
