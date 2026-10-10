package io.openshm.patcher.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CatalogueTest {
    private val root = File(System.getProperty("shm.repoRoot"))
    private val catalogueBytes = File(root, "catalogue/catalogue.json").readBytes()
    private val signature = File(root, "catalogue/catalogue.json.sig").readBytes()
    private val pem = File(root, "catalogue/catalogue.pub").readText()

    @Test fun committedCatalogueVerifies() {
        assertTrue(CatalogueVerifier.verify(catalogueBytes, signature, pem))
    }

    @Test fun oneByteTamperFails() {
        val t = catalogueBytes.copyOf()
        t[100] = (t[100].toInt() xor 1).toByte()
        assertFalse(CatalogueVerifier.verify(t, signature, pem))
    }

    @Test fun malformedSignatureFailsClosed() {
        assertFalse(CatalogueVerifier.verify(catalogueBytes, ByteArray(0), pem))
        assertFalse(CatalogueVerifier.verify(catalogueBytes, ByteArray(71), pem))
        assertFalse(CatalogueVerifier.verify(catalogueBytes, signature.copyOf(40), pem))
        assertFalse(CatalogueVerifier.verify(catalogueBytes, signature, "not a key"))
    }

    @Test fun keyOnAnotherCurveIsRefused() {
        val gen = java.security.KeyPairGenerator.getInstance("EC")
        gen.initialize(java.security.spec.ECGenParameterSpec("secp384r1"))
        val pair = gen.generateKeyPair()
        val sig = java.security.Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private); update(catalogueBytes); sign()
        }
        // a valid signature, but not on P-256: refused (it is also too long for P-256)
        assertFalse(CatalogueVerifier.verify(catalogueBytes, sig, pair.public))
        assertTrue(CatalogueVerifier.verify(catalogueBytes, signature, CatalogueVerifier.parsePublicKey(pem)))
    }

    @Test fun committedCatalogueParses() {
        val c = Catalogue.parse(String(catalogueBytes, Charsets.UTF_8))
        assertEquals(38, c.patterns.size)
        assertEquals(setOf("gms", "alarm"), c.profiles.keys)
    }

    private fun mutated(edit: (String) -> String) =
        edit(String(catalogueBytes, Charsets.UTF_8))

    @Test fun unknownOpIsRejected() {
        val text = mutated { it.replaceFirst("\"op\": \"manifestExpect\"", "\"op\": \"noSuchOp\"") }
        try {
            Catalogue.parse(text)
            fail()
        } catch (e: CatalogueException) {
            assertTrue(e.problems.any { "unknown op 'noSuchOp'" in it })
        }
    }

    @Test fun secureSettingValueMayNotStartWithDash() {
        val text = mutated { it.replaceFirst("\"value\": \"0\"", "\"value\": \"--user\"") }
        assertTrue(text != String(catalogueBytes, Charsets.UTF_8))
        try {
            Catalogue.parse(text)
            fail()
        } catch (e: CatalogueException) {
            assertTrue(e.problems.any { "field 'value'" in it })
        }
    }

    @Test fun duplicateKeysAreRejected() {
        try {
            Catalogue.parse("{\"schema\": 1, \"schema\": 1}")
            fail()
        } catch (e: CatalogueException) {
            // expected
        }
    }
}
