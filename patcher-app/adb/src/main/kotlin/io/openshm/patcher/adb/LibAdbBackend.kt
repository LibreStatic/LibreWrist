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
    private val deviceName: String = "OpenSHM Patcher",
    private val peerApi: Int = Build.VERSION.SDK_INT,
) : AdbBackend {

    override fun pair(host: String, port: Int, code: String) {
        PairingConnectionCtx(
            host,
            port,
            code.toByteArray(Charsets.UTF_8),
            keys.privateKey(),
            keys.certificate(),
            deviceName,
        ).use { it.start() }
    }

    override fun connect(host: String, port: Int, timeoutMillis: Long): AdbTransport {
        val connection = AdbConnection.create(host, port, keys.privateKey(), keys.certificate(), peerApi)
        connection.setDeviceName(deviceName)
        val established = try {
            connection.connect(timeoutMillis, TimeUnit.MILLISECONDS, false)
        } catch (t: Throwable) {
            runCatching { connection.close() }
            throw t
        }
        if (!established) {
            runCatching { connection.close() }
            throw AdbException.Timeout("waiting for the ADB daemon to accept the connection")
        }
        return LibAdbTransport(connection)
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
        if (stream.isClosed) return -1
        return try {
            stream.read(buffer, offset, length)
        } catch (e: IOException) {
            // libadb throws "Stream closed" when the peer closes between its checks; that is EOF.
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
