package io.openshm.patcher.adb

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GrantCommandBuilderTest {
    private val watch = GrantCommandBuilder(GrantTarget.WATCH)
    private val phone = GrantCommandBuilder(GrantTarget.PHONE)
    private val pkg = "io.example.app"

    @Test
    fun `maps every grant kind`() {
        assertEquals(
            "pm grant io.example.app android.permission.POST_NOTIFICATIONS",
            watch.build(pkg, Grant.Permission("android.permission.POST_NOTIFICATIONS")),
        )
        assertEquals(
            "appops set io.example.app SCHEDULE_EXACT_ALARM allow",
            watch.build(pkg, Grant.AppOp("SCHEDULE_EXACT_ALARM")),
        )
        assertEquals("cmd deviceidle whitelist +io.example.app", watch.build(pkg, Grant.DeviceIdleWhitelist()))
        assertEquals(
            "settings put secure doze_always_on 0",
            watch.build(pkg, Grant.SecureSetting("doze_always_on", "0")),
        )
    }

    @Test
    fun `notification listener relative and fully qualified, with phone user arg`() {
        assertEquals(
            "cmd notification allow_listener io.example.app/.SamsungAlarmListener",
            watch.build(pkg, Grant.NotificationListener(".SamsungAlarmListener")),
        )
        assertEquals(
            "cmd notification allow_listener io.example.app/.SamsungAlarmListener 0",
            phone.build(pkg, Grant.NotificationListener(".SamsungAlarmListener")),
        )
        assertEquals(
            "cmd notification allow_listener io.example.app/io.example.app.Listener 0",
            phone.build(pkg, Grant.NotificationListener("io.example.app.Listener")),
        )
    }

    @Test
    fun `rejects values outside the catalogue character sets`() {
        val bad = listOf<() -> Unit>(
            { watch.build("io.example.app; reboot", Grant.DeviceIdleWhitelist()) },
            { watch.build("noDots", Grant.DeviceIdleWhitelist()) },
            { watch.build(pkg, Grant.Permission("android.permission.X; id")) },
            { watch.build(pkg, Grant.Permission("android.permission.X\nid")) },
            { watch.build(pkg, Grant.AppOp("lower")) },
            { watch.build(pkg, Grant.AppOp("OP", mode = "deny")) },
            { watch.build(pkg, Grant.NotificationListener(".A B")) },
            { watch.build(pkg, Grant.NotificationListener("noDotsNoLeadingDot")) },
            { watch.build(pkg, Grant.NotificationListener("A/../B")) },
            { watch.build(pkg, Grant.SecureSetting("Key", "0")) },
            { watch.build(pkg, Grant.SecureSetting("key", "a b")) },
            { watch.build(pkg, Grant.SecureSetting("key", "$(id)")) },
            { watch.build(pkg, Grant.SecureSetting("key", "x".repeat(65))) },
            { watch.build(pkg, Grant.SecureSetting("key", "")) },
            { watch.build(pkg, Grant.SecureSetting("key", "-1")) },
            { watch.build(pkg, Grant.SecureSetting("key", "--user")) },
        )
        for (case in bad) {
            try {
                case()
                fail("expected rejection")
            } catch (expected: UnsafeGrantValueException) {
                assertTrue(expected.message!!.startsWith("Unsafe"))
            }
        }
    }

    @Test
    fun `global setting build is refused because it needs a read-modify-write`() {
        try {
            watch.build(pkg, Grant.GlobalSetting("hidden_api_blacklist_exemptions", "Lcom/samsung/android/hardware/sensormanager/"))
            fail("expected refusal")
        } catch (expected: UnsupportedOperationException) {
            assertTrue(expected.message!!.contains("GrantApplier"))
        }
        for (bad in listOf<() -> Unit>(
            { watch.build(pkg, Grant.GlobalSetting("Bad Key", "Lcom/foo/")) },
            { watch.build(pkg, Grant.GlobalSetting("some_key", "Lcom/foo bar/")) },
            { watch.build(pkg, Grant.GlobalSetting("some_key", "Lcom/foo/;id")) },
            { watch.build(pkg, Grant.GlobalSetting("some_key", "Lcom/foo/'")) },
            { watch.build(pkg, Grant.GlobalSetting("some_key", "com/foo/")) },
            { watch.build(pkg, Grant.GlobalSetting("some_key", "")) },
        )) {
            try {
                bad()
                fail("expected rejection")
            } catch (expected: UnsafeGrantValueException) {
                assertTrue(expected.message!!.startsWith("Unsafe"))
            }
        }
    }

    @Test
    fun `applier appends a global setting entry, preserving foreign entries, then force-stops`() = runBlocking {
        val ran = mutableListOf<String>()
        val client = AdbClient(scriptedTransport(shell = { cmd ->
            ran += cmd
            when (cmd) {
                "settings get global hidden_api_blacklist_exemptions" -> ok("Lother/thing/\n")
                else -> ok()
            }
        }))
        val grant = Grant.GlobalSetting("hidden_api_blacklist_exemptions", "Lcom/samsung/android/hardware/sensormanager/")
        val report = GrantApplier(client, GrantTarget.WATCH).apply(pkg, listOf(grant))
        assertTrue(report.ok)
        assertEquals(
            listOf(
                "settings get global hidden_api_blacklist_exemptions",
                "settings put global hidden_api_blacklist_exemptions 'Lother/thing/,Lcom/samsung/android/hardware/sensormanager/'",
                "am force-stop io.example.app",
            ),
            ran,
        )
    }

    @Test
    fun `applier refuses to rewrite a global setting whose foreign entries are not quotable`() = runBlocking {
        val grant = Grant.GlobalSetting("hidden_api_blacklist_exemptions", "Lcom/samsung/android/hardware/sensormanager/")
        for (current in listOf("Lother/it's/\n", "Lfirst/\nLsecond/\n")) {
            val ran = mutableListOf<String>()
            val client = AdbClient(scriptedTransport(shell = { cmd ->
                ran += cmd
                if (cmd.startsWith("settings get")) ok(current) else ok()
            }))
            val report = GrantApplier(client, GrantTarget.WATCH).apply(pkg, listOf(grant))
            assertFalse(report.ok)
            assertEquals(listOf("settings get global hidden_api_blacklist_exemptions"), ran)
        }
    }

    @Test
    fun `applier writes a global setting alone when unset and skips the write when already present`() = runBlocking {
        val grant = Grant.GlobalSetting("hidden_api_blacklist_exemptions", "Lcom/samsung/android/hardware/sensormanager/")

        val unsetRan = mutableListOf<String>()
        val unsetClient = AdbClient(scriptedTransport(shell = { cmd ->
            unsetRan += cmd
            if (cmd.startsWith("settings get")) ok("null\n") else ok()
        }))
        assertTrue(GrantApplier(unsetClient, GrantTarget.WATCH).apply(pkg, listOf(grant)).ok)
        assertEquals(
            listOf(
                "settings get global hidden_api_blacklist_exemptions",
                "settings put global hidden_api_blacklist_exemptions 'Lcom/samsung/android/hardware/sensormanager/'",
                "am force-stop io.example.app",
            ),
            unsetRan,
        )

        val presentRan = mutableListOf<String>()
        val presentClient = AdbClient(scriptedTransport(shell = { cmd ->
            presentRan += cmd
            if (cmd.startsWith("settings get")) ok("Lother/,Lcom/samsung/android/hardware/sensormanager/\n") else ok()
        }))
        assertTrue(GrantApplier(presentClient, GrantTarget.WATCH).apply(pkg, listOf(grant)).ok)
        assertEquals(listOf("settings get global hidden_api_blacklist_exemptions"), presentRan)
    }

    @Test
    fun `applier validates a global setting before running any command`() = runBlocking {
        val ran = mutableListOf<String>()
        val client = AdbClient(scriptedTransport(shell = { ran += it; ok() }))
        try {
            GrantApplier(client, GrantTarget.WATCH).apply(
                pkg,
                listOf(Grant.DeviceIdleWhitelist(), Grant.GlobalSetting("some_key", "Lcom/foo bar/")),
            )
            fail("expected rejection")
        } catch (expected: UnsafeGrantValueException) {
            assertTrue(ran.isEmpty())
        }
    }

    @Test
    fun `applier skips failed optional grants and stops at a failed required one`() = runBlocking {
        val ran = mutableListOf<String>()
        val transport = scriptedTransport(shell = { cmd ->
            ran += cmd
            when {
                cmd.contains("doze_always_on") -> ShellResult(255, "", "denied")
                cmd.contains("BLUETOOTH_CONNECT") -> ShellResult(1, "", "no")
                else -> ok()
            }
        })
        val client = AdbClient(transport)
        val applier = GrantApplier(client, GrantTarget.WATCH)

        val first = applier.apply(
            pkg,
            listOf(
                Grant.SecureSetting("doze_always_on", "0", optional = true),
                Grant.Permission("android.permission.POST_NOTIFICATIONS"),
            ),
        )
        assertTrue(first.ok)
        assertEquals(1, first.failedOptional.size)
        assertEquals(2, first.outcomes.size)

        ran.clear()
        val second = applier.apply(
            pkg,
            listOf(
                Grant.Permission("android.permission.BLUETOOTH_CONNECT"),
                Grant.Permission("android.permission.POST_NOTIFICATIONS"),
            ),
        )
        assertFalse(second.ok)
        assertEquals(1, ran.size)
    }

    @Test
    fun `applier validates every grant before running any command`() = runBlocking {
        val ran = mutableListOf<String>()
        val client = AdbClient(scriptedTransport(shell = { ran += it; ok() }))
        try {
            GrantApplier(client, GrantTarget.PHONE).apply(
                pkg,
                listOf(Grant.DeviceIdleWhitelist(), Grant.Permission("bad value")),
            )
            fail("expected rejection")
        } catch (expected: UnsafeGrantValueException) {
            assertTrue(ran.isEmpty())
        }
    }
}
