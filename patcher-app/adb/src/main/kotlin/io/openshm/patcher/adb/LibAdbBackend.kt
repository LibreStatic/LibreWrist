package io.openshm.patcher.adb

import android.os.Build
import io.github.muntashirakon.adb.AdbConnection
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.PairingConnectionCtx
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * [AdbBackend] on top of libadb-android. This is the only class that touches libadb's protocol,
 * pairing and TLS code; it needs a device, so it is exercised by device tests only.
 *
 * TLS 1.3 needs Conscrypt; libadb loads `org.conscrypt.OpenSSLProvider` reflectively, which is
 * why `conscrypt-android` is a dependency of this module.
 */
class LibAdbBackend(
    private val keys: AdbKeyStore,
    private val deviceName: String = "LibreWrist",
    private val peerApi: Int = Build.VERSION.SDK_INT,
) : AdbBackend {

    override fun pair(host: String, port: Int, code: String, abort: AbortScope) {
        beforeTls()
        val ctx = PairingConnectionCtx(
            host,
            port,
            code.toByteArray(Charsets.UTF_8),
            keys.privateKey(),
            keys.certificate(),
            deviceName,
        )
        // Closing the context closes its socket, which unblocks a wedged read after a timeout.
        abort.register(ctx)
        ctx.use { it.start() }
    }

    override fun connect(host: String, port: Int, timeoutMillis: Long, abort: AbortScope): AdbTransport {
        beforeTls()
        val connection = AdbConnection.create(host, port, keys.privateKey(), keys.certificate(), peerApi)
        try {
            abort.register(connection)
            connection.setDeviceName(deviceName)
            if (!connection.connect(timeoutMillis, TimeUnit.MILLISECONDS, false)) {
                throw AdbException.Timeout("waiting for the ADB daemon to accept the connection")
            }
        } catch (t: Throwable) {
            runCatching { connection.close() }
            throw t
        }
        return LibAdbTransport(connection)
    }

    /**
     * Fails closed before any TLS use: the key must not have been reset in this process, and our
     * Conscrypt provider (which libadb loads by this same reflective path) must be usable, because
     * otherwise libadb silently falls back to the platform TLS stack.
     */
    private fun beforeTls() {
        AdbKeyStore.markTlsUse()
        ensureConscrypt()
    }

    private companion object {
        @Volatile
        var conscryptChecked = false

        fun ensureConscrypt() {
            if (conscryptChecked) return
            try {
                val provider = Class.forName("org.conscrypt.OpenSSLProvider")
                    .getDeclaredConstructor().newInstance() as java.security.Provider
                javax.net.ssl.SSLContext.getInstance("TLSv1.3", provider)
            } catch (t: Throwable) {
                throw AdbException.TlsProviderUnavailable(t)
            }
            conscryptChecked = true
        }
    }
}

private class LibAdbTransport(private val connection: AdbConnection) : AdbTransport {
    override val isConnected: Boolean
        get() = connection.isConnected && connection.isConnectionEstablished

    override fun open(destination: String): AdbChannel = LibAdbChannel(connection.open(destination))

    override fun close() {
        connection.close()
    }
}

private class LibAdbChannel(private val stream: AdbStream) : AdbChannel {
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        // No isClosed check up front: the peer often sends its last data (the shell's exit packet)
        // and closes at once, and libadb still hands out what it queued before saying "closed".
        return try {
            stream.read(buffer, offset, length)
        } catch (e: IOException) {
            // libadb throws "Stream closed" once the stream is closed and its queue is drained; that is EOF.
            if (stream.isClosed) -1 else throw e
        }
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        stream.write(buffer, offset, length)
    }

    override fun close() {
        stream.close()
    }
}
