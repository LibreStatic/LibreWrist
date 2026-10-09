package io.openshm.patcher.adb

import kotlinx.coroutines.CancellationException

/** Pairs this app with a device's Wireless debugging pairing service. */
class AdbPairing(
    private val backend: AdbBackend,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
) {
    /**
     * Pairs with [host]:[port] using the six-digit [code] shown by the device.
     *
     * @throws AdbException.Pairing with a [PairingFailure] reason on any failure.
     */
    suspend fun pair(host: String, port: Int, code: String) {
        if (!isValidCode(code)) throw AdbException.Pairing(PairingFailure.INVALID_CODE_FORMAT)
        try {
            val abort = AbortScope()
            blockingIo(timeoutMillis, "pairing with $host:$port", onAbort = abort::close) {
                backend.pair(host, port, code, abort)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdbException.Timeout) {
            throw AdbException.Pairing(PairingFailure.TIMEOUT, e)
        } catch (t: Throwable) {
            throw AdbErrors.mapPairing(t)
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 20_000L
        private val CODE = Regex("[0-9]{6}")

        fun isValidCode(code: String): Boolean = CODE.matches(code)
    }
}
