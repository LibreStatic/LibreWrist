package io.openshm.patcher.data

import io.openshm.patcher.engine.Catalogue
import io.openshm.patcher.engine.CatalogueException
import io.openshm.patcher.engine.CatalogueVerifier
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Outcome of one check of the catalogue update channel. */
sealed interface UpdateResult {
    /** A newer verified catalogue is now stored; it is used once [AppCatalogue] adopts it. */
    data class Updated(val serial: Long) : UpdateResult
    data object UpToDate : UpdateResult
    /** The download was understood but refused (signature, rollback, app too old, size, insecure link). */
    data class Rejected(val reason: String) : UpdateResult
    /** The network failed; nothing was learned. */
    data class Failed(val reason: String) : UpdateResult
    /** No update URL is configured in this build. */
    data object Disabled : UpdateResult
}

/** Small HTTP port so the updater is testable without a network. Throws [IOException] on failure. */
fun interface CatalogueFetcher {
    fun get(url: String, maxBytes: Int): ByteArray
}

/** A response is larger than allowed, or a link is not https. Both are refusals, not network faults. */
class RefusedResponseException(message: String) : IOException(message)

/** `HttpURLConnection` fetcher: https only, also across redirects, with timeouts and a size cap. */
class HttpsCatalogueFetcher : CatalogueFetcher {
    override fun get(url: String, maxBytes: Int): ByteArray {
        var current = URL(url)
        requireHttps(current)
        repeat(MAX_REDIRECTS + 1) {
            val connection = current.openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.useCaches = false
                when (val code = connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> return readCapped(connection, maxBytes)
                    301, 302, 303, 307, 308 -> current = nextUrl(current, connection.getHeaderField("Location"))
                    else -> throw IOException("HTTP $code")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("too many redirects")
    }

    private fun readCapped(connection: HttpURLConnection, maxBytes: Int): ByteArray {
        if (connection.contentLengthLong > maxBytes) throw RefusedResponseException("response larger than $maxBytes bytes")
        connection.inputStream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > maxBytes) throw RefusedResponseException("response larger than $maxBytes bytes")
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
    }

    internal companion object {
        const val TIMEOUT_MS = 15_000
        const val MAX_REDIRECTS = 3

        fun requireHttps(url: URL) {
            if (!url.protocol.equals("https", ignoreCase = true)) throw RefusedResponseException("not an https link: ${url.protocol}")
        }

        /** The redirect target resolved against [current]; refused unless it is https. */
        fun nextUrl(current: URL, location: String?): URL {
            if (location.isNullOrBlank()) throw IOException("redirect without a Location")
            val next = URL(URI(current.toString()).resolve(location).toString())
            requireHttps(next)
            return next
        }
    }
}

/** What the updater remembers between runs. */
interface UpdateState {
    /** The highest catalogue serial ever accepted (0 when never set). */
    var highestSerial: Long
}

/** The downloaded catalogue on disk: the verified JSON and its signature. Plain JVM code. */
class StoredCatalogue(private val dir: File) {
    class Stored(val json: ByteArray, val signature: ByteArray)

    private val jsonFile get() = File(dir, JSON)
    private val sigFile get() = File(dir, SIG)

    fun read(): Stored? = try {
        Stored(jsonFile.readBytes(), sigFile.readBytes())
    } catch (e: IOException) {
        null
    }

    /** Writes both files through temp files and renames; a crash in between leaves a pair that fails verification. */
    fun write(json: ByteArray, signature: ByteArray) {
        dir.mkdirs()
        replace(jsonFile, json)
        replace(sigFile, signature)
    }

    fun delete() {
        jsonFile.delete()
        sigFile.delete()
    }

    private fun replace(target: File, bytes: ByteArray) {
        val tmp = File(dir, target.name + ".part")
        try {
            java.io.FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()
        }
    }

    private companion object {
        const val JSON = "catalogue.json"
        const val SIG = "catalogue.json.sig"
    }
}

/**
 * Fetches `<baseUrl>/catalogue.json` and `.sig`, verifies them with the BUNDLED public key only (the
 * key is never downloaded), refuses a serial that is not newer than the highest ever accepted and a
 * catalogue that needs a newer app, then stores the verified bytes. Never downloads inputs.
 */
class CatalogueUpdater(
    private val baseUrl: String,
    private val publicKeyPem: String,
    private val appVersionCode: Int,
    private val bundledSerial: Long,
    private val store: StoredCatalogue,
    private val state: UpdateState,
    private val fetcher: CatalogueFetcher,
) {
    fun check(): UpdateResult {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return UpdateResult.Disabled
        if (!base.startsWith("https://", ignoreCase = true)) return UpdateResult.Rejected("the update URL is not https")
        val json: ByteArray
        val signature: ByteArray
        try {
            json = fetcher.get("$base/catalogue.json", MAX_JSON_BYTES)
            signature = fetcher.get("$base/catalogue.json.sig", MAX_SIG_BYTES)
        } catch (e: RefusedResponseException) {
            return UpdateResult.Rejected(e.message ?: "refused")
        } catch (e: IOException) {
            return UpdateResult.Failed(e.message ?: e.javaClass.simpleName)
        }
        if (json.size > MAX_JSON_BYTES || signature.size > MAX_SIG_BYTES) return UpdateResult.Rejected("response too large")
        val catalogue = try {
            Catalogue.parseSigned(json, signature, CatalogueVerifier.parsePublicKey(publicKeyPem))
        } catch (e: CatalogueException) {
            return UpdateResult.Rejected(e.problems.firstOrNull() ?: "invalid catalogue")
        } catch (e: RuntimeException) {
            return UpdateResult.Rejected("invalid catalogue: ${e.message}")
        }
        val highest = maxOf(state.highestSerial, bundledSerial)
        if (catalogue.serial < highest) return UpdateResult.Rejected("serial ${catalogue.serial} is older than $highest")
        // The same serial is the normal answer when nothing changed: not an error and nothing to store,
        // unless the stored copy was lost (deleted at startup as corrupt) and the bundled one is older.
        if (catalogue.serial == highest && (catalogue.serial <= bundledSerial || store.read() != null)) return UpdateResult.UpToDate
        if (catalogue.minAppVersion > appVersionCode) {
            return UpdateResult.Rejected("needs app version ${catalogue.minAppVersion}, this is $appVersionCode")
        }
        try {
            store.write(json, signature)
        } catch (e: IOException) {
            return UpdateResult.Failed(e.message ?: "could not store the catalogue")
        }
        state.highestSerial = catalogue.serial
        return UpdateResult.Updated(catalogue.serial)
    }

    companion object {
        const val MAX_JSON_BYTES = 4 * 1024 * 1024
        const val MAX_SIG_BYTES = 1024
    }
}
