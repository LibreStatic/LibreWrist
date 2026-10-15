package io.openshm.patcher

import io.openshm.patcher.build.BuildAssets
import io.openshm.patcher.build.outputFingerprint
import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.LoadedCatalogue
import io.openshm.patcher.engine.OutputSource
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputFingerprintTest {
    private val catalogue = TestCatalogue.load()
    private val engine = catalogue.engine

    private fun patched(): List<Pair<String, OutputSource.Patched>> =
        engine.outputs.mapNotNull { o -> (o.from as? OutputSource.Patched)?.let { o.id to it } }

    /** Asset bytes are derived from the path unless overridden. */
    private fun assets(overrides: Map<String, String> = emptyMap()): (String) -> java.io.InputStream = { path ->
        ByteArrayInputStream((overrides[path] ?: "asset:$path").toByteArray())
    }

    private fun fp(
        id: String,
        catalogue: LoadedCatalogue = this.catalogue,
        prefix: String = "com.example",
        disabled: Collection<String> = emptyList(),
        cert: String = "aa",
        engineFp: String = "e1",
        ownCert: String? = "bb",
        open: (String) -> java.io.InputStream = assets(),
    ) = outputFingerprint(catalogue, id, prefix, disabled, cert, engineFp, ownCert, open)

    private val signer = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** The bundled catalogue with one text edit, signed with a throwaway key (never the real one). */
    private fun modified(from: String, to: String): LoadedCatalogue {
        val text = String(TestCatalogue.bytes)
        assertTrue("'$from' not in the catalogue", from in text)
        val bytes = text.replace(from, to).toByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").run { initSign(signer.private); update(bytes); sign() }
        val pem = "-----BEGIN PUBLIC KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(signer.public.encoded) + "\n-----END PUBLIC KEY-----\n"
        return AppCatalogue.fromBytes(bytes, signature, pem, BuildConfig.VERSION_CODE, TestCatalogue.locales)
    }

    @Test fun identicalInputsGiveTheSameFingerprint() {
        for (o in engine.outputs) assertEquals(fp(o.id), fp(o.id))
        assertEquals(64, fp(engine.outputs.first().id).length)
    }

    @Test fun everyOutputHasItsOwnFingerprint() {
        assertEquals(engine.outputs.size, engine.outputs.map { fp(it.id) }.toSet().size)
    }

    @Test fun prefixCertificateAndEngineChangeEveryOutput() {
        for (o in engine.outputs) {
            val base = fp(o.id)
            assertNotEquals(base, fp(o.id, prefix = "com.other"))
            assertNotEquals(base, fp(o.id, cert = "cc"))
            assertNotEquals(base, fp(o.id, engineFp = "e2"))
        }
    }

    @Test fun inputShaChangesOnlyOutputsOfThatInput() {
        val (id, from) = patched().first()
        val sha = catalogue.input(from.input).sha256
        val other = modified(sha, "d".repeat(64))
        assertNotEquals(fp(id), fp(id, catalogue = other))
        for ((otherId, f) in patched()) if (f.input != from.input) assertEquals(fp(otherId), fp(otherId, catalogue = other))
    }

    @Test fun aPatternStepChangesOnlyOutputsThatApplyIt() {
        val patternId = "shm-min-age-22-to-16"
        val other = modified("\"new\": \"const/16 v0, 0x10\",\n          \"context\": \"shealthmonitor/util/c\"", "\"new\": \"const/16 v0, 0x11\",\n          \"context\": \"shealthmonitor/util/c\"")
        assertNotEquals(engine.patterns.getValue(patternId).toString(), other.engine.patterns.getValue(patternId).toString())
        var changed = 0
        for ((id, from) in patched()) {
            val applies = patternId in engine.profile(from.profile).families[from.input].orEmpty()
            if (applies) { assertNotEquals(fp(id), fp(id, catalogue = other)); changed++ }
            else assertEquals(fp(id), fp(id, catalogue = other))
        }
        assertTrue(changed > 0)
        for (o in engine.outputs.filter { it.from is OutputSource.OwnApp }) assertEquals(fp(o.id), fp(o.id, catalogue = other))
    }

    @Test fun disabledPatternsOnlyCountInTheirFamily() {
        val (id, from) = patched().first()
        val family = engine.profile(from.profile).families[from.input].orEmpty()
        val optional = family.first { engine.patterns.getValue(it).optional }
        val foreign = engine.patterns.keys.first { it !in family }
        val base = fp(id)
        assertNotEquals(base, fp(id, disabled = listOf(optional)))
        assertEquals(base, fp(id, disabled = listOf(foreign)))
        assertEquals(fp(id, disabled = listOf(optional)), fp(id, disabled = listOf(foreign, optional)))
    }

    @Test fun injectionDexBytesChangeThePatchedOutputsThatUseThem() {
        var checked = 0
        for ((id, from) in patched()) {
            val names = engine.profile(from.profile).inject[from.input].orEmpty()
            for (name in names) {
                val open = assets(mapOf(BuildAssets.injection(name) to "different"))
                assertNotEquals(fp(id), fp(id, open = open))
                checked++
            }
        }
        assertTrue(checked > 0)
    }

    @Test fun bridgeApkBytesChangeTheOwnApp() {
        val own = engine.outputs.mapNotNull { o -> (o.from as? OutputSource.OwnApp)?.let { o.id to it } }
        assertTrue(own.isNotEmpty())
        for ((id, from) in own) {
            assertNotEquals(fp(id), fp(id, open = assets(mapOf(BuildAssets.ownApp(from.path) to "different"))))
        }
    }

    @Test fun onlyThePhoneBridgeDependsOnThePatcherCertificate() {
        for (o in engine.outputs) {
            if (o.id == "bridge-phone") assertNotEquals(fp(o.id), fp(o.id, ownCert = "zz"))
            else assertEquals(fp(o.id), fp(o.id, ownCert = "zz"))
        }
    }

    @Test fun aMissingAssetFails() {
        val (id, _) = patched().first { (_, f) -> engine.profile(f.profile).inject[f.input].orEmpty().isNotEmpty() }
        try {
            fp(id, open = { throw FileNotFoundException(it) })
            throw AssertionError("expected a failure")
        } catch (e: FileNotFoundException) {
            // expected: a missing built-in file must not silently produce a fingerprint
        }
    }
}
