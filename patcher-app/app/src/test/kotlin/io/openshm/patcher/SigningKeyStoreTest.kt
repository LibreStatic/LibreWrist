package io.openshm.patcher

import io.openshm.patcher.data.KeyImportException
import io.openshm.patcher.data.KeyImportException.Reason
import io.openshm.patcher.data.SigningKeyStore
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.KeyStore
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SigningKeyStoreTest {
    private lateinit var dir: File
    private val wrap: SecretKey = aes()

    @Before fun setUp() { dir = Files.createTempDirectory("signing-test").toFile() }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun aes(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun store(key: SecretKey = wrap) = SigningKeyStore(dir) { key }

    @Test fun createsOnFirstLoadAndReloadsTheSameIdentity() {
        val store = store()
        assertFalse(store.exists())
        val first = store.load()
        assertTrue(store.exists())
        val again = store().load()
        assertArrayEquals(first.certificates.first().encoded, again.certificates.first().encoded)
        assertArrayEquals(first.privateKey.encoded, again.privateKey.encoded)
        assertEquals(store.certificateSha256(), store().certificateSha256())
        assertTrue(Regex("[0-9a-f]{64}").matches(store.certificateSha256()))
    }

    @Test fun certificateAndKeyAreAsSpecified() {
        val cert = store().load().certificates.first()
        assertEquals("SHA256withRSA", cert.sigAlgName)
        assertEquals(3072, (cert.publicKey as RSAPublicKey).modulus.bitLength())
        assertTrue(cert.subjectX500Principal.name.contains("OpenSHM local"))
        assertTrue(cert.serialNumber.signum() > 0)
        val years = (cert.notAfter.time - cert.notBefore.time) / (365.0 * 24 * 3600 * 1000)
        assertTrue(years in 29.0..31.0)
        cert.verify(cert.publicKey)
    }

    @Test fun theKeySigns() {
        val key = store().load()
        val data = "hello".toByteArray()
        val sig = Signature.getInstance("SHA256withRSA").apply { initSign(key.privateKey); update(data) }.sign()
        assertTrue(Signature.getInstance("SHA256withRSA").apply { initVerify(key.certificates.first()); update(data) }.verify(sig))
    }

    @Test fun theFileIsPkcs12ProtectedByANonTrivialPassword() {
        store().load()
        val p12 = File(dir, SigningKeyStore.P12_FILE)
        val ks = KeyStore.getInstance("PKCS12")
        // Neither an empty nor a guessed password opens it.
        for (guess in listOf("", "openshm", "changeit")) {
            try {
                p12.inputStream().use { ks.load(it, guess.toCharArray()) }
                fail("opened with '$guess'")
            } catch (expected: IOException) {
            }
        }
        // The password file is neither the password in clear nor readable text.
        val pw = File(dir, SigningKeyStore.PASSWORD_FILE).readBytes()
        assertTrue(pw.size > 12 + 16 + 32)
    }

    @Test fun aWrongWrapKeyFailsAndDoesNotReplaceTheIdentity() {
        store().load()
        val before = File(dir, SigningKeyStore.P12_FILE).readBytes()
        try {
            store(aes()).load()
            fail("loaded with another wrap key")
        } catch (expected: IOException) {
        }
        assertArrayEquals(before, File(dir, SigningKeyStore.P12_FILE).readBytes())
    }

    @Test fun aLonePasswordFileCountsAsNotCreated() {
        File(dir, SigningKeyStore.PASSWORD_FILE).writeBytes(ByteArray(64))
        val store = store()
        assertFalse(store.exists())
        store.load()
        assertTrue(store.exists())
    }

    @Test fun exportAndImportRoundTrip() {
        val a = store()
        val original = a.load()
        val exported = a.exportPkcs12("backup-pass".toCharArray())
        dir.deleteRecursively()
        dir.mkdirs()
        val b = store(aes())
        b.importPkcs12(ByteArrayInputStream(exported), "backup-pass".toCharArray())
        assertArrayEquals(original.certificates.first().encoded, b.load().certificates.first().encoded)
        assertArrayEquals(original.privateKey.encoded, b.load().privateKey.encoded)
    }

    @Test fun aLonePkcs12FileFailsInsteadOfBeingReplaced() {
        store().load()
        File(dir, SigningKeyStore.PASSWORD_FILE).delete()
        val before = File(dir, SigningKeyStore.P12_FILE).readBytes()
        try {
            store().load()
            fail("made a new identity over an existing PKCS#12 file")
        } catch (expected: IOException) {
        }
        assertArrayEquals(before, File(dir, SigningKeyStore.P12_FILE).readBytes())
    }

    @Test fun importOverAnExistingIdentityKeepsThePasswordFile() {
        val other = Files.createTempDirectory("signing-other").toFile()
        try {
            val otherWrap = aes()
            val exported = SigningKeyStore(other) { otherWrap }.exportPkcs12("pass".toCharArray())
            val a = store()
            a.load()
            val passwordBefore = File(dir, SigningKeyStore.PASSWORD_FILE).readBytes()
            a.importPkcs12(ByteArrayInputStream(exported), "pass".toCharArray())
            assertArrayEquals(passwordBefore, File(dir, SigningKeyStore.PASSWORD_FILE).readBytes())
            val imported = KeyStore.getInstance("PKCS12").apply { load(ByteArrayInputStream(exported), "pass".toCharArray()) }
            assertArrayEquals(imported.getCertificate(imported.aliases().nextElement()).encoded, store().load().certificates.first().encoded)
        } finally {
            other.deleteRecursively()
        }
    }

    private fun p12(key: java.security.PrivateKey, cert: java.security.cert.Certificate, password: String): ByteArray {
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        ks.setKeyEntry("k", key, password.toCharArray(), arrayOf(cert))
        return java.io.ByteArrayOutputStream().also { ks.store(it, password.toCharArray()) }.toByteArray()
    }

    private fun assertImportFails(bytes: ByteArray, password: String, reason: Reason) {
        val s = store()
        val before = s.load().certificates.first().encoded
        try {
            s.importPkcs12(ByteArrayInputStream(bytes), password.toCharArray())
            fail("imported an unusable file")
        } catch (e: KeyImportException) {
            assertEquals(reason, e.reason)
        }
        assertArrayEquals(before, store().load().certificates.first().encoded)
    }

    @Test fun importRejectsAWrongPassword() {
        val exported = store().exportPkcs12("right".toCharArray())
        assertImportFails(exported, "wrong", Reason.WrongPasswordOrNotPkcs12)
    }

    @Test fun importRejectsAKeyThatDoesNotMatchItsCertificate() {
        val cert = store().load().certificates.first()
        val stranger = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertImportFails(p12(stranger.private, cert, "p"), "p", Reason.KeyCertificateMismatch)
    }

    @Test fun importRejectsAShortRsaKey() {
        val cert = store().load().certificates.first()
        val weak = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        assertImportFails(p12(weak.private, cert, "p"), "p", Reason.WeakKey)
    }

    @Test fun importRejectsAFileThatIsNotPkcs12() {
        assertImportFails("not a key store".toByteArray(), "p", Reason.WrongPasswordOrNotPkcs12)
    }

    @Test fun importRejectsAStoreWithoutAKey() {
        val cert = store().load().certificates.first()
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        ks.setCertificateEntry("only-cert", cert)
        val bytes = java.io.ByteArrayOutputStream().also { ks.store(it, "p".toCharArray()) }.toByteArray()
        assertImportFails(bytes, "p", Reason.NotOneKey)
    }

    @Test fun inspectReportsTheDigestWithoutChangingAnything() {
        val other = Files.createTempDirectory("signing-other").toFile()
        try {
            val otherWrap = aes()
            val otherStore = SigningKeyStore(other) { otherWrap }
            val exported = otherStore.exportPkcs12("a-long-password".toCharArray())
            val a = store()
            val before = a.certificateSha256()
            assertEquals(otherStore.certificateSha256(), a.inspectPkcs12(exported, "a-long-password".toCharArray()))
            assertEquals(before, a.certificateSha256())
        } finally {
            other.deleteRecursively()
        }
    }

    // A file like the one the PC keystore is: written by the JDK's own PKCS12 KeyStore (AES / PBKDF2 in JDK 17+).
    @Test fun importsAFileWrittenByTheJdkKeyStore() {
        val source = store()
        val original = source.load()
        val bytes = p12(original.privateKey, original.certificates.first(), "jdk-written-pass")
        dir.deleteRecursively()
        dir.mkdirs()
        val target = store(aes())
        target.importPkcs12(ByteArrayInputStream(bytes), "jdk-written-pass".toCharArray())
        assertArrayEquals(original.certificates.first().encoded, target.load().certificates.first().encoded)
        assertArrayEquals(original.privateKey.encoded, target.load().privateKey.encoded)
    }

    @Test fun exportIsReadableByTheJdkKeyStore() {
        val source = store()
        val original = source.load()
        val bytes = source.exportPkcs12("export-pass-123".toCharArray())
        val ks = KeyStore.getInstance("PKCS12").apply { load(ByteArrayInputStream(bytes), "export-pass-123".toCharArray()) }
        val alias = ks.aliases().toList().single()
        assertArrayEquals(original.privateKey.encoded, ks.getKey(alias, "export-pass-123".toCharArray()).encoded)
        assertArrayEquals(original.certificates.first().encoded, ks.getCertificate(alias).encoded)
        try {
            KeyStore.getInstance("PKCS12").load(ByteArrayInputStream(bytes), "wrong-password".toCharArray())
            fail("opened with the wrong password")
        } catch (expected: IOException) {
        }
    }

    @Test fun exportUsesPbes2AndAes256NotTheLegacyAlgorithms() {
        val bytes = store().exportPkcs12("export-pass-123".toCharArray())
        assertTrue("PBES2", contains(bytes, oid(0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x05, 0x0D)))
        assertTrue("AES-256-CBC", contains(bytes, oid(0x60, 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x01, 0x2A)))
        assertTrue("PBKDF2", contains(bytes, oid(0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x05, 0x0C)))
        assertTrue("SHA-256", contains(bytes, oid(0x60, 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01)))
        assertFalse("3DES PBE", contains(bytes, oid(0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x0C, 0x01, 0x03)))
        assertFalse("RC2-40 PBE", contains(bytes, oid(0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x0C, 0x01, 0x06)))
        assertFalse("SHA-1 digest", contains(bytes, oid(0x2B, 0x0E, 0x03, 0x02, 0x1A)))
    }

    @Test fun exportedBytesAreWrittenForManualToolChecks() {
        // Lets the build directory hold a file for `keytool` / `openssl pkcs12 -info`; see the Gradle test task.
        val out = File("build/export-check/openshm-signing-key.p12")
        out.parentFile.mkdirs()
        out.writeBytes(store().exportPkcs12("export-pass-123".toCharArray()))
        assertTrue(out.length() > 0)
    }

    @Test fun theModernReaderReadsBothItsOwnFilesAndTheJdkOnes() {
        val original = store().load()
        val own = store().exportPkcs12("export-pass-123".toCharArray())
        val jdk = p12(original.privateKey, original.certificates.first(), "export-pass-123")
        for (bytes in listOf(own, jdk)) {
            val (key, chain) = io.openshm.patcher.data.ModernPkcs12.read(bytes, "export-pass-123".toCharArray())
            assertArrayEquals(original.privateKey.encoded, key.encoded)
            assertArrayEquals(original.certificates.first().encoded, chain.single().encoded)
            try {
                io.openshm.patcher.data.ModernPkcs12.read(bytes, "wrong-pass-1234".toCharArray())
                fail("read with the wrong password")
            } catch (expected: IOException) {
            }
        }
    }

    // DER OBJECT IDENTIFIER: tag 0x06, length, content.
    private fun oid(vararg content: Int): ByteArray =
        byteArrayOf(0x06, content.size.toByte()) + ByteArray(content.size) { content[it].toByte() }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean =
        (0..haystack.size - needle.size).any { i -> needle.indices.all { haystack[i + it] == needle[it] } }
}
