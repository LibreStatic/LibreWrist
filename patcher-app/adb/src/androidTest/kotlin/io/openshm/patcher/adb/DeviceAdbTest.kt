package io.openshm.patcher.adb

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * End-to-end checks of the ADB client on real devices. Every test is skipped unless its
 * instrumentation arguments are given, so the suite is inert on a plain `connectedAndroidTest`.
 *
 * Arguments (`am instrument -e <name> <value>`):
 * - `watchHost`: IP address of a watch with Wireless debugging on, on the same Wi-Fi.
 * - `watchCode`: the six-digit code of the watch's "Pair new device" screen (optional when this
 *   test app is already paired). The pairing port is found through mDNS.
 * - `watchInstall`: `true` to install this test APK on the watch and uninstall it again.
 * - `selfCode`: the code of this phone's own "Pair device with pairing code" dialog (self-ADB).
 *
 * Results are logged under the tag [TAG].
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DeviceAdbTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val keys = AdbKeyStore.forContext(context)
    private val backend = LibAdbBackend(keys)

    private fun arg(name: String): String? = args.getString(name)?.takeIf { it.isNotBlank() }

    private fun log(msg: String) {
        Log.i(TAG, msg)
    }

    private suspend fun findService(type: AdbServiceType, host: String): AdbService =
        withTimeout(DISCOVERY_TIMEOUT_MS) {
            AdbDiscovery(context).discover(type).first { list -> list.any { it.host == host } }
                .first { it.host == host }
        }.also { log("discovered ${type.name} ${it.name} at ${it.host}:${it.port}") }

    @Test
    fun a1_pairWithWatch() = runBlocking {
        val host = arg("watchHost")
        val code = arg("watchCode")
        assumeTrue(host != null && code != null)
        log("key fingerprint ${keys.fingerprint()}")
        val pairing = findService(AdbServiceType.PAIRING, host!!)
        AdbPairing(backend).pair(pairing.host, pairing.port, code!!)
        log("paired with ${pairing.host}:${pairing.port}")
    }

    @Test
    fun a2_connectShellAndIdentity() = runBlocking {
        val host = arg("watchHost")
        assumeTrue(host != null)
        val service = findService(AdbServiceType.CONNECT, host!!)
        val client = AdbClient.connect(backend, service.host, service.port)
        try {
            val id = client.deviceIdentity()
            log("watch identity model=${id.model} serial=${id.serial}")
            assertTrue(id.model.isNotEmpty())

            val echo = client.shell("echo openshm-ok; id -u")
            log("shell exit=${echo.exitCode} out=${echo.stdout.trim().replace('\n', ' ')}")
            assertTrue(echo.isSuccess)
            assertTrue(echo.stdout.contains("openshm-ok"))
            assertTrue(echo.stdout.lines().any { it.trim() == "2000" })

            val failing = client.shell("exit 3")
            assertEquals(3, failing.exitCode)

            val pkgs = RemotePackages(client).listPackages()
            log("watch has ${pkgs.size} packages")
            assertTrue(pkgs.isNotEmpty())

            if (arg("watchInstall") == "true") installAndRemoveSelf(client)
        } finally {
            client.close()
        }
    }

    private suspend fun installAndRemoveSelf(client: AdbClient) {
        val apk = File(instrumentation.context.packageCodePath)
        val pkg = instrumentation.context.packageName
        val packages = RemotePackages(client)
        assumeTrue("$pkg is already on the watch", !packages.isInstalled(pkg))
        val result = AdbInstaller(client).install(listOf(ApkSource("base.apk", apk.length()) { apk.inputStream() })) { w, t ->
            if (w == t) log("install wrote $w bytes")
        }
        log("install result $result")
        assertTrue(result.toString(), result is InstallResult.Success)
        assertTrue(packages.isInstalled(pkg))
        val removed = client.shell("pm uninstall $pkg")
        log("uninstall: ${removed.stdout.trim()}")
        assertTrue(removed.isSuccess)
        assertTrue(!packages.isInstalled(pkg))
    }

    @Test
    fun b1_selfAdb() = runBlocking {
        val code = arg("selfCode")
        assumeTrue(code != null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val discovery = AdbDiscovery(context)
        val session = SelfAdbSession(
            backend,
            discovery.discover(AdbServiceType.PAIRING),
            discovery.discover(AdbServiceType.CONNECT),
            scope,
        )
        try {
            session.start()
            val waiting = withTimeout(DISCOVERY_TIMEOUT_MS) { session.state.first { it is SelfAdbState.WaitingForCode || it is SelfAdbState.Failed || it == SelfAdbState.AmbiguousService } }
            log("self-ADB state $waiting")
            assertTrue(waiting.toString(), waiting is SelfAdbState.WaitingForCode)
            session.submitCode(code!!)
            val end = withTimeout(60_000) { session.state.first { it is SelfAdbState.Connected || it is SelfAdbState.Failed || it == SelfAdbState.AmbiguousService || (it is SelfAdbState.WaitingForCode && it.lastFailure != null) } }
            log("self-ADB end state $end")
            assertTrue(end.toString(), end is SelfAdbState.Connected)
            val client = (end as SelfAdbState.Connected).client
            val id = client.shell("id -u; getprop ro.product.model")
            log("self shell: ${id.stdout.trim().replace('\n', ' ')}")
            assertTrue(id.stdout.lines().any { it.trim() == "2000" })
            client.close()
        } finally {
            session.stop()
            scope.cancel()
        }
    }

    companion object {
        const val TAG = "OpenShmDeviceTest"
        const val DISCOVERY_TIMEOUT_MS = 30_000L
    }
}
