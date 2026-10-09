package io.openshm.patcher.adb

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
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
 */
class AdbKeyStore(private val dir: File) {
    private var cached: Identity? = null

    private class Identity(val privateKey: PrivateKey, val certificate: X509Certificate)

    /** The private key, creating the identity on first use. */
    @Synchronized
    fun privateKey(): PrivateKey = identity().privateKey

    /** The self-signed certificate wrapping the public key. */
    @Synchronized
    fun certificate(): X509Certificate = identity().certificate

    /** Lowercase hex SHA-256 of the public key, safe to show or log. */
    @Synchronized
    fun fingerprint(): String =
        MessageDigest.getInstance("SHA-256").digest(identity().certificate.publicKey.encoded)
            .joinToString("") { "%02x".format(it) }

    /** Deletes the identity. Devices that paired with it will need to pair again. */
    @Synchronized
    fun reset() {
        cached = null
        File(dir, KEY_FILE).delete()
        File(dir, CERT_FILE).delete()
    }

    private fun identity(): Identity {
        cached?.let { return it }
        val keyFile = File(dir, KEY_FILE)
        val certFile = File(dir, CERT_FILE)
        val loaded = when {
            keyFile.isFile && certFile.isFile -> load(keyFile, certFile)
            keyFile.exists() || certFile.exists() ->
                throw IOException("ADB key store is incomplete; call reset() to regenerate it")
            else -> generate(keyFile, certFile)
        }
        cached = loaded
        return loaded
    }

    private fun load(keyFile: File, certFile: File): Identity = try {
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
        val cert = parseCertificate(certFile.readBytes())
        Identity(key, cert)
    } catch (e: Exception) {
        // Deliberately no cause message: it could echo file contents.
        throw IOException("ADB key store is unreadable; call reset() to regenerate it")
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

        writePrivate(keyFile, pair.private.encoded)
        writePrivate(certFile, certDer)
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
        tmp.writeBytes(bytes)
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

        /** The key store of this app, under `noBackupFilesDir/adb`. */
        fun forContext(context: Context): AdbKeyStore =
            AdbKeyStore(File(context.applicationContext.noBackupFilesDir, "adb"))
    }
}
