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
        assertEquals(62, c.patterns.size)
        assertEquals(setOf("gms", "alarm", "shealth"), c.profiles.keys)
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
        val text = mutated {
            it.replaceFirst(
                "\"kind\": \"appop\",\n        \"op\": \"SCHEDULE_EXACT_ALARM\",\n        \"mode\": \"allow\"",
                "\"kind\": \"secureSetting\",\n        \"key\": \"some_setting\",\n        \"value\": \"--user\"",
            )
        }
        assertTrue(text != String(catalogueBytes, Charsets.UTF_8))
        try {
            Catalogue.parse(text)
            fail()
        } catch (e: CatalogueException) {
            assertTrue(e.problems.any { "field 'value'" in it })
        }
    }

    private fun withFirstAppopAs(grantJson: String): String = mutated {
        it.replaceFirst(
            "\"kind\": \"appop\",\n        \"op\": \"SCHEDULE_EXACT_ALARM\",\n        \"mode\": \"allow\"",
            grantJson,
        )
    }

    @Test fun globalSettingGrantIsAccepted() {
        val text = withFirstAppopAs(
            "\"kind\": \"globalSetting\",\n        \"key\": \"some_list_key\",\n        \"value\": \"Lcom/example/other/\"",
        )
        assertTrue(text != String(catalogueBytes, Charsets.UTF_8))
        val c = Catalogue.parse(text)
        val grant = c.grants.getValue("shm-watch").single { it.fields["key"] == "some_list_key" }
        assertEquals("globalSetting", grant.kind)
        assertEquals("Lcom/example/other/", grant.fields.getValue("value"))
        assertFalse(grant.optional)
        // The committed entry for the watch sensor-manager exemption parses too.
        val watch = c.grants.getValue("shm-watch").single { it.fields["key"] == "hidden_api_blacklist_exemptions" }
        assertEquals("Lcom/samsung/android/hardware/sensormanager/", watch.fields.getValue("value"))
    }

    @Test fun globalSettingGrantRejectsUnsafeValues() {
        for ((grantJson, field) in listOf(
            "\"kind\": \"globalSetting\",\n        \"key\": \"hidden_api_blacklist_exemptions\",\n        \"value\": \"Lcom/foo bar/\"" to "value",
            "\"kind\": \"globalSetting\",\n        \"key\": \"hidden_api_blacklist_exemptions\",\n        \"value\": \"Lcom/foo/;id\"" to "value",
            "\"kind\": \"globalSetting\",\n        \"key\": \"hidden_api_blacklist_exemptions\",\n        \"value\": \"Lcom/foo/'\"" to "value",
            "\"kind\": \"globalSetting\",\n        \"key\": \"hidden_api_blacklist_exemptions\",\n        \"value\": \"com/samsung/android/hardware/sensormanager/\"" to "value",
            "\"kind\": \"globalSetting\",\n        \"key\": \"hidden_api_blacklist_exemptions\",\n        \"value\": \"L${"a".repeat(128)}\"" to "value",
            "\"kind\": \"globalSetting\",\n        \"key\": \"Bad Key\",\n        \"value\": \"Lcom/foo/\"" to "key",
            "\"kind\": \"globalSetting\",\n        \"key\": \"hidden_api_blacklist_exemptions\"" to "value",
            "\"kind\": \"globalSetting\",\n        \"key\": \"hidden_api_blacklist_exemptions\",\n        \"value\": \"Lcom/foo/\",\n        \"extra\": \"x\"" to "unknown fields",
        )) {
            val text = withFirstAppopAs(grantJson)
            assertTrue(text != String(catalogueBytes, Charsets.UTF_8))
            try {
                Catalogue.parse(text)
                fail(grantJson)
            } catch (e: CatalogueException) {
                assertTrue(e.problems.toString(), e.problems.any { field in it })
            }
        }
    }

    @Test fun malformedInputsAreCatalogueErrors() {
        for (bad in listOf("5", "{}", "{\"id\": \"x\"}")) {
            val text = mutated { it.replaceFirst("\"inputs\": [", "\"inputs\": [$bad, ") }
            try {
                Catalogue.parse(text)
                fail(bad)
            } catch (e: CatalogueException) {
                assertTrue(e.problems.toString(), e.problems.any { "inputs[0]" in it })
            }
        }
        val dupSha = mutated { it.replaceFirst(Regex("\"sha256\": \"[0-9a-f]{64}\""), "\"sha256\": \"ABC\"") }
        try {
            Catalogue.parse(dupSha)
            fail()
        } catch (e: CatalogueException) {
            assertTrue(e.problems.any { "sha256" in it })
        }
    }

    @Test fun committedCatalogueOptionalPatterns() {
        val c = Catalogue.parse(String(catalogueBytes, Charsets.UTF_8))
        assertEquals(
            setOf(
                "shm-min-age-22-to-16", "watch-min-age-22-to-16", "shm-setup-network-gate", "shm-sleep-apnea-support", "shm-sleep-countries", "watch-apnea-country",
                "watch-apnea-reeval-not-supported", "alarm-force-sound-vibrate", "alarm-hide-launcher", "alarm-relabel",
                "shealth-oobe-local", "shealth-capability-gates", "shealth-launcher-badge", "shm-launcher-badge",
            ),
            c.patterns.values.filter { it.optional }.map { it.id }.toSet(),
        )
        assertEquals(
            setOf("shm-sleep-apnea-support", "shm-sleep-countries", "watch-apnea-country", "watch-apnea-reeval-not-supported"),
            c.patterns.values.filter { it.optionGroup == "sleep-apnea" }.map { it.id }.toSet(),
        )
        assertEquals(
            setOf("shm-min-age-22-to-16", "watch-min-age-22-to-16"),
            c.patterns.values.filter { it.optionGroup == "minimum-age" }.map { it.id }.toSet(),
        )
        assertEquals(
            setOf("shealth-oobe-local"),
            c.patterns.values.filter { it.offByDefault }.map { it.id }.toSet(),
        )
        c.requireDisablable(c.patterns.values.filter { it.optional }.map { it.id }.toSet())
    }

    @Test fun offByDefaultFieldIsTypeChecked() {
        val anchor = "\"offByDefault\": true"
        assertTrue(mutated { it }.contains(anchor))
        for ((bad, expected) in listOf(
            "\"offByDefault\": 5" to "offByDefault must be a boolean",
        )) {
            val mutatedText = mutated { it.replaceFirst(anchor, bad) }
            try {
                Catalogue.parse(mutatedText)
                fail(bad)
            } catch (e: CatalogueException) {
                assertTrue(e.problems.toString(), e.problems.any { expected in it })
            }
        }
        // offByDefault without optional: rewrite the shealth-oobe-local pattern's optional flag
        val both = "\"optional\": true,\n      \"offByDefault\": true"
        val noOptional = mutated { it.replaceFirst(both, "\"optional\": false,\n      \"offByDefault\": true") }
        try {
            Catalogue.parse(noOptional)
            fail(noOptional)
        } catch (e: CatalogueException) {
            assertTrue(e.problems.toString(), e.problems.any { "offByDefault is only allowed on optional" in it })
        }
    }

    @Test fun optionGroupFieldIsTypeChecked() {
        val anchor = "\"optionGroup\": \"sleep-apnea\""
        assertTrue(mutated { it }.contains(anchor))
        for ((bad, expected) in listOf(
            "\"optionGroup\": 5" to "kebab-case",
            "\"optionGroup\": \"Sleep Apnea\"" to "kebab-case",
            "\"optionGroup\": \"lonely\"" to "at least two",
        )) {
            // only the first member changes, so for "lonely" the group of the other two stays valid
            val mutatedText = mutated { it.replaceFirst(anchor, bad) }
            try {
                Catalogue.parse(mutatedText)
                fail(bad)
            } catch (e: CatalogueException) {
                assertTrue(e.problems.toString(), e.problems.any { expected in it })
            }
        }
    }

    @Test fun parseSignedVerifiesBeforeParsing() {
        val key = CatalogueVerifier.parsePublicKey(pem)
        assertEquals(62, Catalogue.parseSigned(catalogueBytes, signature, key).patterns.size)
        val tampered = catalogueBytes.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() }
        try {
            Catalogue.parseSigned(tampered, signature, key)
            fail()
        } catch (e: CatalogueException) {
            assertTrue(e.problems.any { "signature" in it })
        }
    }

    @Test fun duplicateKeysAreRejected() {
        try {
            Catalogue.parse("{\"schema\": 2, \"schema\": 2}")
            fail()
        } catch (e: CatalogueException) {
            // expected
        }
    }
}
