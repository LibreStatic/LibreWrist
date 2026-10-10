package io.openshm.patcher.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.openshm.patcher.engine.SigningKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Provider
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.RSAPrivateKey
import java.util.Base64
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.security.auth.x500.X500Principal
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.x509.X509V3CertificateGenerator

/**
 * The key every built APK is signed with: one RSA-3072 key and a self-signed certificate, created on
 * first use and kept on the device. Installed apps only accept upgrades signed by the same key, so
 * this identity must survive for as long as the apps are installed.
 *
 * Security notes for review:
 * - Key generation, PKCS#12 and AES-GCM are all JCA; nothing here implements cryptography. The key
 *   is a plain JCA key (not AndroidKeyStore) because apksig needs the private key material.
 * - Two files in [dir] (the app's `noBackupFilesDir`, so excluded from backups and private to the
 *   app): [P12_FILE], a PKCS#12 store (alias [ALIAS]) protected by a random 32-byte password, and
 *   [PASSWORD_FILE], that password (base64) encrypted with AES-256-GCM (`IV || ciphertext`) under
 *   the key [wrapKey] returns. In the app that key is a non-exportable AndroidKeyStore key, so the
 *   PKCS#12 file is useless without this device's keystore.
 * - Write order on creation: password file first, PKCS#12 last, each fsynced, renamed into place
 *   and the directory fsynced. The PKCS#12 file is the "created" marker: a lone password file is
 *   leftover from an interrupted first run and is overwritten, while a PKCS#12 file that cannot be
 *   read (password file missing, wrap key gone) makes [load] fail. It never silently makes a new
 *   identity, which would break upgrades of installed apps.
 * - An import keeps the existing password, so only the PKCS#12 file changes and an interruption
 *   leaves either the old or the new identity, never a password that matches neither.
 * - Passwords and key bytes are never logged and never put in exception messages.
 */
class SigningKeyStore(private val dir: File, private val wrapKey: () -> SecretKey) {
    private val p12File get() = File(dir, P12_FILE)
    private val passwordFile get() = File(dir, PASSWORD_FILE)

    fun exists(): Boolean = synchronized(LOCK) { p12File.isFile && passwordFile.isFile }

    /** The signing key, creating it on first use. */
    fun load(): SigningKey = synchronized(LOCK) {
        if (!p12File.exists()) create()
        if (!passwordFile.isFile) throw IOException("the signing key password file is missing")
        read()
    }

    /** Lowercase hex SHA-256 of the signing certificate (DER), as Android shows and `knownCerts` expects. */
    fun certificateSha256(): String = sha256Hex(load().certificates.first())

    /**
     * The key as a PKCS#12 file protected by [password], for a backup the user keeps. Unlike the
     * on-device file this one leaves the device, so it uses the modern profile (PBES2 with
     * PBKDF2-HMAC-SHA-256 and AES-256, HMAC-SHA-256 MAC, see [ModernPkcs12]) instead of the
     * platform's legacy 3DES/RC2 and SHA-1 MAC. OpenSSL 3, `keytool` and `apksigner` read it.
     */
    fun exportPkcs12(password: CharArray): ByteArray = synchronized(LOCK) {
        val key = load()
        ModernPkcs12.write(ALIAS, key.privateKey, key.certificates, password)
    }

    /**
     * Replaces the identity with the single key in the PKCS#12 stream. The caller confirms with the
     * user first. The key must match its certificate and be RSA of at least [MIN_RSA_BITS] bits or EC.
     * Failures are [KeyImportException]s with a stable [KeyImportException.reason].
     */
    fun importPkcs12(stream: InputStream, password: CharArray) = synchronized(LOCK) {
        val (key, chain) = readImportable(stream.readBounded(MAX_IMPORT_BYTES), password)
        replace(key, chain)
    }

    /**
     * Runs every check of [importPkcs12] without changing anything and returns the lowercase hex
     * SHA-256 of the certificate the file would install, for the confirmation the user sees first.
     */
    fun inspectPkcs12(bytes: ByteArray, password: CharArray): String =
        sha256Hex(readImportable(bytes, password).second.first())

    private fun readImportable(bytes: ByteArray, password: CharArray): Pair<PrivateKey, List<X509Certificate>> {
        // Untrusted input: refuse it cheaply before any reader tries a password, and report every
        // failure as a KeyImportException with a stable reason.
        Pkcs12Guard.check(bytes)
        try {
            val (raw, certificates) = readAnyPkcs12(bytes, password)
            val chain = certificates.map { parseCertificate(it.encoded) }
            val key = platformKey(raw)
            checkImportable(key, chain.first())
            return key to chain
        } catch (e: KeyImportException) {
            throw e
        } catch (e: java.security.cert.CertificateException) {
            throw KeyImportException(KeyImportException.Reason.NoCertificate)
        } catch (e: Exception) {
            throw KeyImportException(KeyImportException.Reason.NoUsableKey)
        }
    }

    /**
     * The platform store reads the usual files, Bouncy Castle's the ones it cannot, and
     * [ModernPkcs12] the modern profile where neither of them can on the running platform.
     */
    private fun readAnyPkcs12(bytes: ByteArray, password: CharArray): Pair<PrivateKey, List<X509Certificate>> {
        for (provider in listOf<Provider?>(null, BouncyCastleProvider())) {
            val ks = if (provider == null) KeyStore.getInstance(PKCS12) else KeyStore.getInstance(PKCS12, provider)
            try {
                ks.load(ByteArrayInputStream(bytes), password)
            } catch (e: IOException) {
                continue // Another reader may still understand the file.
            } catch (e: java.security.GeneralSecurityException) {
                continue
            } catch (e: RuntimeException) {
                continue
            }
            return identityOf(ks, password)
        }
        try {
            return ModernPkcs12.read(bytes, password)
        } catch (e: KeyImportException) {
            throw e
        } catch (e: IOException) {
            throw KeyImportException(KeyImportException.Reason.WrongPasswordOrNotPkcs12)
        }
    }

    private fun identityOf(ks: KeyStore, password: CharArray): Pair<PrivateKey, List<X509Certificate>> {
        val alias = ks.aliases().toList().filter { ks.isKeyEntry(it) }.singleOrNull()
            ?: throw KeyImportException(KeyImportException.Reason.NotOneKey)
        val key = try {
            ks.getKey(alias, password) as? PrivateKey
        } catch (e: java.security.GeneralSecurityException) {
            null
        } ?: throw KeyImportException(KeyImportException.Reason.NoUsableKey)
        val chain = ks.getCertificateChain(alias)?.map { it as X509Certificate }?.takeIf { it.isNotEmpty() }
            ?: throw KeyImportException(KeyImportException.Reason.NoCertificate)
        return key to chain
    }

    /** Re-creates the key with the platform provider, so signing never depends on which reader produced it. */
    private fun platformKey(key: PrivateKey): PrivateKey {
        val encoded = key.encoded ?: throw KeyImportException(KeyImportException.Reason.NoUsableKey)
        try {
            return java.security.KeyFactory.getInstance(key.algorithm).generatePrivate(java.security.spec.PKCS8EncodedKeySpec(encoded))
        } catch (e: java.security.GeneralSecurityException) {
            throw KeyImportException(KeyImportException.Reason.NoUsableKey)
        } finally {
            encoded.fill(0)
        }
    }

    private fun create() = persist(newPassword(), generateIdentity())

    /** Keeps the current password when it can still be unlocked, so only the PKCS#12 file changes. */
    private fun replace(key: PrivateKey, chain: List<X509Certificate>) {
        val current = if (p12File.exists() && passwordFile.isFile) {
            try {
                String(unwrap(passwordFile.readBytes()), Charsets.US_ASCII).toCharArray()
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
        if (current != null) {
            writeAtomically(p12File, pkcs12(key, chain, current))
        } else {
            // The old identity is already unreadable, so nothing more can be lost.
            persist(newPassword(), key to chain)
        }
    }

    private fun generateIdentity(): Pair<PrivateKey, List<X509Certificate>> {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(KEY_BITS, SecureRandom())
        val pair = generator.generateKeyPair()
        val subject = X500Principal("CN=OpenSHM local")
        val now = System.currentTimeMillis()
        val certGen = X509V3CertificateGenerator()
        certGen.setSerialNumber(BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE))
        certGen.setIssuerDN(subject)
        certGen.setSubjectDN(subject)
        certGen.setNotBefore(Date(now - DAY_MS))
        certGen.setNotAfter(Date(now + VALID_DAYS * DAY_MS))
        certGen.setPublicKey(pair.public)
        certGen.setSignatureAlgorithm("SHA256withRSA")
        // Re-parse through the platform factory so only plain DER is persisted and used.
        val cert = parseCertificate(certGen.generate(pair.private).encoded)
        return pair.private to listOf(cert)
    }

    private fun newPassword(): CharArray {
        val password = ByteArray(PASSWORD_BYTES).also { SecureRandom().nextBytes(it) }
        return Base64.getEncoder().encodeToString(password).toCharArray()
    }

    private fun persist(chars: CharArray, identity: Pair<PrivateKey, List<X509Certificate>>) {
        dir.mkdirs()
        val ascii = ByteArray(chars.size) { chars[it].code.toByte() } // base64 text, so one byte per char
        try {
            writeAtomically(passwordFile, wrap(ascii))
        } finally {
            ascii.fill(0)
        }
        writeAtomically(p12File, pkcs12(identity.first, identity.second, chars))
    }

    private fun checkImportable(key: PrivateKey, cert: X509Certificate) {
        val algorithm = when (key) {
            is RSAPrivateKey -> {
                if (key.modulus.bitLength() < MIN_RSA_BITS) throw KeyImportException(KeyImportException.Reason.WeakKey)
                "SHA256withRSA"
            }
            is ECPrivateKey -> "SHA256withECDSA"
            else -> throw KeyImportException(KeyImportException.Reason.UnsupportedKeyType)
        }
        val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val signature = Signature.getInstance(algorithm).run {
            initSign(key)
            update(nonce)
            sign()
        }
        val matches = try {
            Signature.getInstance(algorithm).run {
                initVerify(cert.publicKey)
                update(nonce)
                verify(signature)
            }
        } catch (e: java.security.GeneralSecurityException) {
            false
        }
        if (!matches) throw KeyImportException(KeyImportException.Reason.KeyCertificateMismatch)
    }

    private fun read(): SigningKey {
        val chars = try {
            String(unwrap(passwordFile.readBytes()), Charsets.US_ASCII).toCharArray()
        } catch (e: java.security.GeneralSecurityException) {
            throw IOException("the signing key password cannot be unlocked on this device")
        }
        val ks = KeyStore.getInstance(PKCS12)
        p12File.inputStream().use { ks.load(it, chars) }
        val key = ks.getKey(ALIAS, chars) as? PrivateKey ?: throw IOException("the signing key store has no key")
        val chain = ks.getCertificateChain(ALIAS)?.map { it as X509Certificate }
            ?: throw IOException("the signing key store has no certificate")
        return SigningKey(key, chain)
    }

    private fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrapKey())
        cipher.updateAAD(AAD)
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        if (blob.size < IV_BYTES + TAG_BITS / 8) throw IOException("the signing key password file is damaged")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
        cipher.updateAAD(AAD)
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    private fun pkcs12(key: PrivateKey, chain: List<X509Certificate>, password: CharArray): ByteArray {
        val ks = KeyStore.getInstance(PKCS12)
        ks.load(null, null)
        ks.setKeyEntry(ALIAS, key, password, chain.toTypedArray())
        return ByteArrayOutputStream().also { ks.store(it, password) }.toByteArray()
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(dir, target.name + ".part")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()
        }
        syncDirectory()
    }

    /** Makes the rename durable, so the write order above also holds after a power loss. */
    private fun syncDirectory() {
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (e: IOException) {
            // Some file systems cannot open or sync a directory; the rename is still atomic there.
        }
    }

    companion object {
        const val ALIAS = "openshm"
        const val P12_FILE = "signing.p12"
        const val PASSWORD_FILE = "signing.pw"
        const val WRAP_KEY_ALIAS = "openshm-signing-wrap"

        private const val PKCS12 = "PKCS12"

        private const val MAX_IMPORT_BYTES = 1 shl 20
        private const val KEY_BITS = 3072
        private const val MIN_RSA_BITS = 2048
        private const val PASSWORD_BYTES = 32
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val AAD = "openshm-signing-password".toByteArray(Charsets.US_ASCII)
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val VALID_DAYS = 365L * 30
        private val LOCK = Any()

        fun sha256Hex(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

        private fun parseCertificate(der: ByteArray): X509Certificate =
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }
}

/**
 * Reads at most [max] bytes plus one, so a caller can tell "too big" from "exactly max". Written out
 * because `InputStream.readNBytes(int)` needs API 33 and the app supports API 31.
 */
internal fun InputStream.readBounded(max: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var remaining = max + 1
    while (remaining > 0) {
        val n = read(buffer, 0, minOf(buffer.size, remaining))
        if (n < 0) break
        out.write(buffer, 0, n)
        remaining -= n
    }
    return out.toByteArray()
}

/**
 * Why an import was refused. [reason] is stable for callers to map to a message; the text never
 * carries file content or the password.
 */
class KeyImportException(val reason: Reason) : IOException(reason.name) {
    enum class Reason {
        /** The password is wrong, or the file is not a PKCS#12 store (the two cannot be told apart). */
        WrongPasswordOrNotPkcs12,
        NotPkcs12,
        NotOneKey,
        NoUsableKey,
        NoCertificate,
        WeakKey,
        UnsupportedKeyType,
        KeyCertificateMismatch,

        /** The file asks for more key derivation work than any real tool writes. */
        TooManyIterations,
    }
}

/** The app's store: files in `noBackupFilesDir`, password wrapped by a non-exportable AndroidKeyStore AES key. */
fun signingKeyStore(context: Context): SigningKeyStore =
    SigningKeyStore(File(context.applicationContext.noBackupFilesDir, "signing"), ::androidWrapKey)

private fun androidWrapKey(): SecretKey {
    val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (ks.getKey(SigningKeyStore.WRAP_KEY_ALIAS, null) as? SecretKey)?.let { return it }
    val spec = KeyGenParameterSpec.Builder(
        SigningKeyStore.WRAP_KEY_ALIAS,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
    )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .setUserAuthenticationRequired(false)
        .build()
    return javax.crypto.KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        .apply { init(spec) }
        .generateKey()
}
