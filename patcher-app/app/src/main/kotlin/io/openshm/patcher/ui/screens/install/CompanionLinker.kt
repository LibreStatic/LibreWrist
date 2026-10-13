package io.openshm.patcher.ui.screens.install

import io.openshm.patcher.adb.Grant as AdbGrant
import io.openshm.patcher.device.WatchDevice
import java.io.IOException
import kotlinx.coroutines.delay

/**
 * Links the started watch bridge to the phone in the watch's Companion Device Manager, so the bridge
 * can run without its foreground-service notification. Waits for the bridge to record the phone's
 * MAC (set when its session to the phone bridge opens), associates it and sends START again so the
 * bridge switches to companion mode, then makes sure the bridge is still on the Doze allow-list
 * ([keepDozeExemption]). Never fails the caller (only cancellation propagates): without
 * a MAC or on any error the bridge keeps working in its notification mode.
 */
internal class CompanionLinker(
    private val device: WatchDevice,
    private val log: (String) -> Unit,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun link(pkg: String, prefix: String) {
        try {
            val mac = waitForPhoneMac(pkg)
            if (mac == null) {
                log("no phone address from $pkg yet, companion link skipped")
                return
            }
            val existing = device.companionAssociations().filter { it.packageName == pkg }
            if (existing.size != 1 || existing.single().mac != mac) {
                for (old in existing) if (old.mac != mac) device.disassociateCompanion(pkg, old.mac)
                if (existing.none { it.mac == mac }) {
                    device.associateCompanion(pkg, mac)
                    log("linked $pkg to the phone in the companion device manager")
                }
                device.startBridge(pkg, prefix)
            }
            keepDozeExemption(pkg)
        } catch (e: IOException) {
            log("companion link for $pkg failed: ${e.javaClass.simpleName}")
        } catch (e: IllegalArgumentException) {
            log("companion link for $pkg rejected: ${e.javaClass.simpleName}")
        } catch (e: UnsupportedOperationException) {
            log("companion link for $pkg not supported: ${e.javaClass.simpleName}")
        }
    }

    /**
     * The watch's Companion Device Manager drops the user Doze exemption of an associated package a
     * few seconds after that package is updated (seen on a Galaxy Watch with Wear OS 5; an
     * unassociated package keeps it, and associating does not remove it). The grant step ran before
     * that, so wait for it to settle and put the exemption back when it is gone.
     */
    private suspend fun keepDozeExemption(pkg: String) {
        repeat(DOZE_TRIES) {
            pause(DOZE_SETTLE_MS)
            if (device.isDozeExempt(pkg) != false) return
            val report = device.applyGrants(pkg, listOf(AdbGrant.DeviceIdleWhitelist()))
            log(if (report.ok) "restored the doze exemption of $pkg" else "could not restore the doze exemption of $pkg")
        }
    }

    private suspend fun waitForPhoneMac(pkg: String): String? {
        var waited = 0L
        while (true) {
            device.phoneMac(pkg)?.let { return it.uppercase() }
            if (waited >= MAX_WAIT_MS) return null
            pause(POLL_MS)
            waited += POLL_MS
        }
    }

    private companion object {
        const val POLL_MS = 2_000L
        const val MAX_WAIT_MS = 20_000L
        const val DOZE_SETTLE_MS = 5_000L
        const val DOZE_TRIES = 3
    }
}
