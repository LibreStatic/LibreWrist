package io.openshm.patcher.engine

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.math.BigInteger
import java.util.Base64

/**
 * Verifies `catalogue.json.sig` as produced by `scripts/catalogue.py sign`: a DER-encoded ECDSA
 * signature (P-256, SHA-256) over the exact bytes of `catalogue.json`. Uses only `java.security`
 * (available on Android API 31).
 *
 * Fails closed: every malformed input (bad PEM, wrong key type or curve, bad DER, wrong length,
 * provider error) yields `false`, never an exception. Verify BEFORE parsing the catalogue.
 */
object CatalogueVerifier {
    private const val MAX_SIGNATURE_BYTES = 72 // DER ECDSA P-256 is at most 72 bytes

    // NIST P-256 (secp256r1): prime field and group order. Any other 256-bit curve is refused.
    private val P256_P = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    private val P256_N = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)

    /** [publicKeyPem] is the `-----BEGIN PUBLIC KEY-----` text of `catalogue/catalogue.pub`. */
    fun verify(catalogue: ByteArray, signature: ByteArray, publicKeyPem: String): Boolean =
        try {
            verify(catalogue, signature, parsePublicKey(publicKeyPem))
        } catch (e: Exception) {
            false
        }

    fun verify(catalogue: ByteArray, signature: ByteArray, key: PublicKey): Boolean =
        try {
            key is ECPublicKey &&
                isP256(key) &&
                signature.size in 8..MAX_SIGNATURE_BYTES &&
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(key)
                    update(catalogue)
                    verify(signature)
                }
        } catch (e: Exception) {
            false
        }

    private fun isP256(key: ECPublicKey): Boolean {
        val field = key.params.curve.field
        return field is java.security.spec.ECFieldFp && field.p == P256_P && key.params.order == P256_N
    }

    /** Parses a PEM SubjectPublicKeyInfo of an EC key; throws on anything else. */
    fun parsePublicKey(pem: String): PublicKey {
        val lines = pem.trim().lines().map { it.trim() }
        require(lines.size >= 3 && lines.first() == "-----BEGIN PUBLIC KEY-----" &&
            lines.last() == "-----END PUBLIC KEY-----") { "not a PEM public key" }
        val der = Base64.getDecoder().decode(lines.subList(1, lines.size - 1).joinToString(""))
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
    }
}
