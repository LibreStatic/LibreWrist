package io.openshm.patcher.adb

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.security.auth.x500.X500Principal
import org.bouncycastle.x509.X509V3CertificateGenerator

/**
 * The app's ADB identity: one RSA-2048 key and a self-signed certificate, generated on first use
 * and kept in the app's private no-backup storage.
 *
 * Security notes for review:
 * - Key generation and signing use the JCA ([KeyPairGenerator], Signature through the generator).
 *   Nothing here implements cryptography.
 * - The private key (PKCS#8) lives only in [dir]/[KEY_FILE]. The directory is under
 *   `noBackupFilesDir`, so it is excluded from backups and is not readable by other apps. Files are
 *   additionally restricted to the owner where the filesystem allows it.
 * - Key material is never logged, never put in exception messages, and never returned as text.
 *   [fingerprint] exposes only a SHA-256 digest of the public key.
 * - All instances for one directory share one process-wide lock, so concurrent first runs cannot
 *   interleave. Every read re-loads the files under that lock and checks that the certificate's
 *   public key matches the private key.
 * - Write order: certificate first, then key, each fsynced before its rename. The only partial
 *   state a crash can leave is a lone certificate, which counts as "not created yet".
 */
class AdbKeyStore(private val dir: File) {
    private val lock: Any = lockFor(dir)

    private class Identity(val privateKey: PrivateKey, val certificate: X509Certificate)

    /** The private key, creating the identity on first use. */
    fun privateKey(): PrivateKey = synchronized(lock) { identity().privateKey }

    /** The self-signed certificate wrapping the public key. */
    fun certificate(): X509Certificate = synchronized(lock) { identity().certificate }

    /** Lowercase hex SHA-256 of the public key, safe to show or log. */
    fun fingerprint(): String = synchronized(lock) {
        MessageDigest.getInstance("SHA-256").digest(identity().certificate.publicKey.encoded)
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Deletes the identity. Devices that paired with it will need to pair again.
     *
     * libadb caches its TLS context, built from the first key it sees, for the life of the
     * process. If TLS has already used this process's key (see [markTlsUse]), a new key cannot take
     * effect until the process restarts, so after such a reset [markTlsUse] (called by
     * [LibAdbBackend] before every pair and connect) throws [AdbException.RestartRequired].
     */
    fun reset() = synchronized(lock) {
        File(dir, KEY_FILE).delete()
        File(dir, CERT_FILE).delete()
        if (tlsUsed.get()) restartRequired.set(true)
    }

    private fun identity(): Identity {
        val keyFile = File(dir, KEY_FILE)
        val certFile = File(dir, CERT_FILE)
        return when {
            keyFile.isFile && certFile.isFile -> load(keyFile, certFile)
            keyFile.exists() -> throw IOException("ADB key store is incomplete; call reset() to regenerate it")
            // Nothing, or a lone certificate left by an interrupted first run: not created yet.
            else -> generate(keyFile, certFile)
        }
    }

    private fun load(keyFile: File, certFile: File): Identity {
        val unreadable = IOException("ADB key store is unreadable; call reset() to regenerate it")
        val key: PrivateKey
        val cert: X509Certificate
        try {
            key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            cert = parseCertificate(certFile.readBytes())
        } catch (e: Exception) {
            // Deliberately no cause: it could echo file contents.
            throw unreadable
        }
        val priv = key as? RSAPrivateKey
        val pub = cert.publicKey as? RSAPublicKey
        if (priv == null || pub == null || priv.modulus != pub.modulus) throw unreadable
        return Identity(key, cert)
    }

    @Suppress("DEPRECATION")
    private fun generate(keyFile: File, certFile: File): Identity {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create the ADB key directory")
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(KEY_BITS, SecureRandom())
        val pair = generator.generateKeyPair()

        val subject = X500Principal("CN=OpenSHM Patcher")
        val now = System.currentTimeMillis()
        val certGen = X509V3CertificateGenerator()
        certGen.setSerialNumber(BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE))
        certGen.setIssuerDN(subject)
        certGen.setSubjectDN(subject)
        certGen.setNotBefore(Date(now - DAY_MS))
        certGen.setNotAfter(Date(now + VALID_DAYS * DAY_MS))
        certGen.setPublicKey(pair.public)
        certGen.setSignatureAlgorithm("SHA256withRSA")
        val generated = certGen.generate(pair.private)
        // Re-parse through the platform factory so only plain DER is persisted and used.
        val certDer = generated.encoded
        val cert = parseCertificate(certDer)

        // Certificate first, key last: the key file's existence is the "created" marker.
        writePrivate(certFile, certDer)
        writePrivate(keyFile, pair.private.encoded)
        return Identity(pair.private, cert)
    }

    private fun parseCertificate(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

    private fun writePrivate(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.delete()
        // Create empty, restrict, then fill: the content is never present in a wider-readable file.
        if (!tmp.createNewFile()) throw IOException("Cannot create the ADB key file")
        tmp.setReadable(false, false)
        tmp.setWritable(false, false)
        tmp.setReadable(true, true)
        tmp.setWritable(true, true)
        FileOutputStream(tmp).use {
            it.write(bytes)
            it.flush()
            it.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Cannot store the ADB key file")
        }
    }

    companion object {
        const val KEY_FILE = "adbkey.pk8"
        const val CERT_FILE = "adbkey.crt"
        private const val KEY_BITS = 2048
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val VALID_DAYS = 365L * 30

        private val locks = ConcurrentHashMap<String, Any>()
        private val tlsUsed = AtomicBoolean(false)
        private val restartRequired = AtomicBoolean(false)

        private fun lockFor(dir: File): Any = locks.computeIfAbsent(dir.absoluteFile.normalize().path) { Any() }

        /**
         * Called before libadb uses the key for TLS (pairing or connecting). Throws
         * [AdbException.RestartRequired] if the key was reset after TLS had already used a key in
         * this process; otherwise records that TLS now holds the current key.
         */
        fun markTlsUse() {
            if (restartRequired.get()) throw AdbException.RestartRequired()
            tlsUsed.set(true)
        }

        /** Clears the process-wide TLS bookkeeping. Tests only. */
        internal fun resetProcessStateForTests() {
            tlsUsed.set(false)
            restartRequired.set(false)
        }

        /** The key store of this app, under `noBackupFilesDir/adb`. */
        fun forContext(context: Context): AdbKeyStore =
            AdbKeyStore(File(context.applicationContext.noBackupFilesDir, "adb"))
    }
}
