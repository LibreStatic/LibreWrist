package io.openshm.patcher.data

import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1Set
import org.bouncycastle.asn1.DERBMPString
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.DERTaggedObject
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers
import org.bouncycastle.asn1.pkcs.CertBag
import org.bouncycastle.asn1.pkcs.ContentInfo
import org.bouncycastle.asn1.pkcs.EncryptedData
import org.bouncycastle.asn1.pkcs.EncryptedPrivateKeyInfo
import org.bouncycastle.asn1.pkcs.EncryptionScheme
import org.bouncycastle.asn1.pkcs.KeyDerivationFunc
import org.bouncycastle.asn1.pkcs.MacData
import org.bouncycastle.asn1.pkcs.PBES2Parameters
import org.bouncycastle.asn1.pkcs.PBKDF2Params
import org.bouncycastle.asn1.pkcs.Pfx
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.pkcs.SafeBag
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.DigestInfo
import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.SHA1Digest
import org.bouncycastle.crypto.digests.SHA224Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA384Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.PKCS12ParametersGenerator
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter

/**
 * The modern PKCS#12 profile `keytool` (JDK 17+) and OpenSSL 3 write by default, for the key backup
 * that leaves the device: the private key and the certificates are encrypted with PBES2
 * (PBKDF2-HMAC-SHA-256, 600 000 iterations, AES-256-CBC) and the file carries an HMAC-SHA-256 MAC.
 *
 * Why this exists instead of a Bouncy Castle `KeyStore`: the platform store only writes the legacy
 * 3DES/RC2-40 profile with a SHA-1 MAC, and Bouncy Castle's AES-256 store type looks its
 * AlgorithmParameters up in the globally registered "BC" provider (so it fails with an explicitly
 * passed provider, and registering a provider globally is not an option). Only the ASN.1 structure is
 * assembled here; every cipher, KDF and MAC is a standard primitive. [read] understands the same
 * profile (what this class and `keytool` write), as the last resort after the platform and Bouncy
 * Castle stores on import.
 *
 * Passwords and key bytes are never logged and never put in exception messages.
 */
/**
 * Bounds on what an imported file may ask this device to compute. The file is untrusted and every
 * password try costs its iteration counts, so a 60-byte file claiming 2^31 iterations would pin the
 * CPU for minutes. A single KDF may use up to [MAX_ITERATIONS] (OpenSSL 3 writes 2048, JDK 17
 * `keytool` 10 000, this app 600 000, so the cap leaves more than headroom) and one file up to
 * [TOTAL_BUDGET] across all of its KDFs (our own file needs 1.8 million: MAC, certificates, key).
 * [MAX_BAGS] bounds the number of containers and bags, i.e. how many KDF runs a file can ask for.
 */
internal object Pkcs12Limits {
    const val MAX_BYTES = 1 shl 20
    const val MAX_ITERATIONS = 1_000_000
    const val TOTAL_BUDGET = 3_000_000L
    const val MAX_BAGS = 64

    /** The iteration count in [value], or a [KeyImportException] when it is not in 1..[MAX_ITERATIONS]. */
    fun iterations(value: java.math.BigInteger): Int {
        if (value.bitLength() > 31 || value.signum() <= 0 || value.toInt() > MAX_ITERATIONS) {
            throw KeyImportException(KeyImportException.Reason.TooManyIterations)
        }
        return value.toInt()
    }

    /** Tracks the KDF work a file has asked for so far. */
    class Budget {
        private var spent = 0L
        private var bags = 0

        fun spend(iterations: Int) {
            spent += iterations
            if (spent > TOTAL_BUDGET) throw KeyImportException(KeyImportException.Reason.TooManyIterations)
        }

        fun countBag() {
            if (++bags > MAX_BAGS) throw KeyImportException(KeyImportException.Reason.TooManyIterations)
        }
    }
}

/**
 * Cheap structural scan of an untrusted PFX that runs before any KeyStore sees it, because the
 * platform and Bouncy Castle readers have no iteration cap of their own. It needs no password and
 * performs no key derivation. Everything it can see is checked: the MAC (which must exist), and the
 * encryption parameters of the plain SafeContents and of the shrouded key bags. Encrypted
 * SafeContents are opaque until decrypted, so [ModernPkcs12.read] keeps charging a [Pkcs12Limits.Budget].
 */
internal object Pkcs12Guard {
    fun check(bytes: ByteArray) {
        if (bytes.size > Pkcs12Limits.MAX_BYTES) throw KeyImportException(KeyImportException.Reason.NotPkcs12)
        try {
            scan(bytes)
        } catch (e: KeyImportException) {
            throw e
        } catch (e: Exception) {
            throw KeyImportException(KeyImportException.Reason.WrongPasswordOrNotPkcs12)
        }
    }

    private fun scan(bytes: ByteArray) {
        val pfx = Pfx.getInstance(ASN1Primitive.fromByteArray(bytes))
        val macData = pfx.macData ?: throw KeyImportException(KeyImportException.Reason.WrongPasswordOrNotPkcs12)
        // Only a plain digest MAC: PBMAC1 carries its own KDF and cost outside the iteration count.
        if (macData.mac.algorithmId.algorithm !in MAC_DIGESTS) throw KeyImportException(KeyImportException.Reason.NotPkcs12)
        val budget = Pkcs12Limits.Budget()
        budget.spend(Pkcs12Limits.iterations(macData.iterationCount))
        val authSafeInfo = pfx.authSafe
        if (authSafeInfo.contentType != PKCSObjectIdentifiers.data) throw KeyImportException(KeyImportException.Reason.NotPkcs12)
        val authSafe = ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(ASN1OctetString.getInstance(authSafeInfo.content).octets))
        for (item in authSafe) {
            budget.countBag()
            val info = ContentInfo.getInstance(item)
            when (info.contentType) {
                PKCSObjectIdentifiers.encryptedData ->
                    scanAlgorithm(EncryptedData.getInstance(info.content).encryptionAlgorithm, budget)
                PKCSObjectIdentifiers.data -> {
                    val bags = ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(ASN1OctetString.getInstance(info.content).octets))
                    for (bagItem in bags) {
                        budget.countBag()
                        val bag = SafeBag.getInstance(bagItem)
                        if (bag.bagId == PKCSObjectIdentifiers.pkcs8ShroudedKeyBag) {
                            scanAlgorithm(EncryptedPrivateKeyInfo.getInstance(bag.bagValue).encryptionAlgorithm, budget)
                        }
                    }
                }
                else -> throw KeyImportException(KeyImportException.Reason.NotPkcs12)
            }
        }
    }

    /**
     * PBES2 carries its count in the PBKDF2 parameters; the legacy PKCS#12 and PKCS#5 PBE schemes in a
     * `{salt, iterations}` pair. Anything else (scrypt, unknown schemes) is refused, since its cost
     * could not be bounded here.
     */
    private fun scanAlgorithm(algorithm: AlgorithmIdentifier, budget: Pkcs12Limits.Budget) {
        val params = algorithm.parameters
        if (algorithm.algorithm == PKCSObjectIdentifiers.id_PBES2) {
            val kdf = PBES2Parameters.getInstance(params).keyDerivationFunc
            if (kdf.algorithm != PKCSObjectIdentifiers.id_PBKDF2) throw KeyImportException(KeyImportException.Reason.NotPkcs12)
            budget.spend(Pkcs12Limits.iterations(PBKDF2Params.getInstance(kdf.parameters).iterationCount))
            return
        }
        val id = algorithm.algorithm.id
        if (!id.startsWith(PKCS12_PBE_PREFIX) && id !in PKCS5_PBES1) throw KeyImportException(KeyImportException.Reason.NotPkcs12)
        val sequence = ASN1Sequence.getInstance(params)
        if (sequence.size() < 2) throw KeyImportException(KeyImportException.Reason.NotPkcs12)
        budget.spend(Pkcs12Limits.iterations(ASN1Integer.getInstance(sequence.getObjectAt(1)).value))
    }

    /** SHA-1 and the SHA-2 family: the digests a classic PKCS#12 MAC uses. */
    private val MAC_DIGESTS = setOf(
        ASN1ObjectIdentifier("1.3.14.3.2.26"),
        NISTObjectIdentifiers.id_sha224,
        NISTObjectIdentifiers.id_sha256,
        NISTObjectIdentifiers.id_sha384,
        NISTObjectIdentifiers.id_sha512,
    )

    /** pkcs-12PbeIds (1.2.840.113549.1.12.1.x). */
    private const val PKCS12_PBE_PREFIX = "1.2.840.113549.1.12.1."

    /** PKCS#5 PBES1: pbeWithMD2/MD5/SHA1 and DES/RC2. */
    private val PKCS5_PBES1 = setOf(1, 3, 4, 6, 10, 11).map { "1.2.840.113549.1.5.$it" }.toSet()
}

internal object ModernPkcs12 {
    private const val KDF_ITERATIONS = 600_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 16
    private const val AES_KEY_BYTES = 32

    private val OID_DATA = PKCSObjectIdentifiers.data
    private val OID_ENCRYPTED_DATA = PKCSObjectIdentifiers.encryptedData
    private val OID_SHROUDED_KEY_BAG = PKCSObjectIdentifiers.pkcs8ShroudedKeyBag
    private val OID_CERT_BAG = PKCSObjectIdentifiers.certBag
    private val OID_X509_CERT = PKCSObjectIdentifiers.x509Certificate
    private val OID_SHA256 = NISTObjectIdentifiers.id_sha256
    private val OID_SHA1 = ASN1ObjectIdentifier("1.3.14.3.2.26")

    /** A PKCS#12 file with [alias] holding [key] and [chain] (leaf first), protected by [password]. */
    fun write(alias: String, key: PrivateKey, chain: List<X509Certificate>, password: CharArray): ByteArray {
        require(chain.isNotEmpty())
        val random = SecureRandom()
        val localKeyId = DEROctetString(MessageDigest.getInstance("SHA-256").digest(chain.first().encoded).copyOf(20))
        val keyAttributes = bagAttributes(alias, localKeyId)

        val keyInfo = key.encoded ?: throw IOException("the key cannot be exported")
        val shrouded = try {
            val (algorithm, encrypted) = encryptPbes2(keyInfo, password, random)
            EncryptedPrivateKeyInfo(algorithm, encrypted)
        } finally {
            keyInfo.fill(0)
        }
        val keyBag = SafeBag(OID_SHROUDED_KEY_BAG, shrouded, keyAttributes)
        val certBags = chain.mapIndexed { index, cert ->
            SafeBag(
                OID_CERT_BAG,
                CertBag(OID_X509_CERT, DEROctetString(cert.encoded)),
                if (index == 0) bagAttributes(alias, localKeyId) else null,
            )
        }

        val keyContent = ContentInfo(OID_DATA, DEROctetString(DERSequence(arrayOf<ASN1Encodable>(keyBag)).getEncoded(ASN1Encoding.DER)))
        val (certAlgorithm, certCipher) = encryptPbes2(
            DERSequence(certBags.toTypedArray<ASN1Encodable>()).getEncoded(ASN1Encoding.DER), password, random,
        )
        val certContent = ContentInfo(
            OID_ENCRYPTED_DATA,
            DERSequence(
                arrayOf<ASN1Encodable>(
                    ASN1Integer(0),
                    DERSequence(arrayOf<ASN1Encodable>(OID_DATA, certAlgorithm, DERTaggedObject(false, 0, DEROctetString(certCipher)))),
                ),
            ),
        )
        val authSafe = DERSequence(arrayOf<ASN1Encodable>(certContent, keyContent)).getEncoded(ASN1Encoding.DER)

        val macSalt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val mac = mac(SHA256Digest(), password, macSalt, KDF_ITERATIONS, authSafe)
        val macData = MacData(DigestInfo(AlgorithmIdentifier(OID_SHA256, DERNull.INSTANCE), mac), macSalt, KDF_ITERATIONS)
        return Pfx(ContentInfo(OID_DATA, DEROctetString(authSafe)), macData).getEncoded(ASN1Encoding.DER)
    }

    /**
     * The single private key and its certificates (leaf first) in [bytes]. Throws [IOException] for
     * anything it cannot read, a wrong password included.
     */
    fun read(bytes: ByteArray, password: CharArray): Pair<PrivateKey, List<X509Certificate>> = try {
        readUnchecked(bytes, password)
    } catch (e: IOException) {
        throw e
    } catch (e: GeneralSecurityException) {
        throw IOException("the PKCS#12 file cannot be read")
    } catch (e: RuntimeException) {
        // Malformed ASN.1 surfaces as several unchecked exception types.
        throw IOException("the PKCS#12 file cannot be read")
    }

    private fun readUnchecked(bytes: ByteArray, password: CharArray): Pair<PrivateKey, List<X509Certificate>> {
        if (bytes.size > Pkcs12Limits.MAX_BYTES) throw KeyImportException(KeyImportException.Reason.NotPkcs12)
        val budget = Pkcs12Limits.Budget()
        val pfx = Pfx.getInstance(ASN1Primitive.fromByteArray(bytes))
        val authSafeInfo = pfx.authSafe
        if (authSafeInfo.contentType != OID_DATA) throw IOException("unsupported PKCS#12 content")
        val authSafe = ASN1OctetString.getInstance(authSafeInfo.content).octets
        // Without a MAC nothing would tell a wrong password from a right one, so such files are refused.
        verifyMac(pfx.macData ?: throw IOException("the PKCS#12 file has no MAC"), password, authSafe, budget)

        val keys = mutableListOf<Pair<PrivateKey, ASN1Set?>>()
        val certs = mutableListOf<Pair<X509Certificate, ASN1Set?>>()
        for (item in ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(authSafe))) {
            budget.countBag()
            val info = ContentInfo.getInstance(item)
            val safeContents = when (info.contentType) {
                OID_DATA -> ASN1OctetString.getInstance(info.content).octets
                OID_ENCRYPTED_DATA -> {
                    val encrypted = EncryptedData.getInstance(info.content)
                    decryptPbes2(encrypted.encryptionAlgorithm, encrypted.content.octets, password, budget)
                }
                else -> throw IOException("unsupported PKCS#12 content")
            }
            for (bagItem in ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(safeContents))) {
                budget.countBag()
                val bag = SafeBag.getInstance(bagItem)
                when (bag.bagId) {
                    OID_SHROUDED_KEY_BAG -> {
                        val shrouded = EncryptedPrivateKeyInfo.getInstance(bag.bagValue)
                        val plain = decryptPbes2(shrouded.encryptionAlgorithm, shrouded.encryptedData, password, budget)
                        try {
                            keys += privateKey(PrivateKeyInfo.getInstance(plain)) to bag.bagAttributes
                        } finally {
                            plain.fill(0)
                        }
                    }
                    OID_CERT_BAG -> {
                        val certBag = CertBag.getInstance(bag.bagValue)
                        if (certBag.certId == OID_X509_CERT) {
                            val der = ASN1OctetString.getInstance(certBag.certValue).octets
                            val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der))
                            certs += (cert as X509Certificate) to bag.bagAttributes
                        }
                    }
                }
            }
        }
        val (key, keyAttributes) = keys.singleOrNull() ?: throw KeyImportException(KeyImportException.Reason.NotOneKey)
        if (certs.isEmpty()) throw KeyImportException(KeyImportException.Reason.NoCertificate)
        val keyId = localKeyId(keyAttributes)
        val leaf = certs.firstOrNull { keyId != null && localKeyId(it.second)?.contentEquals(keyId) == true } ?: certs.first()
        return key to (listOf(leaf.first) + certs.filter { it !== leaf }.map { it.first })
    }

    private fun privateKey(info: PrivateKeyInfo): PrivateKey {
        val algorithm = when (info.privateKeyAlgorithm.algorithm) {
            PKCSObjectIdentifiers.rsaEncryption -> "RSA"
            org.bouncycastle.asn1.x9.X9ObjectIdentifiers.id_ecPublicKey -> "EC"
            else -> throw KeyImportException(KeyImportException.Reason.UnsupportedKeyType)
        }
        return KeyFactory.getInstance(algorithm).generatePrivate(PKCS8EncodedKeySpec(info.getEncoded(ASN1Encoding.DER)))
    }

    private fun localKeyId(attributes: ASN1Set?): ByteArray? {
        attributes ?: return null
        for (attribute in attributes) {
            val sequence = ASN1Sequence.getInstance(attribute)
            if (sequence.getObjectAt(0) == PKCSObjectIdentifiers.pkcs_9_at_localKeyId) {
                return ASN1OctetString.getInstance(ASN1Set.getInstance(sequence.getObjectAt(1)).getObjectAt(0)).octets
            }
        }
        return null
    }

    private fun bagAttributes(alias: String, localKeyId: ASN1Encodable): ASN1Set = DERSet(
        arrayOf<ASN1Encodable>(
            DERSequence(arrayOf<ASN1Encodable>(PKCSObjectIdentifiers.pkcs_9_at_friendlyName, DERSet(DERBMPString(alias)))),
            DERSequence(arrayOf<ASN1Encodable>(PKCSObjectIdentifiers.pkcs_9_at_localKeyId, DERSet(localKeyId))),
        ),
    )

    private fun encryptPbes2(plain: ByteArray, password: CharArray, random: SecureRandom): Pair<AlgorithmIdentifier, ByteArray> {
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val prf = AlgorithmIdentifier(PKCSObjectIdentifiers.id_hmacWithSHA256, DERNull.INSTANCE)
        val params = PBES2Parameters(
            KeyDerivationFunc(PKCSObjectIdentifiers.id_PBKDF2, PBKDF2Params(salt, KDF_ITERATIONS, AES_KEY_BYTES, prf)),
            EncryptionScheme(NISTObjectIdentifiers.id_aes256_CBC, DEROctetString(iv)),
        )
        val key = pbkdf2(password, salt, KDF_ITERATIONS, AES_KEY_BYTES, SHA256Digest())
        try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            return AlgorithmIdentifier(PKCSObjectIdentifiers.id_PBES2, params) to cipher.doFinal(plain)
        } finally {
            key.fill(0)
        }
    }

    private fun decryptPbes2(algorithm: AlgorithmIdentifier, cipherText: ByteArray, password: CharArray, budget: Pkcs12Limits.Budget): ByteArray {
        if (algorithm.algorithm != PKCSObjectIdentifiers.id_PBES2) throw IOException("unsupported PKCS#12 encryption")
        val params = PBES2Parameters.getInstance(algorithm.parameters)
        val kdf = params.keyDerivationFunc
        if (kdf.algorithm != PKCSObjectIdentifiers.id_PBKDF2) throw IOException("unsupported PKCS#12 encryption")
        val pbkdf2 = PBKDF2Params.getInstance(kdf.parameters)
        val scheme = params.encryptionScheme
        val keyBytes = when (scheme.algorithm) {
            NISTObjectIdentifiers.id_aes128_CBC -> 16
            NISTObjectIdentifiers.id_aes192_CBC -> 24
            NISTObjectIdentifiers.id_aes256_CBC -> 32
            else -> throw IOException("unsupported PKCS#12 encryption")
        }
        val iterations = Pkcs12Limits.iterations(pbkdf2.iterationCount)
        budget.spend(iterations)
        val iv = ASN1OctetString.getInstance(scheme.parameters).octets
        val key = pbkdf2(password, pbkdf2.salt, iterations, keyBytes, digestForPrf(pbkdf2.prf.algorithm))
        try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            return try {
                cipher.doFinal(cipherText)
            } catch (e: GeneralSecurityException) {
                throw IOException("wrong password or damaged file")
            }
        } finally {
            key.fill(0)
        }
    }

    private fun verifyMac(macData: MacData, password: CharArray, authSafe: ByteArray, budget: Pkcs12Limits.Budget) {
        val digest = digestForOid(macData.mac.algorithmId.algorithm)
        val iterations = Pkcs12Limits.iterations(macData.iterationCount)
        budget.spend(iterations)
        val expected = mac(digest, password, macData.salt, iterations, authSafe)
        if (!MessageDigest.isEqual(expected, macData.mac.digest)) throw IOException("wrong password or damaged file")
    }

    /** The PKCS#12 MAC: HMAC keyed by the PKCS#12 KDF (RFC 7292 appendix B, ID 3) over the AuthenticatedSafe. */
    private fun mac(digest: Digest, password: CharArray, salt: ByteArray, iterations: Int, data: ByteArray): ByteArray {
        val passwordBytes = PKCS12ParametersGenerator.PKCS12PasswordToBytes(password)
        try {
            val generator = PKCS12ParametersGenerator(digest)
            generator.init(passwordBytes, salt, iterations)
            val key = (generator.generateDerivedMacParameters(digest.digestSize * 8) as KeyParameter).key
            try {
                val hmac = HMac(newDigest(digest))
                hmac.init(KeyParameter(key))
                hmac.update(data, 0, data.size)
                return ByteArray(hmac.macSize).also { hmac.doFinal(it, 0) }
            } finally {
                key.fill(0)
            }
        } finally {
            passwordBytes.fill(0)
        }
    }

    private fun newDigest(like: Digest): Digest = when (like) {
        is SHA1Digest -> SHA1Digest()
        is SHA256Digest -> SHA256Digest()
        is SHA384Digest -> SHA384Digest()
        is SHA512Digest -> SHA512Digest()
        else -> throw IOException("unsupported PKCS#12 MAC")
    }

    private fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int, keyBytes: Int, digest: Digest): ByteArray {
        val passwordBytes = PKCS5S2ParametersGenerator.PKCS5PasswordToUTF8Bytes(password)
        try {
            val generator = PKCS5S2ParametersGenerator(digest)
            generator.init(passwordBytes, salt, iterations)
            return (generator.generateDerivedParameters(keyBytes * 8) as KeyParameter).key
        } finally {
            passwordBytes.fill(0)
        }
    }

    private fun digestForOid(oid: ASN1ObjectIdentifier): Digest = when (oid) {
        OID_SHA1 -> SHA1Digest()
        NISTObjectIdentifiers.id_sha256 -> SHA256Digest()
        NISTObjectIdentifiers.id_sha384 -> SHA384Digest()
        NISTObjectIdentifiers.id_sha512 -> SHA512Digest()
        else -> throw IOException("unsupported PKCS#12 MAC")
    }

    private fun digestForPrf(oid: ASN1ObjectIdentifier): Digest = when (oid) {
        PKCSObjectIdentifiers.id_hmacWithSHA1 -> SHA1Digest()
        PKCSObjectIdentifiers.id_hmacWithSHA224 -> SHA224Digest()
        PKCSObjectIdentifiers.id_hmacWithSHA256 -> SHA256Digest()
        PKCSObjectIdentifiers.id_hmacWithSHA384 -> SHA384Digest()
        PKCSObjectIdentifiers.id_hmacWithSHA512 -> SHA512Digest()
        else -> throw IOException("unsupported PKCS#12 encryption")
    }
}
