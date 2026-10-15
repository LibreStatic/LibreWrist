package io.openshm.patcher.data

import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Where the automatic backup goes, relative to the shared storage root. */
const val KEY_BACKUP_FOLDER = "Documents/OpenSHM"

/** The first eight hex digits of the certificate digest: enough to tell keys apart, public by nature. */
fun keyBackupShortDigest(sha256: String): String = sha256.take(8).lowercase()

/** A UTC `yyyyMMdd-HHmmss` stamp that makes every backup's file name and credential id unique. */
fun keyBackupStamp(epochMillis: Long): String =
    java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        .withZone(java.time.ZoneOffset.UTC)
        .format(java.time.Instant.ofEpochMilli(epochMillis))

fun keyBackupFileName(sha256: String, stamp: String): String = "openshm-signing-key-${keyBackupShortDigest(sha256)}-$stamp.p12"

/** The label the password manager shows and the key the credential is saved under. */
fun keyBackupCredentialId(sha256: String, stamp: String): String = "OpenSHM signing key ${keyBackupShortDigest(sha256)} \u00B7 $stamp"

/** The short digest a credential id or backup file name carries (stamp-less older names included), or null for any other text. */
fun keyBackupDigestOf(idOrFileName: String): String? =
    Regex("""OpenSHM signing key ([0-9a-f]{8})(?: \u00B7 \d{8}-\d{6})?|openshm-signing-key-([0-9a-f]{8})(?:-\d{8}-\d{6})?\.p12""")
        .matchEntire(idOrFileName)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }

/** The backup file a credential id belongs to (stamp-less older ids included), or null for any other text, a file name too. */
fun keyBackupFileNameOf(credentialId: String): String? {
    val m = Regex("""OpenSHM signing key ([0-9a-f]{8})(?: \u00B7 (\d{8}-\d{6}))?""").matchEntire(credentialId) ?: return null
    val short = m.groupValues[1]
    val stamp = m.groupValues[2]
    return if (stamp.isEmpty()) "openshm-signing-key-$short.p12" else "openshm-signing-key-$short-$stamp.p12"
}

/** What the password manager did with a save request. */
enum class PasswordSaveResult { Saved, Declined, Unavailable }

/** Saves a password in the system password manager; tells a user's cancel apart from any other failure. */
interface PasswordSaver {
    /** [password] stays owned by the caller, which wipes it. */
    suspend fun save(id: String, password: CharArray): PasswordSaveResult
}

/** The public backup location. */
interface BackupFiles {
    fun exists(name: String): Boolean

    /** Creates the new file [name], so that it never shows up half written. Never replaces or truncates an existing file. */
    fun write(name: String, bytes: ByteArray)

    /** The bytes of the finished file [name] (the caller wipes them); null when it is absent, still pending or not visible to this app. */
    fun read(name: String): ByteArray?
}

/**
 * The app's private copy of a backup (no public file): the PKCS#12 and its generated password, the
 * password encrypted at rest. Cleared together with the app's data, on purpose.
 */
interface PrivateBackups {
    fun exists(name: String): Boolean

    /** Stores [p12] as [name] and [password] encrypted next to it. Both arrays stay owned by the caller, which wipes them. */
    fun write(name: String, password: CharArray, p12: ByteArray)

    /** The stored password as a char array the caller wipes; null when missing or unreadable. */
    fun readPassword(name: String): CharArray?

    /** The stored PKCS#12 bytes (the caller wipes them); null when missing. */
    fun readFile(name: String): ByteArray?
}

/** [PrivateBackups] in [dir], with the password wrapped by AES-256-GCM under the key [wrapKey] returns (AndroidKeyStore in the app). */
class FilePrivateBackups(private val dir: File, private val wrapKey: () -> SecretKey) : PrivateBackups {
    private fun p12(name: String) = File(dir, name)
    private fun pw(name: String) = File(dir, "$name.pw")

    override fun exists(name: String): Boolean = safe(name) && p12(name).isFile && pw(name).isFile

    override fun write(name: String, password: CharArray, p12: ByteArray) {
        require(safe(name)) { "unexpected backup name" }
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("the private backup folder cannot be created")
        val ascii = Charsets.US_ASCII.encode(java.nio.CharBuffer.wrap(password))
        val plain = ByteArray(ascii.remaining()).also { ascii.get(it) }
        try {
            put(p12(name), p12)
            put(pw(name), seal(name, plain))
        } catch (e: Exception) {
            p12(name).delete()
            pw(name).delete()
            throw e
        } finally {
            plain.fill(0)
            if (ascii.hasArray()) ascii.array().fill(0)
        }
    }

    override fun readPassword(name: String): CharArray? {
        if (!exists(name)) return null
        val blob = pw(name).readBytes()
        if (blob.size < IV_BYTES + TAG_BITS / 8) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
            cipher.updateAAD(aad(name))
            val plain = cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
            try {
                String(plain, Charsets.US_ASCII).toCharArray()
            } finally {
                plain.fill(0)
            }
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    override fun readFile(name: String): ByteArray? = if (exists(name)) p12(name).readBytes() else null

    private fun seal(name: String, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrapKey())
        cipher.updateAAD(aad(name))
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun put(target: File, bytes: ByteArray) {
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("the private backup cannot be written")
        }
    }

    private fun aad(name: String) = "openshm-key-backup:$name".toByteArray(Charsets.UTF_8)

    /** Only names this feature builds, so a name never escapes [dir]. */
    private fun safe(name: String) = keyBackupDigestOf(name) != null && name.endsWith(".p12")

    private companion object {
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

enum class KeyBackupOutcome {
    /** There is no signing key yet. */
    NoKey,

    /** The key was already exported and the file is still there. */
    AlreadyBackedUp,

    /** Password saved and file written in this run. */
    Saved,

    /** The user declined the password manager, so the backup is only in the app's private storage. */
    SavedPrivately,

    /** No password manager could take the password (none, or it failed): only the private copy exists, and a typed password is the way to Documents. */
    SavedPrivatelyNoPasswordManager,

    /** The user cancelled the password manager (now or on an earlier visit) and not even the private copy could be written. */
    PasswordDeclined,

    /** The password manager failed or is unavailable and not even the private copy could be written. */
    PasswordNotSaved,

    /** The key or the file could not be handled. */
    Failed,
}

/** Random passwords for the exported key: 32 characters from a 64-symbol alphabet, 192 bits. */
object BackupPassword {
    const val LENGTH = 32
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun generate(random: SecureRandom = SecureRandom()): CharArray {
        val raw = ByteArray(LENGTH)
        try {
            random.nextBytes(raw)
            // 64 symbols divide 256 evenly, so masking keeps the choice uniform.
            return CharArray(LENGTH) { ALPHABET[raw[it].toInt() and 63] }
        } finally {
            raw.fill(0)
        }
    }
}

/**
 * Backs the signing key up without the user doing anything: a random password goes to the system
 * password manager first and only when that worked is the PKCS#12 written to a public folder. When the
 * password manager declines or fails, the same pair is kept in [PrivateBackups] instead (never public).
 *
 * Security notes: the generated password exists as one [CharArray] inside [run], handed to
 * [PasswordSaver] and to [SigningKeyStore.exportPkcs12], and is overwritten in a `finally`. The
 * exported bytes are overwritten after the write. Nothing is logged and no failure text is kept.
 */
class KeyBackup(
    private val store: SigningKeyStore,
    private val record: KeyBackupRecord,
    private val passwords: PasswordSaver,
    private val files: BackupFiles,
    private val privates: PrivateBackups,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val newPassword: () -> CharArray = { BackupPassword.generate() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * [force] is the user asking for the backup: it clears an earlier decline. Without it, a key whose
     * password manager prompt was declined is left alone.
     */
    suspend fun run(force: Boolean = false): KeyBackupOutcome {
        val sha = try {
            withContext(io) { if (store.exists()) store.certificateSha256() else null }
        } catch (e: IOException) {
            return KeyBackupOutcome.Failed
        } catch (e: GeneralSecurityException) {
            return KeyBackupOutcome.Failed
        } ?: return KeyBackupOutcome.NoKey
        if (force) record.declinedSha256 = null
        val short = keyBackupShortDigest(sha)
        val recorded = record.backupFile
        val publicPresent = recorded != null && !record.backupPrivate && keyBackupDigestOf(recorded) == short && attempt { files.exists(recorded) }
        if (record.sha256 == sha && publicPresent) return KeyBackupOutcome.AlreadyBackedUp
        val privateName = record.privateBackupFile?.takeIf { keyBackupDigestOf(it) == short }
        val privatePresent = privateName != null && attempt { privates.exists(privateName) }
        val declined = record.declinedSha256 == sha
        if (declined && privatePresent) return KeyBackupOutcome.SavedPrivately

        // A new pair every time: an older file and the password saved for it are never touched.
        val stamp = keyBackupStamp(clock())
        val name = keyBackupFileName(sha, stamp)
        val password = newPassword()
        try {
            var result = PasswordSaveResult.Declined
            if (!declined) {
                result = try {
                    passwords.save(keyBackupCredentialId(sha, stamp), password)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    PasswordSaveResult.Unavailable
                }
            }
            if (result == PasswordSaveResult.Declined) record.declinedSha256 = sha
            var bytes: ByteArray? = null
            try {
                if (result == PasswordSaveResult.Saved) {
                    return try {
                        withContext(io) {
                            bytes = store.exportPkcs12(password)
                            files.write(name, bytes!!)
                        }
                        record.sha256 = sha
                        record.backupFile = name
                        record.backupPrivate = false
                        KeyBackupOutcome.Saved
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        KeyBackupOutcome.Failed
                    }
                }
                // Declined or unavailable: keep the backup in private storage rather than none. An earlier
                // private copy of this key is not duplicated on a retry.
                val savedPrivately = if (result == PasswordSaveResult.Declined) KeyBackupOutcome.SavedPrivately else KeyBackupOutcome.SavedPrivatelyNoPasswordManager
                val notSaved = if (result == PasswordSaveResult.Declined) KeyBackupOutcome.PasswordDeclined else KeyBackupOutcome.PasswordNotSaved
                if (privatePresent) return savedPrivately
                return try {
                    withContext(io) {
                        bytes = store.exportPkcs12(password)
                        privates.write(name, password, bytes!!)
                    }
                    record.privateBackupFile = name
                    record.backupFile = name
                    record.backupPrivate = true
                    savedPrivately
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    notSaved
                }
            } finally {
                bytes?.fill(0)
            }
        } finally {
            password.fill('\u0000')
        }
    }

    private suspend fun attempt(block: () -> Boolean): Boolean =
        try {
            withContext(io) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
}

/**
 * The backup with a password the user typed: the same stamped PKCS#12 goes to the public folder and the
 * password is not kept anywhere, no credential is created. The caller owns and wipes both arrays.
 */
class ManualKeyBackup(
    private val store: SigningKeyStore,
    private val record: KeyBackupRecord,
    private val files: BackupFiles,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Result { Saved, Refused, NoKey, Failed }

    suspend fun save(password: CharArray, confirm: CharArray): Result {
        if (password.size < MIN_PASSWORD_LENGTH || !password.contentEquals(confirm)) return Result.Refused
        var bytes: ByteArray? = null
        return try {
            withContext(io) {
                if (!store.exists()) return@withContext Result.NoKey
                val sha = store.certificateSha256()
                val name = keyBackupFileName(sha, keyBackupStamp(clock()))
                bytes = store.exportPkcs12(password)
                files.write(name, bytes!!)
                record.sha256 = sha
                record.backupFile = name
                record.backupPrivate = false
                Result.Saved
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Failed
        } finally {
            bytes?.fill(0)
        }
    }

    companion object {
        /** The same rule as the manual export. */
        const val MIN_PASSWORD_LENGTH = 12
    }
}
