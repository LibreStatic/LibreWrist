package io.openshm.patcher.adb

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PKG = "io.test.bridgewatch"

private val DUMPSYS_PACKAGE = """
    Packages:
      Package [$PKG] (3f2a1b):
        userId=10190
        versionCode=3 minSdk=30 targetSdk=34
        versionName=0.3.0
        requested permissions:
          android.permission.POST_NOTIFICATIONS: restricted=false
          android.permission.BLUETOOTH_CONNECT
        install permissions:
          android.permission.INTERNET: granted=true
          android.permission.FOREGROUND_SERVICE: granted=true
        User 0: ceDataInode=22337 deviceProtectedDataInode=0 installed=true hidden=false suspended=false distractionFlags=0 stopped=false notLaunched=false enabled=0 instant=false virtual=false
          gids=[3002]
          runtime permissions:
            android.permission.POST_NOTIFICATIONS: granted=true, flags=[ USER_SET|REVOKE_WHEN_REQUESTED]
            android.permission.BLUETOOTH_CONNECT: granted=false, flags=[ USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]
""".trimIndent()

class GrantReadbackTest {
    @Test
    fun `permissions come from install and runtime sections`() {
        val parsed = GrantReadback.permissions(DUMPSYS_PACKAGE)
        assertEquals(true, parsed["android.permission.POST_NOTIFICATIONS"])
        assertEquals(false, parsed["android.permission.BLUETOOTH_CONNECT"])
        assertEquals(true, parsed["android.permission.INTERNET"])
        // "requested permissions" lines carry no granted= flag and must not appear as granted.
        assertEquals(4, parsed.size)
    }

    @Test
    fun `a permission granted for any user counts as granted`() {
        val dump = """
            User 0:
              runtime permissions:
                android.permission.READ_PHONE_STATE: granted=false, flags=[ ]
            User 10:
              runtime permissions:
                android.permission.READ_PHONE_STATE: granted=true, flags=[ USER_SET]
        """.trimIndent()
        assertEquals(true, GrantReadback.permissions(dump)["android.permission.READ_PHONE_STATE"])
    }

    @Test
    fun `permissions of an unknown package are empty`() {
        assertTrue(GrantReadback.permissions("Unable to find package: io.nope.app").isEmpty())
    }

    @Test
    fun `appops get reads the mode with and without access time`() {
        assertEquals("allow", GrantReadback.appOpMode("SCHEDULE_EXACT_ALARM: allow\n", "SCHEDULE_EXACT_ALARM"))
        assertEquals(
            "allow",
            GrantReadback.appOpMode("USE_FULL_SCREEN_INTENT: allow; time=+3d2h5m ago; rejectTime=+1d ago\n", "USE_FULL_SCREEN_INTENT"),
        )
        assertEquals("ignore", GrantReadback.appOpMode("SYSTEM_ALERT_WINDOW: ignore\n", "SYSTEM_ALERT_WINDOW"))
        assertEquals("foreground", GrantReadback.appOpMode("MANAGE_ONGOING_CALLS: foreground\n", "MANAGE_ONGOING_CALLS"))
    }

    @Test
    fun `appops get without explicit mode is null`() {
        assertNull(GrantReadback.appOpMode("No operations.\n", "SCHEDULE_EXACT_ALARM"))
        assertNull(GrantReadback.appOpMode("", "SCHEDULE_EXACT_ALARM"))
        assertNull(GrantReadback.appOpMode("OTHER_OP: allow\n", "SCHEDULE_EXACT_ALARM"))
    }

    @Test
    fun `a uid mode line wins over the package line`() {
        val out = "Uid mode: SYSTEM_ALERT_WINDOW: ignore\nSYSTEM_ALERT_WINDOW: allow\n"
        assertEquals("ignore", GrantReadback.appOpMode(out, "SYSTEM_ALERT_WINDOW"))
    }

    @Test
    fun `settings get prints a value or null`() {
        assertEquals("0", GrantReadback.settingValue("0\n"))
        assertNull(GrantReadback.settingValue("null\n"))
        assertNull(GrantReadback.settingValue(""))
        assertEquals("a/b:c/d", GrantReadback.settingValue("a/b:c/d\n"))
    }

    @Test
    fun `listener is found in the stored short and full forms`() {
        val full = "io.other/io.other.Svc:$PKG/$PKG.SamsungAlarmListener"
        val short = "$PKG/.SamsungAlarmListener"
        assertTrue(GrantReadback.listenerEnabled(full, PKG, ".SamsungAlarmListener"))
        assertTrue(GrantReadback.listenerEnabled(short, PKG, ".SamsungAlarmListener"))
        assertTrue(GrantReadback.listenerEnabled(full, PKG, "$PKG.SamsungAlarmListener"))
        assertFalse(GrantReadback.listenerEnabled("io.other/io.other.Svc", PKG, ".SamsungAlarmListener"))
        assertFalse(GrantReadback.listenerEnabled(null, PKG, ".SamsungAlarmListener"))
        assertFalse(GrantReadback.listenerEnabled("$PKG/$PKG.OtherListener", PKG, ".SamsungAlarmListener"))
    }

    @Test
    fun `comma list entries match exactly, not by prefix or substring`() {
        val value = "Lcom/samsung/android/hardware/sensormanager/"
        assertTrue(GrantReadback.listContainsEntry(value, value))
        assertTrue(GrantReadback.listContainsEntry("Lother/,$value", value))
        assertTrue(GrantReadback.listContainsEntry("$value,Lother/", value))
        assertFalse(GrantReadback.listContainsEntry("Lcom/samsung/android/hardware/sensormanager/extra/", value))
        assertFalse(GrantReadback.listContainsEntry("Lcom/samsung/android/hardware/sensormanager", value))
        assertFalse(GrantReadback.listContainsEntry("XL$value", value))
        assertFalse(GrantReadback.listContainsEntry(null, value))
        assertFalse(GrantReadback.listContainsEntry("null", value))
        assertFalse(GrantReadback.listContainsEntry("", value))
    }

    @Test
    fun `appending to a comma list covers unset, null and foreign entries`() {
        val value = "Lcom/samsung/android/hardware/sensormanager/"
        assertEquals(value, GrantReadback.appendListEntry(null, value))
        assertEquals(value, GrantReadback.appendListEntry("null", value))
        assertEquals(value, GrantReadback.appendListEntry("", value))
        assertEquals("Lother/,$value", GrantReadback.appendListEntry("Lother/", value))
        assertEquals("Lother/,$value", GrantReadback.appendListEntry("Lother/,$value", value))
        // A similar-but-different entry is not mistaken for ours, and stays verbatim.
        assertEquals(
            "Lcom/samsung/android/hardware/sensormanager/extra/,$value",
            GrantReadback.appendListEntry("Lcom/samsung/android/hardware/sensormanager/extra/", value),
        )
    }

    @Test
    fun `removing a comma list entry keeps the others verbatim and in order`() {
        val value = "Lcom/samsung/android/hardware/sensormanager/"
        assertNull(GrantReadback.removeListEntry(value, value))
        assertNull(GrantReadback.removeListEntry("$value,$value", value))
        assertNull(GrantReadback.removeListEntry(null, value))
        assertNull(GrantReadback.removeListEntry("null", value))
        assertNull(GrantReadback.removeListEntry("  ", value))
        assertEquals("Lb/,Lc/", GrantReadback.removeListEntry("$value,Lb/,Lc/", value))
        assertEquals("Lb/,Lc/", GrantReadback.removeListEntry("Lb/,$value,Lc/", value))
        assertEquals("Lb/,Lc/", GrantReadback.removeListEntry("Lb/,Lc/,$value", value))
        assertEquals("Lb/", GrantReadback.removeListEntry("Lb/, $value ", value))
        val longer = "${value}extra/"
        assertEquals(longer, GrantReadback.removeListEntry("$longer,$value", value))
    }

    @Test
    fun `deviceidle whitelist matches the exact package`() {
        val dump = """
            system-excidle,com.android.shell,2000
            system,com.google.android.gms,10102
            user,$PKG,10190
        """.trimIndent()
        assertTrue(GrantReadback.deviceIdleWhitelisted(dump, PKG))
        assertFalse(GrantReadback.deviceIdleWhitelisted(dump, "io.test.bridge"))
        assertFalse(GrantReadback.deviceIdleWhitelisted("", PKG))
    }
}

class GrantCheckerTest {
    private val commands = ArrayList<String>()

    private fun checker(answers: Map<String, ShellResult>) = GrantChecker { command ->
        commands += command
        answers[command] ?: ShellResult(1, "", "unexpected: $command")
    }

    private fun ok(stdout: String) = ShellResult(0, stdout, "")

    @Test
    fun `every grant kind is read back from the matching command`() = runBlocking {
        val answers = mapOf(
            "dumpsys package $PKG" to ok(DUMPSYS_PACKAGE),
            "appops get $PKG SCHEDULE_EXACT_ALARM" to ok("SCHEDULE_EXACT_ALARM: allow\n"),
            "appops get $PKG SYSTEM_ALERT_WINDOW" to ok("SYSTEM_ALERT_WINDOW: ignore\n"),
            "appops get $PKG USE_FULL_SCREEN_INTENT" to ok("No operations.\n"),
            "settings get secure doze_always_on" to ok("0\n"),
            "settings get secure enabled_notification_listeners" to ok("$PKG/$PKG.SamsungAlarmListener\n"),
            "dumpsys deviceidle whitelist" to ok("system,com.google.android.gms,10102\n"),
        )
        val grants = listOf(
            Grant.Permission("android.permission.POST_NOTIFICATIONS"),
            Grant.Permission("android.permission.BLUETOOTH_CONNECT"),
            Grant.AppOp("SCHEDULE_EXACT_ALARM"),
            Grant.AppOp("SYSTEM_ALERT_WINDOW"),
            Grant.AppOp("USE_FULL_SCREEN_INTENT"),
            Grant.SecureSetting("doze_always_on", "0", optional = true),
            Grant.NotificationListener(".SamsungAlarmListener"),
            Grant.DeviceIdleWhitelist(),
        )

        val states = checker(answers).check(PKG, grants).map { it.state }

        assertEquals(
            listOf(
                GrantState.Granted, GrantState.Missing, GrantState.Granted, GrantState.Missing,
                GrantState.Unknown, GrantState.Granted, GrantState.Granted, GrantState.Missing,
            ),
            states,
        )
        // The package dump is fetched once for both permissions.
        assertEquals(1, commands.count { it == "dumpsys package $PKG" })
    }

    @Test
    fun `a global setting is granted only when the exact entry is present`() = runBlocking {
        val cmd = "settings get global hidden_api_blacklist_exemptions"
        val grant = Grant.GlobalSetting("hidden_api_blacklist_exemptions", "Lcom/samsung/android/hardware/sensormanager/")
        val granted = checker(mapOf(cmd to ok("Lother/,Lcom/samsung/android/hardware/sensormanager/\n"))).check(PKG, listOf(grant))
        val similar = checker(mapOf(cmd to ok("Lcom/samsung/android/hardware/sensormanager/extra/\n"))).check(PKG, listOf(grant))
        val unset = checker(mapOf(cmd to ok("null\n"))).check(PKG, listOf(grant))
        val failed = checker(emptyMap()).check(PKG, listOf(grant))
        assertEquals(GrantState.Granted, granted.single().state)
        assertEquals(GrantState.Missing, similar.single().state)
        assertEquals(GrantState.Missing, unset.single().state)
        assertEquals(GrantState.Unknown, failed.single().state)
        assertTrue(commands.all { it == cmd })
    }

    @Test
    fun `a setting with another value or unset is missing`() = runBlocking {
        val grant = Grant.SecureSetting("doze_always_on", "0")
        val other = checker(mapOf("settings get secure doze_always_on" to ok("1\n"))).check(PKG, listOf(grant))
        val unset = checker(mapOf("settings get secure doze_always_on" to ok("null\n"))).check(PKG, listOf(grant))
        assertEquals(GrantState.Missing, other.single().state)
        assertEquals(GrantState.Missing, unset.single().state)
    }

    @Test
    fun `a failed command is unknown, not missing`() = runBlocking {
        val checks = checker(emptyMap()).check(
            PKG,
            listOf(Grant.Permission("android.permission.POST_NOTIFICATIONS"), Grant.AppOp("SCHEDULE_EXACT_ALARM")),
        )
        assertEquals(listOf(GrantState.Unknown, GrantState.Unknown), checks.map { it.state })
    }

    @Test
    fun `unsafe values never reach the shell`() {
        val c = checker(emptyMap())
        runCatching { runBlocking { c.check(PKG, listOf(Grant.AppOp("X; reboot"))) } }
            .also { assertTrue(it.exceptionOrNull() is IllegalArgumentException) }
        runCatching { runBlocking { c.check("not a package; id", emptyList()) } }
            .also { assertTrue(it.exceptionOrNull() is IllegalArgumentException) }
        assertTrue(commands.isEmpty())
    }
}
