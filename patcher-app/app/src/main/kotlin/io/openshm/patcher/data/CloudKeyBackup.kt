package io.openshm.patcher.data

import android.content.Context
import com.google.android.gms.auth.blockstore.Blockstore
import com.google.android.gms.auth.blockstore.DeleteBytesRequest
import com.google.android.gms.auth.blockstore.RetrieveBytesRequest
import com.google.android.gms.auth.blockstore.StoreBytesData
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.tasks.Task
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Where a small set of named byte entries can be kept, optionally in the user's Google account. */
interface KeyVault {
    /** False when the vault cannot be used at all (no Google Play services). */
    suspend fun available(): Boolean

    /** True when entries stored with `cloud = true` are end-to-end encrypted (needs a screen lock). */
    suspend fun endToEndEncrypted(): Boolean

    /** Stores [bytes] under [key]; [bytes] stays owned by the caller. */
    suspend fun store(key: String, bytes: ByteArray, cloud: Boolean)

    /** The entries that exist among [keys]. The caller wipes the arrays. */
    suspend fun retrieve(keys: List<String>): Map<String, ByteArray>

    suspend fun delete(keys: List<String>)
}

/** The signing key as it comes back from the vault; the caller calls [wipe]. */
class CloudKey(val p12: ByteArray, val password: CharArray, val certificateSha256: String) {
    fun wipe() {
        p12.fill(0)
        password.fill('\u0000')
    }
}

sealed interface CloudSaveResult {
    data object NoKey : CloudSaveResult
    data object AlreadySaved : CloudSaveResult
    /** [cloud] is false when the copy stays on this phone (no screen lock, so no end-to-end encryption). */
    data class Saved(val cloud: Boolean) : CloudSaveResult
    /** The account holds a different key this app did not upload; it is left alone unless the caller asks to replace it. */
    data object Differs : CloudSaveResult
    data object Unavailable : CloudSaveResult
    data object Failed : CloudSaveResult
}

sealed interface CloudRestoreResult {
    data object KeyExists : CloudRestoreResult
    data class Restored(val sha256: String) : CloudRestoreResult
    data object NotFound : CloudRestoreResult
    data object Unavailable : CloudRestoreResult
    data object Failed : CloudRestoreResult
}

/**
 * An optional extra copy of the signing key in the Google account (Block Store). Everything here is
 * best effort: when the vault is missing or fails, callers carry on with the normal file backup.
 *
 * Payload (a fresh random password plus the PKCS#12 that password protects):
 * `[int32 BE password length][password, US-ASCII][PKCS#12 bytes]`. It is cut into chunks of at most
 * [CHUNK_BYTES] bytes stored under `openshm.signing-key.0..N-1`, because Block Store limits an entry to
 * 1024 bytes and an app to 16 entries.
 *
 * The meta entry `openshm.signing-key.meta` is written last, as the commit:
 * `[byte version = 1][int32 BE chunk count][int32 BE payload length][32 bytes SHA-256 of the payload]`
 * `[certificate SHA-256 as 64 lowercase hex chars, US-ASCII]`. A reader that finds a missing chunk or a
 * payload hash that does not match treats the whole copy as absent.
 *
 * Security notes: the password and the exported bytes live only in arrays that are overwritten in a
 * `finally`. Nothing is logged and no failure text is kept.
 */
class CloudKeyBackup(
    private val store: SigningKeyStore,
    private val record: KeyBackupRecord,
    private val vault: KeyVault,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val newPassword: () -> CharArray = { BackupPassword.generate() },
) {
    /**
     * [replaceForeign] is the user having chosen this key (an import): it then replaces whatever the account
     * holds. Otherwise a stored key that is neither this one nor this app's last upload is not overwritten.
     */
    suspend fun save(replaceForeign: Boolean = false): CloudSaveResult {
        val sha = try {
            withContext(io) { if (store.exists()) store.certificateSha256() else null }
        } catch (e: IOException) {
            return CloudSaveResult.Failed
        } catch (e: GeneralSecurityException) {
            return CloudSaveResult.Failed
        } ?: return CloudSaveResult.NoKey
        if (record.cloudSha256 == sha) return CloudSaveResult.AlreadySaved
        if (!guarded { vault.available() }.orFalse()) return CloudSaveResult.Unavailable
        if (!replaceForeign) {
            val stored = storedCertificateSha256()
            if (stored != null && stored != sha && stored != record.cloudSha256) return CloudSaveResult.Differs
        }

        val password = newPassword()
        var p12: ByteArray? = null
        var payload: ByteArray? = null
        try {
            p12 = try {
                withContext(io) { store.exportPkcs12(password) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return CloudSaveResult.Failed
            }
            payload = buildPayload(password, p12)
            val chunkCount = (payload.size + CHUNK_BYTES - 1) / CHUNK_BYTES
            if (chunkCount > MAX_CHUNKS) return CloudSaveResult.Failed
            val meta = buildMeta(chunkCount, payload, sha)
            val cloud = guarded { vault.endToEndEncrypted() }.orFalse()
            val stored = guarded {
                // An older commit goes first, so a reader never sees new chunks under an old meta.
                runCatching { vault.delete(listOf(META_KEY)) }.onFailure { if (it is CancellationException) throw it }
                for (i in 0 until chunkCount) {
                    val chunk = payload.copyOfRange(i * CHUNK_BYTES, minOf(payload.size, (i + 1) * CHUNK_BYTES))
                    try {
                        vault.store(chunkKey(i), chunk, cloud)
                    } finally {
                        chunk.fill(0)
                    }
                }
                vault.store(META_KEY, meta, cloud)
                true
            }
            if (stored == null) return CloudSaveResult.Unavailable
            // Chunks left over from a longer earlier payload; best effort.
            guarded { vault.delete((chunkCount until MAX_CHUNKS).map(::chunkKey)) }
            if (cloud) record.cloudSha256 = sha
            return CloudSaveResult.Saved(cloud)
        } finally {
            password.fill('\u0000')
            p12?.fill(0)
            payload?.fill(0)
        }
    }

    /** The stored key, or null when there is none or the stored data is incomplete or inconsistent. */
    suspend fun load(): CloudKey? {
        if (!guarded { vault.available() }.orFalse()) return null
        val read = guarded { vault.retrieve(listOf(META_KEY)) } ?: return null
        val metaBytes = read[META_KEY]
        var payload: ByteArray? = null
        try {
            val meta = parseMeta(metaBytes ?: return null) ?: return null
            val chunks = guarded { vault.retrieve((0 until meta.chunkCount).map(::chunkKey)) } ?: return null
            try {
                payload = ByteArray(meta.payloadLength)
                var offset = 0
                for (i in 0 until meta.chunkCount) {
                    val chunk = chunks[chunkKey(i)] ?: return null
                    if (chunk.size > CHUNK_BYTES || offset + chunk.size > payload.size) return null
                    chunk.copyInto(payload, offset)
                    offset += chunk.size
                }
                if (offset != payload.size) return null
            } finally {
                chunks.values.forEach { it.fill(0) }
            }
            if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(payload), meta.payloadSha256)) return null
            return parsePayload(payload, meta.certificateSha256)
        } finally {
            metaBytes?.fill(0)
            payload?.fill(0)
        }
    }

    /** The certificate digest of the stored key, or null when none can be read. */
    suspend fun storedCertificateSha256(): String? {
        val key = load() ?: return null
        key.wipe()
        return key.certificateSha256
    }

    /** Brings the key back from the vault, only when this app has no key yet. */
    suspend fun restoreIfNoKey(): CloudRestoreResult {
        try {
            if (withContext(io) { store.exists() }) return CloudRestoreResult.KeyExists
        } catch (e: IOException) {
            return CloudRestoreResult.Failed
        }
        if (!guarded { vault.available() }.orFalse()) return CloudRestoreResult.Unavailable
        val key = load() ?: return CloudRestoreResult.NotFound
        try {
            return try {
                withContext(io) {
                    // The digest in the meta must be the one of the key inside, or the copy is not trusted.
                    if (store.inspectPkcs12(key.p12, key.password) != key.certificateSha256) return@withContext CloudRestoreResult.Failed
                    store.importPkcs12(ByteArrayInputStream(key.p12), key.password)
                    if (store.certificateSha256() != key.certificateSha256) return@withContext CloudRestoreResult.Failed
                    record.sha256 = key.certificateSha256
                    record.cloudSha256 = key.certificateSha256
                    CloudRestoreResult.Restored(key.certificateSha256)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CloudRestoreResult.Failed
            }
        } finally {
            key.wipe()
        }
    }

    /**
     * Runs a vault call; any failure short of cancellation (a missing class included) means "not available",
     * and so does Play services not answering within [VAULT_TIMEOUT_MS].
     */
    private suspend fun <T : Any> guarded(block: suspend () -> T): T? =
        try {
            withTimeoutOrNull(VAULT_TIMEOUT_MS) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }

    private fun Boolean?.orFalse() = this == true

    private class Meta(val chunkCount: Int, val payloadLength: Int, val payloadSha256: ByteArray, val certificateSha256: String)

    private fun buildPayload(password: CharArray, p12: ByteArray): ByteArray {
        val ascii = Charsets.US_ASCII.encode(java.nio.CharBuffer.wrap(password))
        val pw = ByteArray(ascii.remaining()).also { ascii.get(it) }
        if (ascii.hasArray()) ascii.array().fill(0)
        try {
            return ByteBuffer.allocate(4 + pw.size + p12.size).putInt(pw.size).put(pw).put(p12).array()
        } finally {
            pw.fill(0)
        }
    }

    private fun buildMeta(chunkCount: Int, payload: ByteArray, certSha: String): ByteArray {
        val hex = certSha.lowercase().toByteArray(Charsets.US_ASCII)
        return ByteBuffer.allocate(1 + 4 + 4 + 32 + hex.size)
            .put(VERSION).putInt(chunkCount).putInt(payload.size)
            .put(MessageDigest.getInstance("SHA-256").digest(payload)).put(hex).array()
    }

    private fun parseMeta(bytes: ByteArray): Meta? {
        if (bytes.size != 1 + 4 + 4 + 32 + 64) return null
        val b = ByteBuffer.wrap(bytes)
        if (b.get() != VERSION) return null
        val count = b.getInt()
        val length = b.getInt()
        if (count !in 1..MAX_CHUNKS || length !in 1..count * CHUNK_BYTES) return null
        val hash = ByteArray(32).also { b.get(it) }
        val hex = ByteArray(64).also { b.get(it) }
        val cert = String(hex, Charsets.US_ASCII)
        if (!cert.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return Meta(count, length, hash, cert)
    }

    private fun parsePayload(payload: ByteArray, certSha: String): CloudKey? {
        if (payload.size < 4) return null
        val b = ByteBuffer.wrap(payload)
        val pwLength = b.getInt()
        if (pwLength !in 1..MAX_PASSWORD_BYTES || pwLength > payload.size - 4 - 1) return null
        val pw = ByteArray(pwLength).also { b.get(it) }
        val p12 = ByteArray(b.remaining()).also { b.get(it) }
        val chars = CharArray(pwLength) { (pw[it].toInt() and 0x7f).toChar() }
        pw.fill(0)
        return CloudKey(p12, chars, certSha)
    }

    companion object {
        /** Block Store's limit for one entry. */
        const val CHUNK_BYTES = 1024
        /** Block Store keeps 16 entries per app: the meta entry and at most 15 chunks. */
        const val MAX_CHUNKS = 15
        const val META_KEY = "openshm.signing-key.meta"
        private const val MAX_PASSWORD_BYTES = 256
        private const val VERSION: Byte = 1
        private const val VAULT_TIMEOUT_MS = 20_000L

        fun chunkKey(index: Int) = "openshm.signing-key.$index"
    }
}

/** [KeyVault] on Google Play services Block Store. Every call may throw; [CloudKeyBackup] treats that as "unavailable". */
class BlockstoreKeyVault(context: Context) : KeyVault {
    private val app = context.applicationContext
    private val client by lazy { Blockstore.getClient(app) }

    override suspend fun available(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app) == ConnectionResult.SUCCESS

    override suspend fun endToEndEncrypted(): Boolean = client.isEndToEndEncryptionAvailable().await()

    override suspend fun store(key: String, bytes: ByteArray, cloud: Boolean) {
        client.storeBytes(StoreBytesData.Builder().setBytes(bytes).setKey(key).setShouldBackupToCloud(cloud).build()).await()
    }

    override suspend fun retrieve(keys: List<String>): Map<String, ByteArray> {
        val response = client.retrieveBytes(RetrieveBytesRequest.Builder().setKeys(keys).build()).await()
        return response.blockstoreDataMap.mapValues { it.value.bytes }
    }

    override suspend fun delete(keys: List<String>) {
        client.deleteBytes(DeleteBytesRequest.Builder().setKeys(keys).build()).await()
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
        addOnSuccessListener { c.resume(it) }
        addOnFailureListener { c.resumeWithException(it) }
        addOnCanceledListener { c.cancel() }
    }
}

/** The Block Store backed [CloudKeyBackup] for the app's key. */
fun cloudKeyBackup(context: Context, record: KeyBackupRecord = PrefsExportedCertRecord(context)): CloudKeyBackup =
    CloudKeyBackup(signingKeyStore(context), record, BlockstoreKeyVault(context))
