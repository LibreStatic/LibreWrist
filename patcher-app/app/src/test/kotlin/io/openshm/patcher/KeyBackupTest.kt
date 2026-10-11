package io.openshm.patcher

import io.openshm.patcher.data.BackupFiles
import io.openshm.patcher.data.BackupPassword
import io.openshm.patcher.data.FilePrivateBackups
import io.openshm.patcher.data.PrivateBackups
import io.openshm.patcher.data.KeyBackup
import io.openshm.patcher.data.KeyBackupOutcome
import io.openshm.patcher.data.KeyBackupRecord
import io.openshm.patcher.data.PasswordSaveResult
import io.openshm.patcher.data.PasswordSaver
import io.openshm.patcher.data.SigningKeyStore
import io.openshm.patcher.data.keyBackupCredentialId
import io.openshm.patcher.data.keyBackupDigestOf
import io.openshm.patcher.data.keyBackupFileName
import io.openshm.patcher.data.keyBackupStamp
import io.openshm.patcher.ui.screens.build.KeyBackupStatus
import io.openshm.patcher.ui.screens.build.toStatus
import io.openshm.patcher.ui.screens.signingkey.ExportPasswordPolicy
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KeyBackupTest {
    private class FakeRecord(
        override var sha256: String? = null,
        override var backupFile: String? = null,
        override var declinedSha256: String? = null,
        override var backupPrivate: Boolean = false,
        override var privateBackupFile: String? = null,
    ) : KeyBackupRecord

    private class FakeSaver(var result: PasswordSaveResult = PasswordSaveResult.Saved) : PasswordSaver {
        val saved = mutableListOf<Pair<String, String>>()
        var asked = 0
        override suspend fun save(id: String, password: CharArray): PasswordSaveResult {
            asked++
            if (result != PasswordSaveResult.Saved) return result
            saved += id to String(password)
            return PasswordSaveResult.Saved
        }
    }

    private class FakeFiles : BackupFiles {
        val files = mutableMapOf<String, ByteArray>()
        override fun exists(name: String) = name in files
        override fun write(name: String, bytes: ByteArray) {
            check(name !in files) { "never replaces a file" }
            files[name] = bytes.copyOf()
        }
    }

    private class FakePrivates : PrivateBackups {
        val passwords = mutableMapOf<String, String>()
        val files = mutableMapOf<String, ByteArray>()
        override fun exists(name: String) = name in files
        override fun write(name: String, password: CharArray, p12: ByteArray) {
            passwords[name] = String(password)
            files[name] = p12.copyOf()
        }
        override fun readPassword(name: String) = passwords[name]?.toCharArray()
        override fun readFile(name: String) = files[name]?.copyOf()
    }

    private val privates = FakePrivates()
    private lateinit var dir: File
    private val wrap = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val record = FakeRecord()
    private val saver = FakeSaver()
    private val files = FakeFiles()
    private lateinit var store: SigningKeyStore
    private var lastPassword: CharArray? = null

    @Before fun setUp() {
        dir = Files.createTempDirectory("key-backup-test").toFile()
        store = SigningKeyStore(dir) { wrap }
        store.load()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private var now = 1_700_000_000_000L
    private fun backup() = KeyBackup(store, record, saver, files, privates, Dispatchers.Unconfined, { BackupPassword.generate().also { lastPassword = it } }, { now })
    private fun run(force: Boolean = false): KeyBackupOutcome = runBlocking { backup().run(force) }
    private fun stamp() = keyBackupStamp(now)

    @Test fun noKeyMeansNothingToDo() {
        dir.deleteRecursively()
        assertEquals(KeyBackupOutcome.NoKey, run())
        assertTrue(files.files.isEmpty())
        assertTrue(saver.saved.isEmpty())
    }

    @Test fun dueBackupSavesPasswordThenWritesAFileOpenableWithIt() {
        val sha = store.certificateSha256()
        assertEquals(KeyBackupOutcome.Saved, run())
        val (id, password) = saver.saved.single()
        assertEquals(keyBackupCredentialId(sha, stamp()), id)
        val bytes = files.files.getValue(keyBackupFileName(sha, stamp()))
        assertEquals(keyBackupFileName(sha, stamp()), record.backupFile)
        // The saved password really opens the written file.
        assertEquals(sha, SigningKeyStore(File(dir, "x").also { it.mkdirs() }) { wrap }.inspectPkcs12(bytes, password.toCharArray()))
        assertEquals(sha, record.sha256)
    }

    @Test fun passwordIsWipedAfterwards() {
        run()
        assertTrue(lastPassword!!.all { it == '\u0000' })
    }

    @Test fun cancelledPasswordManagerWritesNoFileAndRecordsNothing() {
        saver.result = PasswordSaveResult.Unavailable
        assertEquals(KeyBackupOutcome.SavedPrivately, run())
        assertTrue(files.files.isEmpty())
        assertNull(record.sha256)
        assertTrue(lastPassword!!.all { it == '\u0000' })
    }

    @Test fun recordedAndPresentIsANoOp() {
        assertEquals(KeyBackupOutcome.Saved, run())
        assertEquals(KeyBackupOutcome.AlreadyBackedUp, run())
        assertEquals(1, saver.saved.size)
    }

    @Test fun recordedButMissingFileExportsAgainAsANewPairWithANewId() {
        assertEquals(KeyBackupOutcome.Saved, run())
        files.files.clear()
        now += 5_000
        assertEquals(KeyBackupOutcome.Saved, run())
        assertEquals(2, saver.saved.size)
        assertTrue(saver.saved[0].first != saver.saved[1].first)
        assertTrue(saver.saved[0].second != saver.saved[1].second)
        assertEquals(1, files.files.size)
        assertEquals(record.backupFile, files.files.keys.single())
    }

    @Test fun anExistingCredentialAndFileAreNeverReplaced() {
        val sha = store.certificateSha256()
        assertEquals(KeyBackupOutcome.Saved, run())
        val firstFile = keyBackupFileName(sha, stamp())
        val firstBytes = files.files.getValue(firstFile).copyOf()
        // The record forgot the file (for instance after a reinstall), but the old file still exists.
        record.backupFile = null
        now += 7_000
        assertEquals(KeyBackupOutcome.Saved, run())
        assertEquals(2, files.files.size)
        assertTrue(firstBytes.contentEquals(files.files.getValue(firstFile)))
        assertEquals(2, saver.saved.map { it.first }.toSet().size)
        assertEquals(keyBackupCredentialId(sha, keyBackupStamp(now - 7_000)), saver.saved[0].first)
    }

    @Test fun stampIsUtcAndSortable() {
        assertEquals("20231114-221320", keyBackupStamp(1_700_000_000_000L))
    }

    @Test fun declinedPromptIsRememberedAndNotAskedAgainUntilForced() {
        saver.result = PasswordSaveResult.Declined
        assertEquals(KeyBackupOutcome.SavedPrivately, run())
        assertEquals(store.certificateSha256(), record.declinedSha256)
        assertTrue(files.files.isEmpty())
        assertEquals(KeyBackupOutcome.SavedPrivately, run())
        assertEquals(1, saver.asked)
        assertEquals(1, privates.files.size)
        saver.result = PasswordSaveResult.Saved
        assertEquals(KeyBackupOutcome.Saved, run(force = true))
        assertNull(record.declinedSha256)
        assertEquals(1, files.files.size)
    }

    @Test fun otherFailuresAreRetriedAutomatically() {
        saver.result = PasswordSaveResult.Unavailable
        assertEquals(KeyBackupOutcome.SavedPrivately, run())
        assertNull(record.declinedSha256)
        assertEquals(KeyBackupOutcome.SavedPrivately, run())
        // Asked again, without piling up private copies.
        assertEquals(2, saver.asked)
        assertEquals(1, privates.files.size)
    }

    @Test fun declineKeepsAPrivateCopyOpenableWithItsPasswordAndWritesNothingPublic() {
        saver.result = PasswordSaveResult.Declined
        assertEquals(KeyBackupOutcome.SavedPrivately, run())
        assertTrue(files.files.isEmpty())
        assertTrue(saver.saved.isEmpty())
        val name = privates.files.keys.single()
        assertEquals(keyBackupFileName(store.certificateSha256(), stamp()), name)
        assertEquals(name, record.privateBackupFile)
        assertEquals(name, record.backupFile)
        assertTrue(record.backupPrivate)
        assertEquals(store.certificateSha256(), SigningKeyStore(File(dir, "x").also { it.mkdirs() }) { wrap }.inspectPkcs12(privates.files.getValue(name), privates.passwords.getValue(name).toCharArray()))
        assertTrue(lastPassword!!.all { it == '\u0000' })
    }

    @Test fun savingPubliclyLaterKeepsThePrivateCopy() {
        saver.result = PasswordSaveResult.Declined
        run()
        saver.result = PasswordSaveResult.Saved
        now += 3_000
        assertEquals(KeyBackupOutcome.Saved, run(force = true))
        assertEquals(1, privates.files.size)
        assertEquals(1, files.files.size)
        assertFalse(record.backupPrivate)
        assertEquals(KeyBackupOutcome.AlreadyBackedUp, run())
    }

    @Test fun privateStoreEncryptsThePasswordAtRest() {
        val d = File(dir, "private").also { it.mkdirs() }
        val store2 = FilePrivateBackups(d) { wrap }
        val name = "openshm-signing-key-abcdef01-20240102-030405.p12"
        store2.write(name, "Secret-password_123".toCharArray(), byteArrayOf(1, 2, 3))
        assertTrue(store2.exists(name))
        assertEquals("Secret-password_123", String(store2.readPassword(name)!!))
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(store2.readFile(name)))
        assertFalse(String(File(d, "$name.pw").readBytes(), Charsets.ISO_8859_1).contains("Secret-password"))
        // A different wrapping key cannot read it.
        val other = FilePrivateBackups(d) { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        assertNull(other.readPassword(name))
        // Names outside the feature's pattern are refused.
        assertFalse(store2.exists("../x.p12"))
    }

    @Test fun aDeclineOfAnotherKeyDoesNotBlockThisOne() {
        record.declinedSha256 = "00".repeat(32)
        assertEquals(KeyBackupOutcome.Saved, run())
    }

    @Test fun oldStamplessFileDoesNotCountAsTheBackup() {
        files.files["openshm-signing-key-${store.certificateSha256().take(8)}.p12"] = byteArrayOf(1)
        assertEquals(KeyBackupOutcome.Saved, run())
        assertEquals(2, files.files.size)
    }

    @Test fun failedWriteIsNotRecorded() {
        val failing = object : BackupFiles {
            override fun exists(name: String) = false
            override fun write(name: String, bytes: ByteArray) = throw java.io.IOException("full")
        }
        assertEquals(KeyBackupOutcome.Failed, runBlocking { KeyBackup(store, record, saver, failing, privates, Dispatchers.Unconfined).run() })
        assertNull(record.sha256)
    }

    @Test fun generatedPasswordsMeetThePolicyAndDiffer() {
        val a = BackupPassword.generate()
        val b = BackupPassword.generate()
        assertTrue(a.size >= 24)
        assertNull(ExportPasswordPolicy.problem(a, a.copyOf()))
        assertFalse(a.contentEquals(b))
        assertTrue(a.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test fun namesUseTheFirstEightHexDigits() {
        val sha = "ABCDEF0123456789" + "0".repeat(48)
        assertEquals("openshm-signing-key-abcdef01-20240102-030405.p12", keyBackupFileName(sha, "20240102-030405"))
        assertEquals("OpenSHM signing key abcdef01 \u00B7 20240102-030405", keyBackupCredentialId(sha, "20240102-030405"))
        assertEquals("abcdef01", keyBackupDigestOf(keyBackupFileName(sha, "20240102-030405")))
        assertEquals("abcdef01", keyBackupDigestOf(keyBackupCredentialId(sha, "20240102-030405")))
        // Older names without a stamp still parse.
        assertEquals("abcdef01", keyBackupDigestOf("openshm-signing-key-abcdef01.p12"))
        assertEquals("abcdef01", keyBackupDigestOf("OpenSHM signing key abcdef01"))
        assertNull(keyBackupDigestOf("something else"))
    }

    @Test fun outcomesMapToCardStates() {
        assertEquals(KeyBackupStatus.Hidden, KeyBackupOutcome.NoKey.toStatus())
        assertEquals(KeyBackupStatus.Saved, KeyBackupOutcome.AlreadyBackedUp.toStatus())
        assertEquals(KeyBackupStatus.Saved, KeyBackupOutcome.Saved.toStatus())
        assertEquals(KeyBackupStatus.PrivateOnly, KeyBackupOutcome.SavedPrivately.toStatus())
        assertEquals(KeyBackupStatus.Declined, KeyBackupOutcome.PasswordDeclined.toStatus())
        assertEquals(KeyBackupStatus.Failed, KeyBackupOutcome.PasswordNotSaved.toStatus())
        assertEquals(KeyBackupStatus.Failed, KeyBackupOutcome.Failed.toStatus())
    }
}
