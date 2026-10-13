package io.openshm.patcher.device.logs

import android.content.Context
import android.util.Log
import io.openshm.patcher.adb.AdbClient
import io.openshm.patcher.adb.AdbDiscovery
import io.openshm.patcher.adb.AdbException
import io.openshm.patcher.adb.AdbKeyStore
import io.openshm.patcher.adb.AdbServiceType
import io.openshm.patcher.adb.AndroidOwnAddresses
import io.openshm.patcher.adb.LibAdbBackend
import io.openshm.patcher.adb.SelfAdbSession
import io.openshm.patcher.adb.SelfAdbState
import io.openshm.patcher.device.DisconnectReason
import io.openshm.patcher.device.SelfAdbTrust
import io.openshm.patcher.device.WatchLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** A [LogSession] over an [AdbClient]; [onClose] decides whether closing also closes the link. */
internal class AdbLogSession(private val client: AdbClient, private val onClose: () -> Unit = {}) : LogSession {
    override suspend fun shell(command: String): LogShellResult {
        val result = client.shell(command)
        return LogShellResult(result.exitCode, result.stdout)
    }

    override suspend fun stream(command: String, onLine: (String) -> Unit) = client.streamShell(command, onLine)

    override fun close() = onClose()
}

/** The paired watch, through the shared [WatchLink]. The link is shared, so closing a session leaves it open. */
internal class WatchLogSource(private val link: WatchLink) : DeviceLogSource {
    override suspend fun connect(): LogSession {
        if (link.rememberedHost == null) throw LogLinkException(DisconnectReason.NotPaired)
        try {
            return AdbLogSession(link.client())
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdbException.PairingRequired) {
            throw LogLinkException(DisconnectReason.NotPaired, e)
        } catch (e: AdbException.AuthenticationRejected) {
            throw LogLinkException(DisconnectReason.NotPaired, e)
        } catch (e: AdbException) {
            throw LogLinkException(DisconnectReason.WirelessDebuggingOff, e)
        } catch (e: Exception) {
            throw LogLinkException(DisconnectReason.Error, e)
        }
    }

    override fun reset() = link.forget()
}

/**
 * This phone through its own Wireless debugging. Each connect builds a [SelfAdbSession] of its own (the
 * shared `SelfAdbController` stays free for the grants) and uses only its reconnect path: it never enters
 * the pairing-code flow. A key the phone does not trust yet, or Wireless debugging being off, ends in a
 * [LogLinkException] saying which.
 */
internal class PhoneLogSource(context: Context) : DeviceLogSource {
    private val app = context.applicationContext

    override suspend fun connect(): LogSession {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val discovery = AdbDiscovery(app)
        val keyRejected = java.util.concurrent.atomic.AtomicBoolean(false)
        val session = SelfAdbSession(
            backend = LibAdbBackend(AdbKeyStore.forContext(app)),
            pairingServices = discovery.discover(AdbServiceType.PAIRING),
            connectServices = discovery.discover(AdbServiceType.CONNECT),
            scope = scope,
            isLocalHost = AndroidOwnAddresses(app)::contains,
            log = {
                Log.i(LOG_TAG, it)
                if (it.startsWith("reconnect failed: AuthenticationRejected") || it.startsWith("reconnect failed: PairingRequired")) {
                    keyRejected.set(true)
                }
            },
        )
        var handedOut = false
        try {
            session.start()
            val settled = withTimeoutOrNull(SETTLE_TIMEOUT_MS) {
                session.state.first { it != SelfAdbState.Idle && it != SelfAdbState.Reconnecting }
            }
            when (settled) {
                is SelfAdbState.Connected -> {
                    SelfAdbTrust.record(app)
                    handedOut = true
                    return AdbLogSession(settled.client) {
                        session.stop() // also closes the client
                        scope.cancel()
                    }
                }
                is SelfAdbState.Failed -> throw LogLinkException(DisconnectReason.Error, settled.error)
                null -> throw LogLinkException(DisconnectReason.WirelessDebuggingOff)
                // The reconnect found nothing usable and the session moved on to wait for pairing: never follow it.
                else -> throw LogLinkException(
                    if (keyRejected.get()) DisconnectReason.KeyNotTrusted else DisconnectReason.WirelessDebuggingOff,
                )
            }
        } finally {
            if (!handedOut) {
                session.stop()
                scope.cancel()
            }
        }
    }

    override fun reset() = Unit

    private companion object {
        const val LOG_TAG = "DeviceLogs"
        const val SETTLE_TIMEOUT_MS = 60_000L
    }
}
