package io.openshm.patcher

import io.openshm.patcher.build.BuiltBuild
import io.openshm.patcher.build.BuiltOutputs
import io.openshm.patcher.build.BuiltRecord
import io.openshm.patcher.build.buildFacts
import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.CatalogueFetcher
import io.openshm.patcher.data.CatalogueUpdater
import io.openshm.patcher.data.HttpsCatalogueFetcher
import io.openshm.patcher.data.LoadedCatalogue
import io.openshm.patcher.data.RefusedResponseException
import io.openshm.patcher.data.StoredCatalogue
import io.openshm.patcher.data.UpdateResult
import io.openshm.patcher.data.UpdateState
import io.openshm.patcher.ui.screens.home.UpdateOffer
import io.openshm.patcher.ui.screens.home.updateOfferFor
import java.io.File
import java.io.IOException
import java.net.URL
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The update channel, with catalogues signed in-test by a throwaway P-256 key (never the real one). */
class CatalogueUpdateTest {
    private class Signer {
        private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val publicKeyPem: String = "-----BEGIN PUBLIC KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.public.encoded) + "\n-----END PUBLIC KEY-----\n"

        fun sign(bytes: ByteArray): ByteArray =
            Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(bytes); sign() }
    }

    private val signer = Signer()
    private val other = Signer()
    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("cat-update").toFile() }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun text(serial: Long = 5, minApp: Long = 1, edit: (String) -> String = { it }): ByteArray {
        val base = String(TestCatalogue.bytes, Charsets.UTF_8)
        val withSerial = base.replace("\"serial\": 5,", "\"serial\": $serial,").replace("\"minAppVersion\": 1,", "\"minAppVersion\": $minApp,")
        assertTrue(withSerial.contains("\"serial\": $serial,"))
        return edit(withSerial).toByteArray(Charsets.UTF_8)
    }

    private fun load(bytes: ByteArray): LoadedCatalogue =
        AppCatalogue.fromBytes(bytes, signer.sign(bytes), signer.publicKeyPem, 1, TestCatalogue.resolve)

    private class MemState(override var highestSerial: Long = 0) : UpdateState

    private class FakeFetcher(var json: ByteArray, var sig: ByteArray) : CatalogueFetcher {
        var failure: IOException? = null
        val urls = mutableListOf<String>()
        override fun get(url: String, maxBytes: Int): ByteArray {
            urls += url
            failure?.let { throw it }
            return if (url.endsWith(".sig")) sig else json
        }
    }

    private fun store() = StoredCatalogue(File(dir, "catalogue"))

    private fun updater(
        fetcher: CatalogueFetcher, state: UpdateState = MemState(), base: String = "https://example.test/catalogue/",
        app: Int = 1, key: String = signer.publicKeyPem,
    ) = CatalogueUpdater(base, key, app, bundledSerial = 5, store = store(), state = state, fetcher = fetcher)

    private fun signed(serial: Long = 6, minApp: Long = 1): FakeFetcher {
        val bytes = text(serial, minApp)
        return FakeFetcher(bytes, signer.sign(bytes))
    }

    @Test fun aLostStoredCopyOfTheHighestSerialIsStoredAgain() {
        val state = MemState().apply { highestSerial = 6 }
        val dir = store()
        val result = CatalogueUpdater("https://example.test/c", signer.publicKeyPem, 1, 5, dir, state, signed(6)).check()
        assertEquals(UpdateResult.Updated(6), result)
        assertNotNull(dir.read())
        assertEquals(UpdateResult.UpToDate, CatalogueUpdater("https://example.test/c", signer.publicKeyPem, 1, 5, dir, state, signed(6)).check())
    }

    @Test fun happyPathStoresVerifiedBytesAndBumpsHighestSerial() {
        val fetcher = signed(6)
        val state = MemState()
        assertEquals(UpdateResult.Updated(6), updater(fetcher, state).check())
        assertEquals(6, state.highestSerial)
        assertEquals(listOf("https://example.test/catalogue/catalogue.json", "https://example.test/catalogue/catalogue.json.sig"), fetcher.urls)
        val stored = store().read()!!
        assertArrayEquals(fetcher.json, stored.json)
        assertArrayEquals(fetcher.sig, stored.signature)
        assertEquals(emptyList<String>(), File(dir, "catalogue").list()!!.filter { it.endsWith(".part") })
    }

    @Test fun badSignatureIsRejectedAndNothingIsStored() {
        val fetcher = signed(6)
        fetcher.json = fetcher.json.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        assertTrue(updater(fetcher).check() is UpdateResult.Rejected)
        assertNull(store().read())
    }

    @Test fun aSignatureByAnotherKeyIsRejected() {
        val bytes = text(6)
        val fetcher = FakeFetcher(bytes, other.sign(bytes))
        val state = MemState()
        assertTrue(updater(fetcher, state).check() is UpdateResult.Rejected)
        assertNull(store().read())
        assertEquals(0, state.highestSerial)
    }

    @Test fun aRollbackIsRejectedAndTheSameSerialIsUpToDate() {
        val state = MemState(highestSerial = 9)
        assertTrue(updater(signed(8), state).check() is UpdateResult.Rejected)
        // The bundled serial (5) is the floor even when the preferences never recorded anything.
        assertTrue(updater(signed(4)).check() is UpdateResult.Rejected)
        assertEquals(UpdateResult.UpToDate, updater(signed(5)).check())
        assertNull(store().read())
        // The same serial as the highest accepted, with that copy still stored, is up to date.
        store().write(text(9, 1), signer.sign(text(9, 1)))
        assertEquals(UpdateResult.UpToDate, updater(signed(9), state).check())
        assertEquals(9, state.highestSerial)
    }

    @Test fun aCatalogueNeedingANewerAppIsRejected() {
        val state = MemState()
        assertTrue(updater(signed(6, minApp = 2), state, app = 1).check() is UpdateResult.Rejected)
        assertNull(store().read())
        assertEquals(0, state.highestSerial)
        assertEquals(UpdateResult.Updated(6), updater(signed(6, minApp = 2), state, app = 2).check())
    }

    @Test fun oversizeResponsesAreRejected() {
        val big = signed(6).also { it.json = ByteArray(CatalogueUpdater.MAX_JSON_BYTES + 1) }
        assertTrue(updater(big).check() is UpdateResult.Rejected)
        val bigSig = signed(6).also { it.sig = ByteArray(CatalogueUpdater.MAX_SIG_BYTES + 1) }
        assertTrue(updater(bigSig).check() is UpdateResult.Rejected)
        val refusing = signed(6).also { it.failure = RefusedResponseException("response larger") }
        assertTrue(updater(refusing).check() is UpdateResult.Rejected)
        assertNull(store().read())
    }

    @Test fun malformedJsonIsRejectedEvenWhenSigned() {
        val bytes = "{ not json".toByteArray()
        val result = updater(FakeFetcher(bytes, signer.sign(bytes))).check()
        assertTrue(result.toString(), result is UpdateResult.Rejected)
        assertNull(store().read())
    }

    @Test fun networkFailureIsFailedAndAnEmptyOrInsecureUrlIsNot() {
        val failing = signed(6).also { it.failure = IOException("offline") }
        assertEquals(UpdateResult.Failed("offline"), updater(failing).check())
        val fetcher = signed(6)
        assertEquals(UpdateResult.Disabled, updater(fetcher, base = "").check())
        assertTrue(updater(fetcher, base = "http://example.test/catalogue").check() is UpdateResult.Rejected)
        assertTrue(fetcher.urls.isEmpty())
    }

    @Test fun redirectsMustStayOnHttps() {
        val start = URL("https://example.test/catalogue/catalogue.json")
        assertEquals("https://cdn.example.test/x/catalogue.json", HttpsCatalogueFetcher.nextUrl(start, "https://cdn.example.test/x/catalogue.json").toString())
        assertEquals("https://example.test/other/catalogue.json", HttpsCatalogueFetcher.nextUrl(start, "/other/catalogue.json").toString())
        try {
            HttpsCatalogueFetcher.nextUrl(start, "http://example.test/catalogue.json")
            fail("an https to http redirect must be refused")
        } catch (e: RefusedResponseException) {
            assertTrue(e.message, e.message!!.contains("https"))
        }
        try {
            HttpsCatalogueFetcher().get("http://example.test/catalogue.json", 10)
            fail("a plain http url must be refused")
        } catch (e: RefusedResponseException) {
        }
    }

    // ---- startup selection

    private fun select(bundled: LoadedCatalogue) =
        AppCatalogue.select(bundled, store(), signer.publicKeyPem, 1, TestCatalogue.resolve)

    @Test fun aStoredNewerCatalogueIsUsed() {
        val newer = text(8)
        store().write(newer, signer.sign(newer))
        assertEquals(8L, select(load(text(5))).serial)
        assertNotNullFiles()
    }

    @Test fun aStoredCatalogueOlderThanTheBundledOneIsDeleted() {
        val older = text(4)
        store().write(older, signer.sign(older))
        assertEquals(6L, select(load(text(6))).serial)
        assertNull(store().read())
    }

    @Test fun aCorruptOrForeignStoredCatalogueIsDeleted() {
        val bytes = text(8)
        store().write(bytes, other.sign(bytes))
        assertEquals(5L, select(load(text(5))).serial)
        assertNull(store().read())
        store().write("garbage".toByteArray(), ByteArray(70))
        assertEquals(5L, select(load(text(5))).serial)
        assertNull(store().read())
        // Needs a newer app than this one.
        val future = text(9, minApp = 2)
        store().write(future, signer.sign(future))
        assertEquals(5L, select(load(text(5))).serial)
        assertNull(store().read())
    }

    @Test fun withoutStoredFilesTheBundledCatalogueIsUsed() {
        assertEquals(5L, select(load(text(5))).serial)
    }

    private fun assertNotNullFiles() = assertFalse(store().read() == null)

    // ---- the Home update offer

    private val ids = listOf("shm-phone", "shm-watch", "bridge-watch", "alarm-fork", "bridge-phone")
    private val watchSha = "a714e9a5950f456b8da4843bd6cdb19948a0c6fd9101b473e24beb3384a2c620"

    private fun builtWith(catalogue: LoadedCatalogue, facts: Map<String, String> = buildFacts(catalogue, ids)) =
        BuiltBuild("k", "p", catalogue.serial, 0, listOf(BuiltRecord("shm-phone", "x", 1, "ff")), facts)

    @Test fun anUpdatedInputOffersItsVersions() {
        val old = load(text(5))
        val new = load(text(6) { it.replace(watchSha, "b".repeat(64)).replace("\"versionName\": \"1.5.1.022\"", "\"versionName\": \"1.5.2.004\"") })
        assertEquals(
            UpdateOffer(new.input("shm-watch").label, "1.5.1.022", "1.5.2.004"),
            updateOfferFor(new, builtWith(old), ids),
        )
    }

    @Test fun changedPatchesOfferAPatchesOnlyUpdate() {
        val old = load(text(5))
        val new = load(text(6) { it.replace("\"title\": \"Minimum age 16\"", "\"title\": \"Minimum age 17\"") })
        val offer = updateOfferFor(new, builtWith(old), ids)!!
        assertTrue(offer.patchesOnly)
        assertEquals(offer.fromVersion, offer.toVersion)
    }

    @Test fun nothingRelevantChangedOffersNothing() {
        val old = load(text(5))
        val new = load(text(6) { it.replace("\"title\": \"Manufacturer check\"", "\"title\": \"Manufacturer check\"") })
        assertNull(updateOfferFor(new, builtWith(old), ids))
        // A newer serial that only changes an input outside the user's outputs.
        val alarmOnly = load(text(7) { it.replace("141832f22d7fb8bb7a6bb5e11695829015da6a33c5e19671a8695ecea276e8bd", "c".repeat(64)) })
        assertNull(updateOfferFor(alarmOnly, builtWith(old), listOf("shm-phone", "shm-watch", "bridge-watch", "bridge-phone")))
    }

    @Test fun noOfferWithoutABuildOrWithAnEqualSerialOrNoFacts() {
        val old = load(text(5))
        val new = load(text(6) { it.replace(watchSha, "b".repeat(64)) })
        assertNull(updateOfferFor(new, null, ids))
        assertNull(updateOfferFor(new, builtWith(old, facts = emptyMap()), ids))
        assertNull(updateOfferFor(old, builtWith(old), ids))
        assertNull(updateOfferFor(old, builtWith(new), ids))
    }

    @Test fun factsSurviveTheBuildRecordAndOldRecordsStillRead() {
        val old = load(text(5))
        val apk = File(dir, "a.apk").also { it.writeBytes(ByteArray(5)) }
        val store = BuiltOutputs(File(dir, "record.properties"))
        val build = BuiltBuild("k", "com.example", 4, 99, listOf(BuiltRecord("a", apk.path, 5, "ff")), buildFacts(old, ids))
        store.write(build)
        assertEquals(build.facts, store.read()!!.facts)
        assertTrue(build.facts.keys.containsAll(listOf("input.shm-watch", "patches.shm-watch")))
        store.write(build.copy(facts = emptyMap()))
        assertEquals(emptyMap<String, String>(), store.read()!!.facts)
    }
}
