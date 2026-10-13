package io.openshm.patcher

import io.openshm.patcher.build.BuildAssets
import io.openshm.patcher.build.BuildController
import io.openshm.patcher.build.BuildCancelledException
import io.openshm.patcher.build.BuildEngine
import io.openshm.patcher.build.BuildRequest
import io.openshm.patcher.build.BuildRunPhase
import io.openshm.patcher.build.BuildRunner
import io.openshm.patcher.build.BuildSink
import io.openshm.patcher.build.BuiltBuild
import io.openshm.patcher.build.BuiltOutputs
import io.openshm.patcher.build.BuiltRecord
import io.openshm.patcher.build.buildKey
import io.openshm.patcher.build.StepResult
import io.openshm.patcher.data.SigningKeyStore
import io.openshm.patcher.engine.OwnAppRequest
import io.openshm.patcher.engine.PatchListener
import io.openshm.patcher.engine.PatchRequest
import io.openshm.patcher.engine.PatternResult
import io.openshm.patcher.engine.PhaseReport
import io.openshm.patcher.engine.SigningKey
import io.openshm.patcher.ui.screens.build.BuildStage
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import javax.crypto.KeyGenerator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BuildRunnerTest {
    private lateinit var root: File
    private val engineCatalogue = TestCatalogue.load().engine

    private val key: SigningKey by lazy {
        val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        SigningKeyStore(File(root, "key")) { aes }.load()
    }

    @Before fun setUp() {
        root = Files.createTempDirectory("runner-test").toFile()
        File(root, "inputs").mkdirs()
        for (input in engineCatalogue.inputs) File(root, "inputs/${input.id}.apk").writeText("apk")
    }

    @After fun tearDown() { root.deleteRecursively() }

    private class Sink : BuildSink {
        val stages = ArrayList<Triple<String, Int, BuildStage>>()
        val log = ArrayList<String>()
        override fun onStage(outputId: String, index: Int, count: Int, stage: BuildStage) { stages += Triple(outputId, index, stage) }
        override fun onLog(line: String) { log += line }
    }

    private class FakeEngine(val failOn: String? = null, val events: List<String> = listOf("check-request", "verify-input", "unpack", "detect-family", "check-injections", "apply", "pattern x", "rebuild-dex", "write", "sign", "verify-signature")) : BuildEngine {
        val patches = ArrayList<PatchRequest>()
        val ownApps = ArrayList<OwnAppRequest>()
        val ownAppEvents = listOf("check-request", "unpack", "stage-dex", "stage-manifest", "stage-resources", "write", "sign", "verify-signature", "verify-dex")

        override fun patch(request: PatchRequest, listener: PatchListener): StepResult {
            patches += request
            events.forEach(listener::onEvent)
            return finish(request.inputId, request.outputApk!!)
        }

        override fun buildOwnApp(request: OwnAppRequest, listener: PatchListener): StepResult {
            ownApps += request
            assertTrue(request.placeholderApk.isFile)
            ownAppEvents.forEach(listener::onEvent)
            return finish(request.outputId, request.outputApk)
        }

        private fun finish(name: String, out: File): StepResult {
            out.writeText("built $name")
            return if (name == failOn) StepResult(false, "sign: boom", "report of $name\n [FAIL] sign") else StepResult(true, null, "report of $name")
        }
    }

    private fun runner(
        engine: BuildEngine,
        assets: (String) -> ByteArray? = { ByteArray(4) },
        cancelled: () -> Boolean = { false },
    ) = BuildRunner(
        catalogue = engineCatalogue,
        engine = engine,
        inputFile = { File(root, "inputs/$it.apk") },
        openAsset = { path -> ByteArrayInputStream(assets(path) ?: throw FileNotFoundException(path)) },
        outputsDir = File(root, "outputs"),
        workDir = File(root, "work"),
        cancelled = cancelled,
    )

    private fun request(ids: Collection<String>) = BuildRequest(ids, "com.example", key, "ab".repeat(32))

    private val allIds get() = engineCatalogue.outputs.map { it.id }

    @Test fun buildsEveryOutputInInstallOrderAndRecordsFiles() {
        val engine = FakeEngine()
        val sink = Sink()
        val outcome = runner(engine).run(request(allIds), sink)
        assertTrue(outcome.error, outcome.ok)
        assertEquals(engineCatalogue.installOrder.map { it.output }, outcome.files.map { it.outputId })
        for (f in outcome.files) {
            assertEquals("${f.outputId}.apk", f.file.name)
            assertEquals(f.file.length(), f.sizeBytes)
            assertEquals(64, f.sha256.length)
        }
        assertFalse(File(root, "outputs").listFiles()!!.any { it.name.endsWith(".part") })
        assertFalse(File(root, "work").exists())
        assertTrue(sink.log.any { it.startsWith("built shm-phone") })
    }

    @Test fun patchedOutputsGetInputInjectionsAndPrefix() {
        val engine = FakeEngine()
        runner(engine).run(request(allIds), Sink())
        val phone = engine.patches.first { it.inputId == "shm-phone" }
        assertEquals("com.example", phone.prefix)
        assertEquals(File(root, "inputs/shm-phone.apk"), phone.inputApk)
        val profile = engineCatalogue.profile(phone.profile)
        assertEquals(profile.inject["shm-phone"].orEmpty().toSet(), phone.injections.keys)
        assertTrue(phone.outputApk!!.name.endsWith(".apk.part"))
    }

    @Test fun disabledPatternsReachEveryPatchedOutput() {
        val engine = FakeEngine()
        val disabled = setOf("shm-sleep-apnea-support", "watch-apnea-country", "watch-apnea-reeval-not-supported", "alarm-relabel")
        runner(engine).run(BuildRequest(allIds, "com.example", key, null, disabled), Sink())
        assertEquals(setOf("shm-phone", "shm-watch", "alarm-watch", "shealth-phone"), engine.patches.map { it.inputId }.toSet())
        for (req in engine.patches) assertEquals(disabled, req.disabledPatterns)
    }

    @Test fun cancelBetweenOutputsStopsBeforeTheNextOneAndDeletesWhatWasBuilt() {
        val engine = FakeEngine()
        var requested = false
        val sink = object : BuildSink {
            override fun onStage(outputId: String, index: Int, count: Int, stage: BuildStage) {
                if (index == 0 && stage == BuildStage.Sign) requested = true // asked for while the first output is being built
            }
            override fun onLog(line: String) {}
        }
        val outcome = runner(engine, cancelled = { requested }).run(request(allIds), sink)
        // the cancel is seen at the next engine event of the first output, so nothing completes
        assertTrue(outcome.cancelled)
        assertFalse(outcome.ok)
        assertNull(outcome.error)
        assertTrue(outcome.files.isEmpty())
        assertEquals(1, engine.patches.size + engine.ownApps.size)
        assertTrue(File(root, "outputs").listFiles().orEmpty().isEmpty())
        assertFalse(File(root, "work").exists())
    }

    @Test fun cancelAfterAnOutputFinishedDeletesItAndBuildsNoMore() {
        val engine = FakeEngine()
        var requested = false
        val sink = object : BuildSink {
            override fun onStage(outputId: String, index: Int, count: Int, stage: BuildStage) {}
            override fun onLog(line: String) { if (line.startsWith("built ")) requested = true }
        }
        val outcome = runner(engine, cancelled = { requested }).run(request(allIds), sink)
        assertTrue(outcome.cancelled)
        assertEquals(1, engine.patches.size + engine.ownApps.size) // the second output never started
        assertTrue(File(root, "outputs").listFiles().orEmpty().isEmpty())
    }

    @Test fun cancelBeforeTheFirstOutputBuildsNothing() {
        val engine = FakeEngine()
        val outcome = runner(engine, cancelled = { true }).run(request(allIds), Sink())
        assertTrue(outcome.cancelled)
        assertTrue(engine.patches.isEmpty() && engine.ownApps.isEmpty())
    }

    @Test fun cancelMidOutputThrowsFromTheListenerAndDeletesThePartFile() {
        var requested = false
        val engine = object : BuildEngine {
            override fun patch(request: PatchRequest, listener: PatchListener): StepResult {
                request.outputApk!!.writeText("half")
                listener.onEvent("unpack")
                requested = true
                try {
                    listener.onEvent("apply")
                    throw AssertionError("the listener must throw once a cancel was requested")
                } catch (e: BuildCancelledException) {
                    throw e
                }
            }
            override fun buildOwnApp(request: OwnAppRequest, listener: PatchListener) = StepResult(true, null, "")
        }
        val sink = Sink()
        val outcome = runner(engine, cancelled = { requested }).run(request(allIds), sink)
        assertTrue(outcome.cancelled)
        assertNull(outcome.error)
        assertTrue(File(root, "outputs").listFiles().orEmpty().isEmpty())
        assertTrue(sink.log.any { it.contains("CANCELLED") })
        assertFalse(sink.log.any { it.startsWith("FAILED") })
    }

    @Test fun engineProgressIsForwardedAsReadableLines() {
        val engine = object : BuildEngine {
            override fun patch(request: PatchRequest, listener: PatchListener): StepResult {
                listener.onPhase(PhaseReport("unpack", true, 12, listOf("3 entries")))
                listener.onPattern(PatternResult("p1", "d", listOf("a", "b")))
                listener.onPattern(PatternResult("p2", "d", emptyList()))
                listener.onPhase(PhaseReport("sign", false, 7, listOf("boom")))
                request.outputApk!!.writeText("x")
                return StepResult(true, null, "")
            }
            override fun buildOwnApp(request: OwnAppRequest, listener: PatchListener) = StepResult(true, null, "").also {
                request.outputApk.writeText("x")
            }
        }
        val sink = Sink()
        runner(engine).run(request(listOf("shm-phone")), sink)
        assertTrue(sink.log.toString(), sink.log.contains("ok   unpack (12 ms)"))
        assertTrue(sink.log.contains("       3 entries"))
        assertTrue(sink.log.contains("  + p1: 2 patched sites"))
        assertTrue(sink.log.contains("  . p2: nothing to patch"))
        assertTrue(sink.log.contains("FAIL sign (7 ms)"))
    }

    @Test fun nothingIsDisabledByDefault() {
        val engine = FakeEngine()
        runner(engine).run(request(allIds), Sink())
        assertTrue(engine.patches.all { it.disabledPatterns.isEmpty() })
    }

    @Test fun buildKeyChangesWithTheSkippedPatches() {
        assertEquals(buildKey(1, "a.b", listOf("x")), buildKey(1, "a.b", listOf("x"), emptySet()))
        assertTrue(buildKey(1, "a.b", listOf("x")) != buildKey(1, "a.b", listOf("x"), setOf("alarm-relabel")))
        assertEquals(buildKey(1, "a.b", listOf("x"), listOf("p", "q")), buildKey(1, "a.b", listOf("x"), listOf("q", "p")))
    }

    @Test fun onlyThePhoneBridgeGetsTheKnownSigner() {
        val engine = FakeEngine()
        runner(engine).run(request(allIds), Sink())
        assertEquals(setOf("bridge-watch", "bridge-phone"), engine.ownApps.map { it.outputId }.toSet())
        for (req in engine.ownApps) {
            assertEquals(if (req.outputId == "bridge-phone") "ab".repeat(32) else null, req.knownSignerCert)
        }
    }

    @Test fun onlyTheRequestedOutputsAreBuilt() {
        val engine = FakeEngine()
        val outcome = runner(engine).run(request(listOf("bridge-watch")), Sink())
        assertTrue(outcome.ok)
        assertEquals(listOf("bridge-watch"), outcome.files.map { it.outputId })
        assertTrue(engine.patches.isEmpty())
    }

    @Test fun stagesNeverGoBackAndOwnAppsSkipPatchStages() {
        val sink = Sink()
        runner(FakeEngine()).run(request(listOf("shm-phone", "bridge-watch")), sink)
        for (output in listOf("shm-phone", "bridge-watch")) {
            val seq = sink.stages.filter { it.first == output }.map { it.third }
            assertEquals(seq.sortedBy { it.ordinal }, seq)
            assertEquals(seq.distinct(), seq)
        }
        assertEquals(
            listOf(BuildStage.Unpack, BuildStage.Fingerprints, BuildStage.Apply, BuildStage.Rebuild, BuildStage.Sign, BuildStage.SelfCheck),
            sink.stages.filter { it.first == "shm-phone" }.map { it.third },
        )
        assertEquals(
            listOf(BuildStage.Unpack, BuildStage.Rename, BuildStage.Rebuild, BuildStage.Sign, BuildStage.SelfCheck),
            sink.stages.filter { it.first == "bridge-watch" }.map { it.third },
        )
    }

    @Test fun phaseToStageMapping() {
        val m = BuildRunner.Companion::stageOf
        assertEquals(BuildStage.Unpack, m("check-request"))
        assertEquals(BuildStage.Unpack, m("verify-input"))
        assertEquals(BuildStage.Fingerprints, m("check-injections"))
        assertEquals(BuildStage.Apply, m("pattern generic-vendor-check"))
        assertEquals(BuildStage.Rename, m("stage-resources"))
        assertEquals(BuildStage.Rebuild, m("rebuild-dex"))
        assertEquals(BuildStage.SelfCheck, m("verify-anchors"))
        assertNull(m("something-else"))
    }

    @Test fun stopsAtTheFirstFailureAndDeletesTheirPartialOutput() {
        val engine = FakeEngine(failOn = "shm-watch")
        val sink = Sink()
        val outcome = runner(engine).run(request(allIds), sink)
        assertFalse(outcome.ok)
        assertEquals("sign: boom", outcome.error)
        assertEquals(listOf("shm-phone"), outcome.files.map { it.outputId })
        assertTrue(sink.log.any { it.contains("[FAIL] sign") })
        assertTrue(sink.log.last().startsWith("FAILED"))
        assertEquals(listOf("shm-phone.apk"), File(root, "outputs").list()!!.sorted())
        assertEquals(2, engine.patches.size)
        assertTrue(engine.ownApps.isEmpty())
    }

    @Test fun aMissingInputFailsBeforeAnythingRuns() {
        File(root, "inputs/shm-watch.apk").delete()
        val engine = FakeEngine()
        val outcome = runner(engine).run(request(allIds), Sink())
        assertFalse(outcome.ok)
        assertTrue(outcome.error!!.contains("shm-watch"))
        assertTrue(engine.patches.isEmpty())
    }

    @Test fun aMissingAssetFailsClearly() {
        val outcome = runner(FakeEngine(), assets = { path -> if (path == "owncode/apps/bridge-phone.apk") null else ByteArray(4) })
            .run(request(listOf("bridge-phone")), Sink())
        assertFalse(outcome.ok)
        assertTrue(outcome.error!!, outcome.error!!.contains("owncode/apps/bridge-phone.apk"))
        assertTrue(File(root, "outputs").listFiles().orEmpty().isEmpty())
    }

    @Test fun anEngineExceptionBecomesAFailure() {
        val boom = object : BuildEngine {
            override fun patch(request: PatchRequest, listener: PatchListener): StepResult {
                request.outputApk!!.writeText("partial")
                throw java.io.IOException("disk full")
            }
            override fun buildOwnApp(request: OwnAppRequest, listener: PatchListener) = error("unused")
        }
        val outcome = runner(boom).run(request(listOf("shm-phone")), Sink())
        assertFalse(outcome.ok)
        assertTrue(outcome.error!!.contains("disk full"))
        assertTrue(File(root, "outputs").listFiles().orEmpty().isEmpty())
    }

    @Test fun assetPaths() {
        assertEquals("owncode/apps/bridge-phone.apk", BuildAssets.ownApp("shm-bridge/phone"))
        assertEquals("owncode/apps/bridge-watch.apk", BuildAssets.ownApp("shm-bridge/watch"))
        assertEquals("owncode/injections/watch-mod.dex", BuildAssets.injection("watch-mod"))
        for (bad in listOf("phone", "other/phone", "shm-bridge/", "shm-bridge/a/b")) {
            try {
                BuildAssets.ownApp(bad)
                throw AssertionError("accepted $bad")
            } catch (expected: IllegalArgumentException) {
            }
        }
    }

    @Test fun everyCatalogueOwnAppPathMaps() {
        for (out in engineCatalogue.outputs) {
            val from = out.from
            if (from is io.openshm.patcher.engine.OutputSource.OwnApp) BuildAssets.ownApp(from.path)
        }
    }

    @Test fun builtOutputsRecordRoundTripsAndGoesStaleWhenAFileChanges() {
        val apk = File(root, "a.apk").also { it.writeText("12345") }
        val store = BuiltOutputs(File(root, "record.properties"))
        assertNull(store.read())
        val build = BuiltBuild("k", "com.example", 7, 99, listOf(BuiltRecord("a", apk.path, 5, "ff")))
        store.write(build)
        assertEquals(build, store.read())
        apk.writeText("1")
        assertNull(store.read())
        store.write(build.copy(outputs = listOf(BuiltRecord("a", apk.path, 1, "ff"))))
        assertNotNull(store.read())
        store.clear()
        assertNull(store.read())
    }

    @Test fun controllerStartsOnceAndKeepsProgressMonotonic() {
        assertTrue(BuildController.tryBegin("k1"))
        assertFalse(BuildController.tryBegin("k2"))
        BuildController.onStage("a", 0, 2, BuildStage.Sign)
        val p1 = BuildController.state.value.progress
        BuildController.onStage("a", 0, 2, BuildStage.Unpack)
        assertEquals(p1, BuildController.state.value.progress, 0f)
        BuildController.onStage("b", 1, 2, BuildStage.Unpack)
        assertTrue(BuildController.state.value.progress > p1)
        BuildController.fail("x")
        assertEquals(BuildRunPhase.Failed, BuildController.state.value.phase)
        assertTrue(BuildController.tryBegin("k3"))
        assertEquals("k3", BuildController.state.value.key)
        BuildController.succeed()
        assertEquals(1f, BuildController.state.value.progress, 0f)
    }

    @Test fun controllerCancelFlowNeverLooksFinished() {
        assertFalse(BuildController.requestCancel()) // nothing running
        assertTrue(BuildController.tryBegin("kc"))
        assertFalse(BuildController.isCancelRequested())
        assertTrue(BuildController.requestCancel())
        assertTrue(BuildController.isCancelRequested())
        BuildController.cancelled()
        assertEquals(BuildRunPhase.Cancelled, BuildController.state.value.phase)
        assertFalse(BuildController.requestCancel())
        BuildController.clearCancelled()
        assertEquals(BuildRunPhase.Idle, BuildController.state.value.phase)
        // a new build starts without the old cancel request
        assertTrue(BuildController.tryBegin("kd"))
        assertFalse(BuildController.isCancelRequested())
        BuildController.fail("done with the test")
        BuildController.clearCancelled() // only a cancelled build is cleared
        assertEquals(BuildRunPhase.Failed, BuildController.state.value.phase)
    }

    @Test fun controllerLogLinesCarryTheElapsedTime() {
        var now = 1_000L
        BuildController.clockMillis = { now }
        try {
            assertTrue(BuildController.tryBegin("kt"))
            now += 75_000
            BuildController.onLog("hello")
            now += 3_000_000
            BuildController.onLog("later")
            assertEquals(listOf("01:15 hello", "51:15 later"), BuildController.state.value.log)
        } finally {
            BuildController.clockMillis = { System.nanoTime() / 1_000_000 }
            BuildController.fail("done with the test")
        }
    }
}
