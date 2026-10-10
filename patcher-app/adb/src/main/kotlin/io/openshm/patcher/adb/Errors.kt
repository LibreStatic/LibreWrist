package io.openshm.patcher.adb

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Why a pairing attempt failed. */
enum class PairingFailure {
    /** The code is not exactly six digits; nothing was sent. */
    INVALID_CODE_FORMAT,

    /** The device rejected the exchange, which is what a wrong code looks like on the wire. */
    WRONG_CODE,
    TIMEOUT,
    NETWORK,
    UNKNOWN,
}

/** Typed failures of the ADB client. All of them are [IOException]s. */
sealed class AdbException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class Pairing(val reason: PairingFailure, cause: Throwable? = null) :
        AdbException("Pairing failed: $reason", cause)

    /** The device does not know our key: pair first. */
    class PairingRequired(cause: Throwable? = null) :
        AdbException("The device has not paired with this app", cause)

    /** The device refused our key (for example the user denied the prompt). */
    class AuthenticationRejected(cause: Throwable? = null) :
        AdbException("The device rejected the ADB key", cause)

    class ConnectionFailed(cause: Throwable? = null) :
        AdbException("Could not connect to the ADB daemon", cause)

    class Timeout(what: String) : AdbException("Timed out: $what")

    class NotConnected : AdbException("Not connected to an ADB daemon")

    /** The daemon answered in a way the client cannot interpret. */
    class Protocol(message: String) : AdbException(message)

    /** A shell command ran but did not succeed. */
    class CommandFailed(val command: String, val result: ShellResult) :
        AdbException("Command failed (exit ${result.exitCode}): ${result.stderr.ifBlank { result.stdout }.trim().take(300)}")

    /** The ADB key was reset after TLS used it in this process; libadb caches it until restart. */
    class RestartRequired :
        AdbException("The ADB key was reset; restart the app before pairing or connecting again")

    /** Our Conscrypt provider cannot be used, so TLS 1.3 would silently fall back to the platform. */
    class TlsProviderUnavailable(cause: Throwable? = null) :
        AdbException("The bundled Conscrypt TLS provider is not available", cause)

    class Discovery(message: String) : AdbException(message)

    class Notifications(message: String) : AdbException(message)
}

internal object AdbErrors {
    private val wrongCodeMessages = listOf(
        "Exchanging message wasn't successful",
        "Could not exchange peer info",
        "Connection closed with errors",
    )

    private fun chain(t: Throwable): Sequence<Throwable> =
        generateSequence(t) { it.cause?.takeIf { c -> c !== it } }.take(8)

    /** Maps whatever libadb threw during pairing to a typed error. */
    fun mapPairing(t: Throwable): AdbException {
        if (t is AdbException) return t
        val all = chain(t).toList()
        val reason = when {
            all.any { it is SocketTimeoutException } -> PairingFailure.TIMEOUT
            all.any { e -> wrongCodeMessages.any { e.message?.contains(it) == true } } -> PairingFailure.WRONG_CODE
            all.any { isNetwork(it) } -> PairingFailure.NETWORK
            else -> PairingFailure.UNKNOWN
        }
        return AdbException.Pairing(reason, t)
    }

    /** Maps whatever libadb threw while connecting to a typed error. */
    fun mapConnect(t: Throwable): AdbException {
        if (t is AdbException) return t
        val all = chain(t).toList()
        return when {
            all.any { it.javaClass.simpleName == "AdbPairingRequiredException" } -> AdbException.PairingRequired(t)
            all.any { it.javaClass.simpleName == "AdbAuthenticationFailedException" } ->
                AdbException.AuthenticationRejected(t)
            all.any { it is SocketTimeoutException } -> AdbException.Timeout("connecting to the ADB daemon")
            else -> AdbException.ConnectionFailed(t)
        }
    }

    /** True when the cause chain is a refused TCP connection (nothing listens on the port). */
    fun isRefused(t: Throwable): Boolean = chain(t).any { it is ConnectException }

    private fun isNetwork(t: Throwable) =
        t is ConnectException || t is NoRouteToHostException || t is UnknownHostException ||
            t is SocketException || t is SSLException
}
