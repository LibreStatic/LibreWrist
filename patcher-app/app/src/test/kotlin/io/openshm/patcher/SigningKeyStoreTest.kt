package io.openshm.patcher

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

    private fun assertImportFails(bytes: ByteArray, password: String, messagePart: String) {
        val s = store()
        val before = s.load().certificates.first().encoded
        try {
            s.importPkcs12(ByteArrayInputStream(bytes), password.toCharArray())
            fail("imported an unusable file")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains(messagePart))
        }
        assertArrayEquals(before, store().load().certificates.first().encoded)
    }

    @Test fun importRejectsAWrongPassword() {
        val exported = store().exportPkcs12("right".toCharArray())
        assertImportFails(exported, "wrong", "password")
    }

    @Test fun importRejectsAKeyThatDoesNotMatchItsCertificate() {
        val cert = store().load().certificates.first()
        val stranger = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertImportFails(p12(stranger.private, cert, "p"), "p", "does not match")
    }

    @Test fun importRejectsAShortRsaKey() {
        val cert = store().load().certificates.first()
        val weak = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        assertImportFails(p12(weak.private, cert, "p"), "p", "shorter than")
    }
}
