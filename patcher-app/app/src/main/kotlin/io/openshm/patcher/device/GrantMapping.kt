package io.openshm.patcher.device

import io.openshm.patcher.adb.Grant as AdbGrant
import io.openshm.patcher.data.Grant

/** A catalogue grant the device layer cannot express (unknown kind or a missing field). */
class UnsupportedGrantException(message: String) : IllegalArgumentException(message)

/** Maps a catalogue grant to the ADB layer's grant. */
fun Grant.toAdbGrant(): AdbGrant {
    val g = engine ?: throw UnsupportedGrantException("Grant '$name' has no catalogue entry")
    fun field(key: String) = g.fields[key] ?: throw UnsupportedGrantException("Grant '${g.kind}' lacks '$key'")
    return when (g.kind) {
        "permission" -> AdbGrant.Permission(field("name"), optional)
        "appop" -> AdbGrant.AppOp(field("op"), g.fields["mode"] ?: "allow", optional)
        "notificationListener" -> AdbGrant.NotificationListener(field("component"), optional)
        "deviceIdleWhitelist" -> AdbGrant.DeviceIdleWhitelist(optional)
        "secureSetting" -> AdbGrant.SecureSetting(field("key"), field("value"), optional)
        else -> throw UnsupportedGrantException("Unknown grant kind '${g.kind}'")
    }
}
