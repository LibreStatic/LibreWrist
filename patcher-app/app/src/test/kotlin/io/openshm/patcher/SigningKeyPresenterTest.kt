package io.openshm.patcher

import io.openshm.patcher.data.BackupFiles
import io.openshm.patcher.data.KeyBackupRecord
import io.openshm.patcher.data.SigningKeyStore
import io.openshm.patcher.ui.screens.signingkey.ExportPasswordPolicy
import io.openshm.patcher.ui.screens.signingkey.SigningKeyEvent
import io.openshm.patcher.ui.screens.signingkey.SigningKeyForm
import io.openshm.patcher.ui.screens.signingkey.SigningKeyPresenter
import io.openshm.patcher.ui.screens.signingkey.SigningKeyRequest
import io.openshm.patcher.ui.screens.signingkey.formatDigest
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.KeyStore
import javax.crypto.KeyGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SigningKeyPresenterTest {
    private class FakeRecord(
        override var sha256: String? = null,
        override var backupFile: String? = null,
        override var declinedSha256: String? = null,
        override var cloudSha256: String? = null,
        override var backupPrivate: Boolean = false,
        override var privateBackupFile: String? = null,
    ) : KeyBackupRecord

    private lateinit var dir: File
    private lateinit var other: File
    private val wrap = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val record = FakeRecord()
    private val password = "a-long-password".toCharArray()

    @Before fun setUp() {
        dir = Files.createTempDirectory("presenter-test").toFile()
        other = Files.createTempDirectory("presenter-other").toFile()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
        other.deleteRecursively()
    }

    private fun store(root: File = dir) = SigningKeyStore(root) { wrap }
    private fun presenter(store: SigningKeyStore = store()) = SigningKeyPresenter(store, record, Dispatchers.Unconfined)
    private fun chars(text: String) = text.toCharArray()

    /** A backup of another identity, as the import flow would receive it. */
    private fun otherBackup(): Pair<ByteArray, String> {
        val s = store(other)
        return s.exportPkcs12(password) to s.certificateSha256()
    }

    @Test fun passwordPolicyNeedsTwelveCharactersThatMatch() {
        assertEquals(R.string.signing_key_error_short, ExportPasswordPolicy.problem(chars("elevenchars"), chars("elevenchars")))
        assertEquals(R.string.signing_key_error_mismatch, ExportPasswordPolicy.problem(chars("twelve-chars"), chars("twelve-charz")))
        assertNull(ExportPasswordPolicy.problem(chars("twelve-chars"), chars("twelve-chars")))
        assertEquals(12, ExportPasswordPolicy.MIN_LENGTH)
    }

    @Test fun digestIsFormattedInGroupsOfFour() {
        assertEquals("ABCD 0123 4567", formatDigest("abcd01234567"))
    }

    @Test fun withoutAKeyExportIsClosedAndNothingIsCreated() = runBlocking {
        val p = presenter()
        p.refresh()
        assertFalse(p.state.value.keyExists)
        assertNull(p.state.value.certificateSha256)
        p.onEvent(SigningKeyEvent.OpenExport)
        assertEquals(SigningKeyForm.None, p.state.value.form)
        p.onEvent(SigningKeyEvent.OpenImport)
        assertEquals(SigningKeyForm.Import, p.state.value.form)
        assertFalse(store().exists())
    }

    @Test fun aWeakOrMismatchedPasswordIsRejectedBeforeAnyPicker() = runBlocking {
        val s = store().also { it.load() }
        val p = presenter(s)
        p.refresh()
        p.onEvent(SigningKeyEvent.OpenExport)
        p.onEvent(SigningKeyEvent.SubmitExport(chars("short"), chars("short")))
        assertEquals(R.string.signing_key_error_short, p.state.value.formError)
        p.onEvent(SigningKeyEvent.SubmitExport(chars("twelve-chars"), chars("twelve-charz")))
        assertEquals(R.string.signing_key_error_mismatch, p.state.value.formError)
        // No picker was requested, so exporting has nothing pending.
        var wrote = false
        p.exportTo { wrote = true }
        assertFalse(wrote)
        assertNull(record.sha256)
    }

    @Test fun exportWritesAReadableBackupAndRemembersTheDigest() = runBlocking {
        val s = store().also { it.load() }
        val p = presenter(s)
        p.refresh()
        assertFalse(p.state.value.backedUp)
        p.onEvent(SigningKeyEvent.OpenExport)
        val submitted = chars("a-long-password")
        p.onEvent(SigningKeyEvent.SubmitExport(submitted, chars("a-long-password")))
        assertTrue("the caller's array is wiped", submitted.all { it == '\u0000' })
        assertEquals(SigningKeyRequest.CreateBackupFile, p.requests.first())

        var written: ByteArray? = null
        p.exportTo { written = it.copyOf() }
        val ks = KeyStore.getInstance("PKCS12").apply { load(ByteArrayInputStream(written), password) }
        assertArrayEquals(s.load().certificates.first().encoded, ks.getCertificate(ks.aliases().nextElement()).encoded)
        assertEquals(s.certificateSha256(), record.sha256)
        assertTrue(p.state.value.backedUp)
        assertEquals(SigningKeyForm.None, p.state.value.form)
        assertEquals(R.string.signing_key_export_done, p.state.value.message!!.text)
        assertFalse(p.state.value.message!!.isError)
    }

    @Test fun aFailedWriteReportsAnErrorAndRemembersNothing() = runBlocking {
        val s = store().also { it.load() }
        val p = presenter(s)
        p.refresh()
        p.onEvent(SigningKeyEvent.OpenExport)
        p.onEvent(SigningKeyEvent.SubmitExport(chars("a-long-password"), chars("a-long-password")))
        p.exportTo { throw IOException("secret detail that must not surface") }
        assertNull(record.sha256)
        assertEquals(R.string.signing_key_export_failed, p.state.value.message!!.text)
        assertTrue(p.state.value.message!!.isError)
        assertEquals(SigningKeyForm.Export, p.state.value.form)
    }

    @Test fun cancellingThePickerDropsThePassword() = runBlocking {
        val s = store().also { it.load() }
        val p = presenter(s)
        p.refresh()
        p.onEvent(SigningKeyEvent.OpenExport)
        p.onEvent(SigningKeyEvent.SubmitExport(chars("a-long-password"), chars("a-long-password")))
        p.pickCancelled()
        var wrote = false
        p.exportTo { wrote = true }
        assertFalse(wrote)
    }

    private suspend fun importWith(credentialId: String?, fileName: String?): Boolean {
        val s = store().also { it.load() }
        val (backup, _) = otherBackup()
        val p = presenter(s)
        p.refresh()
        p.onEvent(SigningKeyEvent.OpenImport)
        p.submitImport(chars("a-long-password"), credentialId)
        p.inspectFile(fileName) { backup }
        return p.state.value.savedPasswordMismatch
    }

    @Test fun savedPasswordOfAnotherKeyWarns() = runBlocking {
        assertTrue(importWith("OpenSHM signing key aaaaaaaa \u00B7 20240102-030405", "openshm-signing-key-bbbbbbbb-20240102-030405.p12"))
    }

    @Test fun matchingOrUnknownDigestsDoNotWarn() = runBlocking {
        assertFalse(importWith("OpenSHM signing key aaaaaaaa \u00B7 20240102-030405", "openshm-signing-key-aaaaaaaa-20240101-000000.p12"))
        assertFalse(importWith("OpenSHM signing key aaaaaaaa", "my-key.p12"))
        assertFalse(importWith("some other credential", "openshm-signing-key-bbbbbbbb.p12"))
        assertFalse(importWith(null, "openshm-signing-key-bbbbbbbb.p12"))
    }

    @Test fun importAsksForConfirmationAndChangesNothingUntilConfirmed() = runBlocking {
        val s = store().also { it.load() }
        val before = s.certificateSha256()
        val (backup, backupSha) = otherBackup()
        val p = presenter(s)
        p.refresh()
        p.onEvent(SigningKeyEvent.OpenImport)
        p.submitImport(chars("a-long-password"))
        assertEquals(SigningKeyRequest.PickKeyFile, p.requests.first())
        p.inspectFile { backup }

        assertNotNull(p.state.value.confirmation)
        val confirmation = p.state.value.confirmation!!
        assertEquals(before, confirmation.currentSha256)
        assertEquals(backupSha, confirmation.importedSha256)
        assertEquals(before, s.certificateSha256())

        p.confirmImport()
        assertEquals(backupSha, s.certificateSha256())
        assertEquals(backupSha, record.sha256)
        assertEquals(backupSha, p.state.value.certificateSha256)
        assertNull(p.state.value.confirmation)
        assertEquals(R.string.signing_key_import_done, p.state.value.message!!.text)
    }

    @Test fun decliningTheConfirmationKeepsTheCurrentKey() = runBlocking {
        val s = store().also { it.load() }
        val before = s.certificateSha256()
        val (backup, _) = otherBackup()
        val p = presenter(s)
        p.refresh()
        p.submitImport(chars("a-long-password"))
        p.inspectFile { backup }
        p.onEvent(SigningKeyEvent.DismissConfirmation)
        assertNull(p.state.value.confirmation)
        p.confirmImport() // nothing pending any more
        assertEquals(before, s.certificateSha256())
        assertNull(record.sha256)
    }

    @Test fun aWrongFilePasswordIsShownOnTheFormWithoutAConfirmation() = runBlocking {
        val s = store().also { it.load() }
        val (backup, _) = otherBackup()
        val p = presenter(s)
        p.refresh()
        p.onEvent(SigningKeyEvent.OpenImport)
        p.submitImport(chars("not-the-password"))
        p.inspectFile { backup }
        assertNull(p.state.value.confirmation)
        assertEquals(R.string.signing_key_import_wrong_password, p.state.value.formError)
        assertEquals(SigningKeyForm.Import, p.state.value.form)
    }

    @Test fun anUnreadableFileIsShownOnTheForm() = runBlocking {
        val s = store().also { it.load() }
        val p = presenter(s)
        p.refresh()
        p.submitImport(chars("a-long-password"))
        p.inspectFile { throw IOException("cannot open") }
        assertEquals(R.string.signing_key_import_unreadable, p.state.value.formError)
        assertNull(p.state.value.confirmation)
    }

    @Test fun importIsAllowedWithoutAKeyAndShowsNoCurrentDigest() = runBlocking {
        val (backup, backupSha) = otherBackup()
        val s = store()
        val p = presenter(s)
        p.refresh()
        p.submitImport(chars("a-long-password"))
        p.inspectFile { backup }
        assertNull(p.state.value.confirmation!!.currentSha256)
        p.confirmImport()
        assertTrue(s.exists())
        assertEquals(backupSha, s.certificateSha256())
        assertTrue(p.state.value.keyExists)
    }

    private class FakeBackupFiles(private val content: Map<String, ByteArray>, private val failing: Boolean = false) : BackupFiles {
        override fun exists(name: String) = name in content
        override fun write(name: String, bytes: ByteArray) = throw UnsupportedOperationException()
        override fun read(name: String): ByteArray? = if (failing) throw IOException("denied") else content[name]?.copyOf()
    }

    private val savedId = "OpenSHM signing key aaaaaaaa \u00B7 20240102-030405"
    private val savedFile = "openshm-signing-key-aaaaaaaa-20240102-030405.p12"

    private fun presenterWith(s: SigningKeyStore, files: BackupFiles) = SigningKeyPresenter(s, record, Dispatchers.Unconfined, backupFiles = files)

    @Test fun aSavedPasswordWithAReadableBackupSkipsThePicker() = runBlocking {
        val s = store().also { it.load() }
        val (backup, backupSha) = otherBackup()
        val p = presenterWith(s, FakeBackupFiles(mapOf(savedFile to backup)))
        p.refresh()
        p.onEvent(SigningKeyEvent.OpenImport)
        p.submitImport(chars("a-long-password"), savedId)
        assertEquals(backupSha, p.state.value.confirmation?.importedSha256)
        assertNull(kotlinx.coroutines.withTimeoutOrNull(50) { p.requests.first() })
        p.confirmImport()
        assertEquals(backupSha, s.certificateSha256())
    }

    @Test fun aSavedPasswordWithoutAReadableBackupAsksForTheFile() = runBlocking {
        val s = store().also { it.load() }
        val (backup, _) = otherBackup()
        for (files in listOf(FakeBackupFiles(emptyMap()), FakeBackupFiles(mapOf(savedFile to backup), failing = true))) {
            val p = presenterWith(s, files)
            p.refresh()
            p.submitImport(chars("a-long-password"), savedId)
            assertEquals(SigningKeyRequest.PickKeyFile, p.requests.first())
            assertNull(p.state.value.confirmation)
        }
    }

    @Test fun aTypedPasswordAsksForTheFileEvenWhenABackupExists() = runBlocking {
        val s = store().also { it.load() }
        val (backup, _) = otherBackup()
        val p = presenterWith(s, FakeBackupFiles(mapOf(savedFile to backup)))
        p.refresh()
        p.submitImport(chars("a-long-password"))
        assertEquals(SigningKeyRequest.PickKeyFile, p.requests.first())
        assertNull(p.state.value.confirmation)
    }

    @Test fun aWrongPasswordWithTheDirectReadShowsTheFormError() = runBlocking {
        val s = store().also { it.load() }
        val (backup, _) = otherBackup()
        val p = presenterWith(s, FakeBackupFiles(mapOf(savedFile to backup)))
        p.refresh()
        p.onEvent(SigningKeyEvent.OpenImport)
        p.submitImport(chars("not-the-password"), savedId)
        assertNull(p.state.value.confirmation)
        assertEquals(R.string.signing_key_import_wrong_password, p.state.value.formError)
        assertNull(kotlinx.coroutines.withTimeoutOrNull(50) { p.requests.first() })
    }

    @Test fun startRestoresTheKeyFromTheCloudWhenThereIsNone() = runBlocking {
        val vault = FakeKeyVault()
        val donor = store(other).also { it.load() }
        io.openshm.patcher.data.CloudKeyBackup(donor, FakeRecord(), vault, Dispatchers.Unconfined).save()
        val s = store()
        val p = SigningKeyPresenter(s, record, Dispatchers.Unconfined, cloud = io.openshm.patcher.data.CloudKeyBackup(s, record, vault, Dispatchers.Unconfined))
        p.start()
        assertTrue(s.exists())
        assertEquals(donor.certificateSha256(), p.state.value.certificateSha256)
        assertEquals(R.string.key_backup_cloud_restored, p.state.value.message!!.text)
        assertEquals(io.openshm.patcher.ui.screens.signingkey.CloudStatus.Saved, p.state.value.cloud)
    }

    @Test fun aDifferentCloudKeyIsOfferedAndGoesThroughTheConfirmation() = runBlocking {
        val vault = FakeKeyVault()
        val donor = store(other).also { it.load() }
        io.openshm.patcher.data.CloudKeyBackup(donor, FakeRecord(), vault, Dispatchers.Unconfined).save()
        val s = store().also { it.load() }
        val p = SigningKeyPresenter(s, record, Dispatchers.Unconfined, cloud = io.openshm.patcher.data.CloudKeyBackup(s, record, vault, Dispatchers.Unconfined))
        p.refresh()
        p.syncCloud()
        assertTrue(p.state.value.cloudRestorable)
        val before = s.certificateSha256()
        p.restoreFromCloud()
        assertEquals(donor.certificateSha256(), p.state.value.confirmation?.importedSha256)
        assertEquals(before, s.certificateSha256())
        p.confirmImport()
        assertEquals(donor.certificateSha256(), s.certificateSha256())
    }

    @Test fun aBrokenCloudNeverBreaksTheScreen() = runBlocking {
        val vault = FakeKeyVault(failing = true)
        val s = store().also { it.load() }
        val p = SigningKeyPresenter(s, record, Dispatchers.Unconfined, cloud = io.openshm.patcher.data.CloudKeyBackup(s, record, vault, Dispatchers.Unconfined))
        p.start()
        assertTrue(p.state.value.keyExists)
        assertEquals(io.openshm.patcher.ui.screens.signingkey.CloudStatus.None, p.state.value.cloud)
        assertNull(p.state.value.message)
    }

    @Test fun anEmptyImportPasswordIsRejected() = runBlocking {
        val p = presenter()
        p.refresh()
        p.onEvent(SigningKeyEvent.OpenImport)
        p.submitImport(CharArray(0))
        assertEquals(R.string.signing_key_error_empty, p.state.value.formError)
    }

    @Test fun aShortPasswordIsFlaggedOnlyOnceTheFieldIsLeft() {
        val short = "a".repeat(ExportPasswordPolicy.MIN_LENGTH - 1)
        val ok = "a".repeat(ExportPasswordPolicy.MIN_LENGTH)
        assertNull(ExportPasswordPolicy.shownProblem(short, "", passwordLeft = false, confirmLeft = false, submitted = null))
        assertNull(ExportPasswordPolicy.shownProblem("", "", passwordLeft = true, confirmLeft = false, submitted = null))
        assertEquals(R.string.signing_key_error_short, ExportPasswordPolicy.shownProblem(short, "", passwordLeft = true, confirmLeft = false, submitted = null))
        assertNull(ExportPasswordPolicy.shownProblem(ok, "", passwordLeft = true, confirmLeft = false, submitted = null))
    }

    @Test fun aMismatchIsFlaggedOnceTheConfirmationIsLeft() {
        val ok = "a".repeat(ExportPasswordPolicy.MIN_LENGTH)
        assertNull(ExportPasswordPolicy.shownProblem(ok, "b", passwordLeft = true, confirmLeft = false, submitted = null))
        assertEquals(R.string.signing_key_error_mismatch, ExportPasswordPolicy.shownProblem(ok, "b", passwordLeft = true, confirmLeft = true, submitted = null))
        assertNull(ExportPasswordPolicy.shownProblem(ok, ok, passwordLeft = true, confirmLeft = true, submitted = null))
    }

    @Test fun aSubmittedErrorClearsOnceFixed() {
        val ok = "a".repeat(ExportPasswordPolicy.MIN_LENGTH)
        assertEquals(R.string.signing_key_error_short, ExportPasswordPolicy.shownProblem("a", "", false, false, R.string.signing_key_error_short))
        assertNull(ExportPasswordPolicy.shownProblem(ok, "", false, false, R.string.signing_key_error_short))
        assertNull(ExportPasswordPolicy.shownProblem(ok, ok, false, false, R.string.signing_key_error_mismatch))
        assertEquals(R.string.signing_key_export_failed, ExportPasswordPolicy.shownProblem(ok, ok, false, false, R.string.signing_key_export_failed))
    }
}
