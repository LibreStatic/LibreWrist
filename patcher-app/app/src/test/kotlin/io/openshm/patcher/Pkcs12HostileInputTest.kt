package io.openshm.patcher

import io.openshm.patcher.data.KeyImportException
import io.openshm.patcher.data.KeyImportException.Reason
import io.openshm.patcher.data.ModernPkcs12
import io.openshm.patcher.data.Pkcs12Limits
import io.openshm.patcher.data.SigningKeyStore
import io.openshm.patcher.data.readBounded
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.KeyStore
import javax.crypto.KeyGenerator
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers
import org.bouncycastle.asn1.pkcs.ContentInfo
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.DigestInfo
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Files an attacker or a broken tool could hand to the import: they must fail fast and with a stable reason. */
class Pkcs12HostileInputTest {
    private lateinit var dir: File
    private val password = "hostile-test-pass".toCharArray()

    @Before fun setUp() { dir = Files.createTempDirectory("hostile-test").toFile() }

    @After fun tearDown() { dir.deleteRecursively() }

    private val wrap = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun store() = SigningKeyStore(dir) { wrap }

    private val good: ByteArray by lazy { store().exportPkcs12(password) }

    private fun assertRejected(reason: Reason, bytes: ByteArray) {
        val started = System.nanoTime()
        try {
            store().inspectPkcs12(bytes, password)
            fail("accepted a hostile file")
        } catch (e: KeyImportException) {
            assertEquals(reason, e.reason)
        }
        assertTrue("took too long", (System.nanoTime() - started) / 1_000_000 < 10_000)
    }

    /** A PFX whose MAC claims [iterations] and whose content is empty: only the pre-scan can reject it quickly. */
    private fun pfxWithMacIterations(iterations: BigInteger): ByteArray {
        val digest = DigestInfo(AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256, DERNull.INSTANCE), ByteArray(32))
        val mac = DERSequence(arrayOf<ASN1Encodable>(digest, DEROctetString(ByteArray(16)), ASN1Integer(iterations)))
        val authSafe = ContentInfo(PKCSObjectIdentifiers.data, DEROctetString(DERSequence().encoded))
        return DERSequence(arrayOf<ASN1Encodable>(ASN1Integer(3), authSafe, mac)).encoded
    }

    private fun indexesOf(bytes: ByteArray, needle: ByteArray): List<Int> =
        (0..bytes.size - needle.size).filter { i -> needle.indices.all { bytes[i + it] == needle[it] } }

    // DER INTEGER 600000.
    private val iterations600k = byteArrayOf(0x02, 0x03, 0x09, 0x27, 0xC0.toByte())

    /** [good] with the n-th occurrence of the 600 000 count replaced by 8 388 607 (same length, still valid DER). */
    private fun withHugeCount(occurrence: Int): ByteArray {
        val at = indexesOf(good, iterations600k)[occurrence]
        return good.copyOf().also { it[at + 2] = 0x7F; it[at + 3] = 0xFF.toByte(); it[at + 4] = 0xFF.toByte() }
    }

    private fun withoutMac(): ByteArray {
        val seq = ASN1Sequence.getInstance(good)
        return DERSequence(arrayOf(seq.getObjectAt(0), seq.getObjectAt(1))).encoded
    }

    private fun withTamperedMac(): ByteArray = good.copyOf().also { it[it.size - 24] = (it[it.size - 24].toInt() xor 0x01).toByte() }

    private fun twoKeys(): ByteArray {
        val original = store().load()
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        ks.setKeyEntry("a", original.privateKey, password, arrayOf(original.certificates.first()))
        ks.setKeyEntry("b", other.private, password, arrayOf(original.certificates.first()))
        return java.io.ByteArrayOutputStream().also { ks.store(it, password) }.toByteArray()
    }

    @Test fun theGoodFileIsAccepted() {
        assertEquals(store().certificateSha256(), store().inspectPkcs12(good, password))
        // The three counts in our own file are the ones patched below: certificates, key, MAC.
        assertEquals(3, indexesOf(good, iterations600k).size)
    }

    @Test fun aHugeMacCountIsRejectedByThePreScanBeforeAnyReaderRuns() {
        assertRejected(Reason.TooManyIterations, pfxWithMacIterations(BigInteger.valueOf(Int.MAX_VALUE.toLong())))
        assertRejected(Reason.TooManyIterations, pfxWithMacIterations(BigInteger.ONE.shiftLeft(40)))
        assertRejected(Reason.TooManyIterations, pfxWithMacIterations(BigInteger.ZERO))
        assertRejected(Reason.TooManyIterations, pfxWithMacIterations(BigInteger.valueOf(Pkcs12Limits.MAX_ITERATIONS + 1L)))
    }

    /** A PFX whose MAC digest is [macAlgorithm] and whose content is [contents] (empty by default). */
    private fun pfx(macAlgorithm: AlgorithmIdentifier, vararg contents: ContentInfo): ByteArray {
        val mac = DERSequence(arrayOf<ASN1Encodable>(DigestInfo(macAlgorithm, ByteArray(32)), DEROctetString(ByteArray(16)), ASN1Integer(1)))
        val authSafe = ContentInfo(PKCSObjectIdentifiers.data, DEROctetString(DERSequence(arrayOf<ASN1Encodable>(*contents)).encoded))
        return DERSequence(arrayOf<ASN1Encodable>(ASN1Integer(3), authSafe, mac)).encoded
    }

    private val sha256Mac = AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256, DERNull.INSTANCE)

    @Test fun aMacWhoseCostIsNotTheIterationCountIsRejected() {
        // PBMAC1 (RFC 9579) keeps its PBKDF2 count inside the algorithm parameters.
        val pbmac1 = AlgorithmIdentifier(org.bouncycastle.asn1.ASN1ObjectIdentifier("1.2.840.113549.1.5.14"), DERNull.INSTANCE)
        assertRejected(Reason.NotPkcs12, pfx(pbmac1))
    }

    @Test fun aPbes2SchemeWithoutPbkdf2IsRejected() {
        // id-scrypt (RFC 7914) with salt, cost 2^30, block size 8, parallelism 1, key length 32.
        val scryptParams = DERSequence(
            arrayOf<ASN1Encodable>(DEROctetString(ByteArray(16)), ASN1Integer(1L shl 30), ASN1Integer(8), ASN1Integer(1), ASN1Integer(32)),
        )
        val scrypt = org.bouncycastle.asn1.pkcs.KeyDerivationFunc(
            org.bouncycastle.asn1.ASN1ObjectIdentifier("1.3.6.1.4.1.11591.4.11"),
            scryptParams,
        )
        val scheme = org.bouncycastle.asn1.pkcs.EncryptionScheme(NISTObjectIdentifiers.id_aes256_CBC, DEROctetString(ByteArray(16)))
        val algorithm = AlgorithmIdentifier(PKCSObjectIdentifiers.id_PBES2, org.bouncycastle.asn1.pkcs.PBES2Parameters(scrypt, scheme))
        val encrypted = org.bouncycastle.asn1.pkcs.EncryptedData(PKCSObjectIdentifiers.data, algorithm, DEROctetString(ByteArray(32)))
        assertRejected(Reason.NotPkcs12, pfx(sha256Mac, ContentInfo(PKCSObjectIdentifiers.encryptedData, encrypted)))
    }

    @Test fun aHugePbkdf2CountInAPlainBagOrInTheEncryptedDataHeaderIsRejected() {
        assertRejected(Reason.TooManyIterations, withHugeCount(0))
        assertRejected(Reason.TooManyIterations, withHugeCount(1))
        assertRejected(Reason.TooManyIterations, withHugeCount(2))
    }

    @Test fun theCapAdmitsWhatRealToolsWrite() {
        // OpenSSL 3 writes 2048, JDK 17 keytool 10 000, this app 600 000.
        for (count in listOf(2048, 10_000, 600_000)) {
            assertEquals(count, Pkcs12Limits.iterations(BigInteger.valueOf(count.toLong())))
        }
        val budget = Pkcs12Limits.Budget()
        repeat(3) { budget.spend(600_000) } // our own file: certificates, key, MAC
    }

    @Test fun theTotalWorkAndBagCountAreBounded() {
        val work = Pkcs12Limits.Budget()
        repeat(3) { work.spend(Pkcs12Limits.MAX_ITERATIONS) }
        try {
            work.spend(1)
            fail("budget not enforced")
        } catch (e: KeyImportException) {
            assertEquals(Reason.TooManyIterations, e.reason)
        }
        val bags = Pkcs12Limits.Budget()
        repeat(Pkcs12Limits.MAX_BAGS) { bags.countBag() }
        try {
            bags.countBag()
            fail("bag count not enforced")
        } catch (e: KeyImportException) {
            assertEquals(Reason.TooManyIterations, e.reason)
        }
    }

    @Test fun aFileWithoutAMacIsRejected() {
        assertRejected(Reason.WrongPasswordOrNotPkcs12, withoutMac())
        try {
            ModernPkcs12.read(withoutMac(), password)
            fail("read a file without a MAC")
        } catch (expected: IOException) {
        }
        // Not even with a different password.
        try {
            ModernPkcs12.read(withoutMac(), "another-password".toCharArray())
            fail("read a file without a MAC")
        } catch (expected: IOException) {
        }
    }

    @Test fun aTamperedMacIsRejected() {
        assertRejected(Reason.WrongPasswordOrNotPkcs12, withTamperedMac())
        try {
            ModernPkcs12.read(withTamperedMac(), password)
            fail("read a file with a bad MAC")
        } catch (expected: IOException) {
        }
    }

    @Test fun garbageAndOversizedFilesAreRejected() {
        assertRejected(Reason.WrongPasswordOrNotPkcs12, "garbage".toByteArray())
        assertRejected(Reason.WrongPasswordOrNotPkcs12, ByteArray(0))
        assertRejected(Reason.NotPkcs12, ByteArray(Pkcs12Limits.MAX_BYTES + 1))
        val big = store()
        try {
            big.importPkcs12(ByteArrayInputStream(ByteArray(Pkcs12Limits.MAX_BYTES + 5)), password)
            fail("imported an oversized stream")
        } catch (e: KeyImportException) {
            assertEquals(Reason.NotPkcs12, e.reason)
        }
        try {
            ModernPkcs12.read("garbage".toByteArray(), password)
            fail("read garbage")
        } catch (expected: IOException) {
        }
    }

    @Test fun twoKeysAreRejected() {
        val bytes = twoKeys()
        assertRejected(Reason.NotOneKey, bytes)
        try {
            ModernPkcs12.read(bytes, password)
            fail("read two keys")
        } catch (e: KeyImportException) {
            assertEquals(Reason.NotOneKey, e.reason)
        }
    }

    @Test fun theBouncyCastleReaderRefusesWhatIsNotAValidFile() {
        fun loadWithBc(bytes: ByteArray) =
            KeyStore.getInstance("PKCS12", BouncyCastleProvider()).load(ByteArrayInputStream(bytes), password)
        for ((name, bytes) in listOf("garbage" to "garbage".toByteArray(), "tampered MAC" to withTamperedMac())) {
            try {
                loadWithBc(bytes)
                fail("BC loaded: $name")
            } catch (expected: IOException) {
            }
        }
        // BC itself may accept a file without a MAC (no password check); that is why Pkcs12Guard requires one.
        try {
            loadWithBc(withoutMac())
        } catch (expected: IOException) {
        }
        loadWithBc(good) // sanity: the good one loads
        val ks = KeyStore.getInstance("PKCS12", BouncyCastleProvider()).apply { load(ByteArrayInputStream(twoKeys()), password) }
        assertEquals(2, ks.aliases().toList().count { ks.isKeyEntry(it) })
    }

    @Test fun boundedReadStopsOneByteAfterTheLimit() {
        assertEquals(11, ByteArrayInputStream(ByteArray(100)).readBounded(10).size)
        assertEquals(10, ByteArrayInputStream(ByteArray(10)).readBounded(10).size)
        assertEquals(0, ByteArrayInputStream(ByteArray(0)).readBounded(10).size)
    }
}
