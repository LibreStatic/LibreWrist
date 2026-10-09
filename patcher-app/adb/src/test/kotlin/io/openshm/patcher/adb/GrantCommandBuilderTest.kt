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
