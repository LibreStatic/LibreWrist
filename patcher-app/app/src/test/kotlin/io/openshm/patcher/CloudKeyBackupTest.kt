package io.openshm.patcher

import io.openshm.patcher.data.CloudKeyBackup
import io.openshm.patcher.data.CloudRestoreResult
import io.openshm.patcher.data.CloudSaveResult
import io.openshm.patcher.data.KeyBackupRecord
import io.openshm.patcher.data.KeyVault
import io.openshm.patcher.data.SigningKeyStore
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import kotlinx.coroutines.Dispatchers
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

internal class FakeKeyVault(
    var isAvailable: Boolean = true,
    var e2ee: Boolean = true,
    var failing: Boolean = false,
) : KeyVault {
    val entries = linkedMapOf<String, ByteArray>()
    val writes = mutableListOf<String>()
    val cloudFlags = mutableListOf<Boolean>()

    private fun check() {
        if (failing) throw java.io.IOException("vault failure")
    }

    override suspend fun available() = isAvailable.also { check() }
    override suspend fun endToEndEncrypted() = e2ee.also { check() }
    override suspend fun store(key: String, bytes: ByteArray, cloud: Boolean) {
        check()
        require(bytes.size <= CloudKeyBackup.CHUNK_BYTES) { "entry too large" }
        writes += key
        cloudFlags += cloud
        entries[key] = bytes.copyOf()
        require(entries.size <= 16) { "too many entries" }
    }

    override suspend fun retrieve(keys: List<String>): Map<String, ByteArray> {
        check()
        return keys.mapNotNull { k -> entries[k]?.let { k to it.copyOf() } }.toMap()
    }

    override suspend fun delete(keys: List<String>) {
        check()
        keys.forEach { entries.remove(it) }
    }
}

class CloudKeyBackupTest {
    private class FakeRecord(
        override var sha256: String? = null,
        override var backupFile: String? = null,
        override var declinedSha256: String? = null,
        override var cloudSha256: String? = null,
        override var backupPrivate: Boolean = false,
        override var privateBackupFile: String? = null,
    ) : KeyBackupRecord

    private lateinit var dir: File
    private lateinit var dir2: File
    private val wrap = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val record = FakeRecord()
    private val vault = FakeKeyVault()
    private lateinit var store: SigningKeyStore

    @Before fun setUp() {
        dir = Files.createTempDirectory("cloud-backup-test").toFile()
        dir2 = Files.createTempDirectory("cloud-backup-test2").toFile()
        store = SigningKeyStore(dir) { wrap }.also { it.load() }
    }

    @After fun tearDown() {
        dir.deleteRecursively()
        dir2.deleteRecursively()
    }

    private fun cloud(s: SigningKeyStore = store, r: KeyBackupRecord = record, v: KeyVault = vault) = CloudKeyBackup(s, r, v, Dispatchers.Unconfined)

    @Test fun saveThenLoadRoundTripsInChunksAndCommitsMetaLast() = runBlocking {
        assertEquals(CloudSaveResult.Saved(true), cloud().save())
        assertTrue(vault.entries.size in 3..16) // a 3072-bit key does not fit one entry
        assertEquals(CloudKeyBackup.META_KEY, vault.writes.last())
        assertTrue(vault.writes.dropLast(1).all { it.startsWith("openshm.signing-key.") && it != CloudKeyBackup.META_KEY })
        assertEquals(store.certificateSha256(), record.cloudSha256)
        val key = cloud().load()!!
        assertEquals(store.certificateSha256(), key.certificateSha256)
        assertEquals(store.certificateSha256(), store.inspectPkcs12(key.p12, key.password))
        key.wipe()
        assertTrue(key.p12.all { it == 0.toByte() })
    }

    @Test fun savingAgainForTheSameKeyIsSkipped() = runBlocking {
        cloud().save()
        val writes = vault.writes.size
        assertEquals(CloudSaveResult.AlreadySaved, cloud().save())
        assertEquals(writes, vault.writes.size)
    }

    @Test fun withoutEndToEndEncryptionTheCopyStaysLocalAndIsRetriedLater() = runBlocking {
        vault.e2ee = false
        assertEquals(CloudSaveResult.Saved(false), cloud().save())
        assertNull(record.cloudSha256)
        assertTrue(vault.cloudFlags.none { it })
        vault.e2ee = true
        assertEquals(CloudSaveResult.Saved(true), cloud().save())
        assertNotNull(record.cloudSha256)
    }

    @Test fun aDifferentKeyInTheAccountIsNotOverwrittenUnlessAsked() = runBlocking {
        val donor = SigningKeyStore(dir2) { wrap }.also { it.load() }
        cloud(donor, FakeRecord()).save()
        assertEquals(CloudSaveResult.Differs, cloud().save())
        assertEquals(donor.certificateSha256(), cloud().storedCertificateSha256())
        assertEquals(CloudSaveResult.Saved(true), cloud().save(replaceForeign = true))
        assertEquals(store.certificateSha256(), cloud().storedCertificateSha256())
    }

    @Test fun ourOwnOlderUploadIsReplacedWhenTheKeyChanges() = runBlocking {
        cloud().save()
        val oldSha = store.certificateSha256()
        dir.deleteRecursively()
        val replaced = SigningKeyStore(dir) { wrap }.also { it.load() }
        record.cloudSha256 = oldSha
        assertEquals(CloudSaveResult.Saved(true), cloud(replaced).save())
        assertEquals(replaced.certificateSha256(), cloud().storedCertificateSha256())
    }

    @Test fun noKeyMeansNothingToSave() = runBlocking {
        dir.deleteRecursively()
        assertEquals(CloudSaveResult.NoKey, cloud().save())
        assertTrue(vault.writes.isEmpty())
    }

    @Test fun corruptedOrIncompleteDataLoadsAsNull() = runBlocking {
        cloud().save()
        val intact = vault.entries.mapValues { it.value.copyOf() }

        vault.entries.remove(CloudKeyBackup.chunkKey(1))
        assertNull(cloud().load())

        vault.entries.clear(); vault.entries.putAll(intact.mapValues { it.value.copyOf() })
        vault.entries.getValue(CloudKeyBackup.chunkKey(0))[10] = (vault.entries.getValue(CloudKeyBackup.chunkKey(0))[10] + 1).toByte()
        assertNull(cloud().load())

        vault.entries.clear(); vault.entries.putAll(intact.mapValues { it.value.copyOf() })
        vault.entries.remove(CloudKeyBackup.META_KEY)
        assertNull(cloud().load())

        vault.entries.clear(); vault.entries.putAll(intact.mapValues { it.value.copyOf() })
        vault.entries.getValue(CloudKeyBackup.META_KEY)[0] = 9 // unknown version
        assertNull(cloud().load())

        vault.entries.clear(); vault.entries.putAll(intact)
        assertNotNull(cloud().load())
    }

    @Test fun aFailingOrMissingVaultIsUnavailableAndNeverThrows() = runBlocking {
        vault.failing = true
        assertEquals(CloudSaveResult.Unavailable, cloud().save())
        assertNull(cloud().load())
        assertNull(record.cloudSha256)
        vault.failing = false
        vault.isAvailable = false
        assertEquals(CloudSaveResult.Unavailable, cloud().save())
        val empty = SigningKeyStore(dir2) { wrap }
        assertEquals(CloudRestoreResult.Unavailable, cloud(empty).restoreIfNoKey())
        assertFalse(empty.exists())
    }

    @Test fun aFailureHalfWayLeavesNoReadableCopy() = runBlocking {
        cloud().save()
        record.cloudSha256 = null
        val failing = object : KeyVault by vault {
            var n = 0
            override suspend fun store(key: String, bytes: ByteArray, cloud: Boolean) {
                if (++n == 2) throw java.io.IOException("boom")
                vault.store(key, bytes, cloud)
            }
        }
        assertEquals(CloudSaveResult.Unavailable, cloud(v = failing).save())
        assertNull(record.cloudSha256)
        assertNull(cloud().load())
    }

    @Test fun restoreBringsTheKeyBackOnlyWhenThereIsNone() = runBlocking {
        cloud().save()
        val sha = store.certificateSha256()
        val fresh = SigningKeyStore(dir2) { wrap }
        val freshRecord = FakeRecord()
        assertEquals(CloudRestoreResult.Restored(sha), cloud(fresh, freshRecord).restoreIfNoKey())
        assertEquals(sha, fresh.certificateSha256())
        assertEquals(sha, freshRecord.sha256)
        assertEquals(sha, freshRecord.cloudSha256)
        assertEquals(CloudRestoreResult.KeyExists, cloud(fresh, freshRecord).restoreIfNoKey())
    }

    @Test fun restoreWithNothingStoredIsNotFound() = runBlocking {
        val fresh = SigningKeyStore(dir2) { wrap }
        assertEquals(CloudRestoreResult.NotFound, cloud(fresh, FakeRecord()).restoreIfNoKey())
        assertFalse(fresh.exists())
    }

    @Test fun aCertificateDigestThatDoesNotMatchTheKeyIsNotRestored() = runBlocking {
        cloud().save()
        val meta = vault.entries.getValue(CloudKeyBackup.META_KEY)
        meta[meta.size - 1] = if (meta[meta.size - 1] == '0'.code.toByte()) '1'.code.toByte() else '0'.code.toByte()
        val fresh = SigningKeyStore(dir2) { wrap }
        assertEquals(CloudRestoreResult.Failed, cloud(fresh, FakeRecord()).restoreIfNoKey())
        assertFalse(fresh.exists())
    }

    @Test fun anOldLongerCopyLeavesNoStaleChunks() = runBlocking {
        vault.entries[CloudKeyBackup.chunkKey(14)] = ByteArray(10)
        cloud().save()
        assertFalse(vault.entries.containsKey(CloudKeyBackup.chunkKey(14)))
        assertArrayEquals(vault.entries.keys.sorted().toTypedArray(), vault.entries.keys.sorted().toTypedArray())
    }
}
