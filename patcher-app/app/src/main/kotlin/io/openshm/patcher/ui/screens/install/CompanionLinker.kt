package io.openshm.patcher.ui.screens.install

import io.openshm.patcher.device.WatchDevice
import java.io.IOException
import kotlinx.coroutines.delay

/**
 * Links the started watch bridge to the phone in the watch's Companion Device Manager, so the bridge
 * can run without its foreground-service notification. Waits for the bridge to record the phone's
 * MAC (set when its session to the phone bridge opens), associates it and sends START again so the
 * bridge switches to companion mode. Never fails the caller (only cancellation propagates): without
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
            if (existing.size == 1 && existing.single().mac == mac) return // already linked
            for (old in existing) if (old.mac != mac) device.disassociateCompanion(pkg, old.mac)
            if (existing.none { it.mac == mac }) {
                device.associateCompanion(pkg, mac)
                log("linked $pkg to the phone in the companion device manager")
            }
            device.startBridge(pkg, prefix)
        } catch (e: IOException) {
            log("companion link for $pkg failed: ${e.javaClass.simpleName}")
        } catch (e: IllegalArgumentException) {
            log("companion link for $pkg rejected: ${e.javaClass.simpleName}")
        } catch (e: UnsupportedOperationException) {
            log("companion link for $pkg not supported: ${e.javaClass.simpleName}")
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
    }
}
