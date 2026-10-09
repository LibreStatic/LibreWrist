package io.openshm.patcher.adb

import java.io.File
import java.io.IOException
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Date
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AdbKeyStoreTest {
    private val dir = File(System.getProperty("java.io.tmpdir"), "adbkeystore-test-" + System.nanoTime())

    @After
    fun cleanup() {
        AdbKeyStore.resetProcessStateForTests()
        dir.deleteRecursively()
    }

    @Test
    fun `generates an RSA-2048 key with a valid self-signed certificate and reloads it`() {
        val first = AdbKeyStore(dir)
        val key = first.privateKey() as RSAPrivateKey
        val cert = first.certificate()
        assertEquals(2048, key.modulus.bitLength())
        assertEquals(2048, (cert.publicKey as RSAPublicKey).modulus.bitLength())
        cert.verify(cert.publicKey) // self-signed
        cert.checkValidity(Date())

        val second = AdbKeyStore(dir) // fresh instance reads the files
        assertEquals(first.fingerprint(), second.fingerprint())
        assertEquals(key, second.privateKey())
        assertTrue(File(dir, AdbKeyStore.KEY_FILE).isFile && File(dir, AdbKeyStore.CERT_FILE).isFile)
        assertFalse(File(dir, AdbKeyStore.KEY_FILE + ".tmp").exists())
    }

    @Test
    fun `key file is owner-only and the fingerprint does not leak key bytes`() {
        val store = AdbKeyStore(dir)
        val fingerprint = store.fingerprint()
        val keyFile = File(dir, AdbKeyStore.KEY_FILE)
        assertTrue(keyFile.canRead() && keyFile.canWrite())
        val perms = java.nio.file.Files.getPosixFilePermissions(keyFile.toPath())
        assertEquals(setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE), perms)
        assertEquals(64, fingerprint.length)
        val hex = store.privateKey().encoded.joinToString("") { "%02x".format(it) }
        assertFalse(fingerprint.contains(hex.take(16)))
    }

    @Test
    fun `reset creates a new identity`() {
        val store = AdbKeyStore(dir)
        val before = store.fingerprint()
        store.reset()
        assertFalse(File(dir, AdbKeyStore.KEY_FILE).exists())
        assertNotEquals(before, store.fingerprint())
    }

    @Test
    fun `corrupt or partial stores fail loudly without echoing contents`() {
        AdbKeyStore(dir).fingerprint()
        File(dir, AdbKeyStore.KEY_FILE).writeText("SECRET-GARBAGE")
        try {
            AdbKeyStore(dir).privateKey()
            fail()
        } catch (e: IOException) {
            assertFalse(e.message!!.contains("SECRET"))
            assertEquals(null, e.cause)
        }
        File(dir, AdbKeyStore.CERT_FILE).delete()
        try {
            AdbKeyStore(dir).certificate()
            fail()
        } catch (e: IOException) {
        }
    }

    @Test
    fun `a lone certificate from an interrupted first run is regenerated`() {
        AdbKeyStore(dir).fingerprint()
        File(dir, AdbKeyStore.KEY_FILE).delete()
        val store = AdbKeyStore(dir)
        store.privateKey()
        assertTrue(File(dir, AdbKeyStore.KEY_FILE).isFile)
        store.certificate().verify(store.certificate().publicKey)
    }

    @Test
    fun `a certificate that does not match the key is rejected`() {
        val other = File(dir.path + "-other")
        try {
            AdbKeyStore(dir).fingerprint()
            AdbKeyStore(other).fingerprint()
            File(other, AdbKeyStore.CERT_FILE).copyTo(File(dir, AdbKeyStore.CERT_FILE), overwrite = true)
            try {
                AdbKeyStore(dir).privateKey()
                fail()
            } catch (e: IOException) {
                assertTrue(e.message!!.contains("reset"))
            }
        } finally {
            other.deleteRecursively()
        }
    }

    @Test
    fun `concurrent first runs through separate instances yield one consistent identity`() {
        val results = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val start = java.util.concurrent.CountDownLatch(1)
        val threads = (1..6).map {
            Thread {
                start.await()
                results += AdbKeyStore(File(dir.path)).fingerprint()
            }.also { it.start() }
        }
        start.countDown()
        threads.forEach { it.join(60_000) }
        assertEquals(6, results.size)
        assertEquals(1, results.toSet().size)
        AdbKeyStore(dir).privateKey() // loads and passes the key/cert match check
    }

    @Test
    fun `reset after TLS use demands a restart, reset before it does not`() {
        val store = AdbKeyStore(dir)
        store.fingerprint()
        store.reset()
        AdbKeyStore.markTlsUse() // no TLS yet at reset time: fine
        store.reset() // now TLS holds a key
        try {
            AdbKeyStore.markTlsUse()
            fail()
        } catch (e: AdbException.RestartRequired) {
        }
    }
}
