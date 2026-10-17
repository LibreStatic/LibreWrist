package io.openshm.patcher.build

import io.openshm.patcher.engine.Catalogue
import io.openshm.patcher.engine.CatalogueOutput
import io.openshm.patcher.engine.DexInjection
import io.openshm.patcher.engine.InjectionSource
import io.openshm.patcher.engine.OutputSource
import io.openshm.patcher.engine.OwnAppBuilder
import io.openshm.patcher.engine.OwnAppRequest
import io.openshm.patcher.engine.PatchListener
import io.openshm.patcher.engine.PatternResult
import io.openshm.patcher.engine.PhaseReport
import io.openshm.patcher.engine.PatchRequest
import io.openshm.patcher.engine.Patcher
import io.openshm.patcher.engine.SigningKey
import io.openshm.patcher.ui.screens.build.BuildStage
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** What one engine run says: the success flag, the failure and the human-readable report. */
class StepResult(val ok: Boolean, val error: String?, val text: String)

/** The boundary over the engine, so the runner is testable without real APKs. */
interface BuildEngine {
    fun patch(request: PatchRequest, listener: PatchListener): StepResult
    fun buildOwnApp(request: OwnAppRequest, listener: PatchListener): StepResult
}

class RealBuildEngine(catalogue: Catalogue) : BuildEngine {
    private val patcher = Patcher(catalogue)
    private val ownApps = OwnAppBuilder(catalogue)

    override fun patch(request: PatchRequest, listener: PatchListener): StepResult =
        patcher.patch(request, listener).let { StepResult(it.ok, it.error, it.text()) }

    override fun buildOwnApp(request: OwnAppRequest, listener: PatchListener): StepResult =
        ownApps.build(request, listener).let { StepResult(it.ok, it.error, it.text()) }
}

/** Where the built-in own code lives in the app assets. */
object BuildAssets {
    /** The catalogue `ownApp` path with "/" as "-": `dir/app` becomes `owncode/apps/dir-app.apk` (see `own-code.gradle.kts`). */
    fun ownApp(path: String): String {
        val parts = path.split('/')
        require(parts.size == 2 && parts.all { it.isNotEmpty() && it != ".." }) { "unknown own app path '$path'" }
        return "owncode/apps/${parts.joinToString("-")}.apk"
    }

    fun injection(name: String): String {
        require(name.isNotEmpty() && '/' !in name) { "unknown injection '$name'" }
        return "owncode/injections/$name.dex"
    }
}

/** What to build. */
class BuildRequest(
    val outputIds: Collection<String>,
    val prefix: String,
    val signingKey: SigningKey,
    /** SHA-256 of this patcher's own signing certificate, for the phone bridge's STATUS permission. */
    val knownSignerCert: String?,
    /** Optional patterns to leave out (see [PatchRequest.disabledPatterns]); the engine refuses required or unknown ids. */
    val disabledPatterns: Set<String> = emptySet(),
    /** What each output would be built from now (see [outputFingerprint]), by output id. */
    val fingerprints: Map<String, String> = emptyMap(),
    /** Outputs of the last build that may be kept as they are, by output id; used only when the fingerprint and the file still match. */
    val reusable: Map<String, BuiltRecord> = emptyMap(),
    /** China mode is on: an output with a `chinaFrom` is built from that source instead of `from`. */
    val chinaMode: Boolean = false,
)

/** One finished output; [reused] means the file of an earlier build was kept, [fingerprint] is what it was built from. */
class BuiltFile(
    val outputId: String,
    val file: File,
    val sizeBytes: Long,
    val sha256: String,
    val fingerprint: String = "",
    val reused: Boolean = false,
)

/**
 * [files] are the complete outputs that exist after the run (on a failure or a cancel only those before
 * it). [cancelled] means the user stopped the build: what this run built is deleted, so [files] holds
 * only reused outputs, and there is no [error].
 */
class BuildOutcome(val ok: Boolean, val error: String?, val files: List<BuiltFile>, val cancelled: Boolean = false)

/** Thrown from the listener handed to the engine to stop a blocking engine call at its next event. */
class BuildCancelledException : RuntimeException("build cancelled")

/** Progress and log sink; called on the building thread. */
interface BuildSink {
    /** [stage] is reached for output number [index] (0-based) of [count]. Never goes backwards within one output. */
    fun onStage(outputId: String, index: Int, count: Int, stage: BuildStage)
    fun onLog(line: String)
}

/**
 * An output is not built again when [BuildRequest.reusable] has a record with the same non-empty
 * fingerprint as [BuildRequest.fingerprints] and `<id>.apk` still has the recorded size and sha256.
 *
 * Builds the outputs of a request one after the other, in the catalogue `installOrder`, and stops at the
 * first failure or when [cancelled] turns true (checked before each output and, through the engine
 * listener, at the next engine event). A cancel deletes the partial output and every output already
 * built by this run (not the reused ones). Each output goes to `<outputs>/<id>.apk.part` and is renamed to `<id>.apk` only when
 * its report is ok; a failure deletes the partial file. Plain JVM code.
 *
 * Stage mapping (engine phase event -> [BuildStage]); the stages list shows the output being built and
 * restarts for the next one, the overall progress (output index plus stage) is what stays monotonic:
 * - check-request, verify-input, unpack -> Unpack
 * - detect-family, check-injections -> Fingerprints (the anchors are matched against the family)
 * - apply and every `pattern <id>` -> Apply
 * - stage-dex, stage-manifest, stage-resources -> Rename (own code is staged to the prefix)
 * - rebuild-dex, write -> Rebuild
 * - sign -> Sign
 * - verify-signature, verify-package, verify-injections, verify-anchors, verify-dex -> SelfCheck
 * The order differs from the engine's: Fingerprints is the stage after Unpack in the UI list. Within one
 * output a stage never moves back, and stages an output does not have (an own app has no patches) are skipped.
 */
class BuildRunner(
    private val catalogue: Catalogue,
    private val engine: BuildEngine,
    private val inputFile: (inputId: String) -> File,
    private val openAsset: (path: String) -> InputStream,
    private val outputsDir: File,
    private val workDir: File,
    /** Polled before each output and on every engine event; true once the user asked to stop. */
    private val cancelled: () -> Boolean = { false },
) {
    fun run(request: BuildRequest, sink: BuildSink): BuildOutcome {
        val outputs = catalogue.installOrder.map { step -> catalogue.outputs.first { it.id == step.output } }
            .filter { it.id in request.outputIds }
        val missing = request.outputIds.filter { id -> outputs.none { it.id == id } }
        if (missing.isNotEmpty()) return fail(sink, "unknown outputs: ${missing.joinToString()}", emptyList())
        for (out in outputs) {
            val from = out.sourceFor(request.chinaMode)
            if (from is OutputSource.Patched && !inputFile(from.input).isFile) {
                return fail(sink, "input '${from.input}' is missing; import it again", emptyList())
            }
        }
        outputsDir.mkdirs()
        workDir.deleteRecursively()
        workDir.mkdirs()
        val built = ArrayList<BuiltFile>()
        try {
            outputs.forEachIndexed { index, out ->
                if (cancelled()) return cancel(sink, built)
                val part = File(outputsDir, "${out.id}.apk.part")
                val final = File(outputsDir, "${out.id}.apk")
                part.delete()
                val fingerprint = request.fingerprints[out.id].orEmpty()
                val kept = reusable(request.reusable[out.id], fingerprint, final)
                if (kept != null) {
                    sink.onStage(out.id, index, outputs.size, BuildStage.SelfCheck)
                    sink.onLog("reused ${out.id}: patches unchanged since the last build")
                    built += BuiltFile(out.id, final, kept.sizeBytes, kept.sha256, fingerprint, reused = true)
                    return@forEachIndexed
                }
                sink.onLog("== ${out.id} (${index + 1}/${outputs.size}) ==")
                val result = try {
                    buildOne(out, request, part, listener(out.id, index, outputs.size, sink))
                } catch (e: BuildCancelledException) {
                    part.delete()
                    return cancel(sink, built)
                } catch (e: Exception) {
                    part.delete()
                    return fail(sink, "${out.id}: ${e.message ?: e.javaClass.simpleName}", built)
                }
                if (!result.ok || !part.isFile) {
                    // the live lines already told the story; the full report helps to diagnose a failure
                    result.text.lineSequence().filter { it.isNotBlank() }.forEach(sink::onLog)
                    part.delete()
                    return fail(sink, result.error ?: "${out.id}: no output was produced", built)
                }
                Files.move(part.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                built += BuiltFile(out.id, final, final.length(), sha256(final), fingerprint)
                sink.onLog("built ${out.id}: ${final.length() / 1024} KiB")
            }
        } finally {
            workDir.deleteRecursively()
        }
        return BuildOutcome(true, null, built)
    }

    private fun buildOne(out: CatalogueOutput, request: BuildRequest, target: File, listener: PatchListener): StepResult =
        when (val from = out.sourceFor(request.chinaMode)) {
            is OutputSource.Patched -> {
                val names = catalogue.profile(from.profile).inject[out.family].orEmpty()
                val injections: Map<String, InjectionSource> = names.associateWith { name ->
                    DexInjection(readAsset(BuildAssets.injection(name)), request.prefix)
                }
                engine.patch(
                    PatchRequest(
                        inputApk = inputFile(from.input), inputId = from.input, profile = from.profile,
                        prefix = request.prefix, injections = injections, outputApk = target,
                        signingKey = request.signingKey, workDir = workDir,
                        disabledPatterns = request.disabledPatterns,
                    ),
                    listener,
                )
            }
            is OutputSource.OwnApp -> {
                val placeholder = File(workDir, "${out.id}-placeholder.apk")
                openAssetOrFail(BuildAssets.ownApp(from.path)).use { input -> Files.copy(input, placeholder.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                engine.buildOwnApp(
                    OwnAppRequest(
                        placeholderApk = placeholder, outputId = out.id, prefix = request.prefix,
                        knownSignerCert = request.knownSignerCert.takeIf { out.knownSigner },
                        outputApk = target, signingKey = request.signingKey, workDir = workDir,
                    ),
                    listener,
                ).also { placeholder.delete() }
            }
        }

    private fun listener(outputId: String, index: Int, count: Int, sink: BuildSink): PatchListener {
        var current: BuildStage? = null
        return object : PatchListener {
            override fun onEvent(event: String) {
                if (cancelled()) throw BuildCancelledException()
                val stage = stageOf(event) ?: return
                if (current == null || stage.ordinal > current!!.ordinal) {
                    current = stage
                    sink.onStage(outputId, index, count, stage)
                }
            }

            override fun onPhase(report: PhaseReport) {
                sink.onLog("${if (report.ok) "ok  " else "FAIL"} ${report.name} (${report.millis} ms)")
                report.details.forEach { sink.onLog("       $it") }
                if (cancelled()) throw BuildCancelledException()
            }

            override fun onPattern(result: PatternResult) {
                val line = when {
                    result.error != null -> "  ! ${result.id}: ${result.error}"
                    result.log.isEmpty() -> "  . ${result.id}: nothing to patch"
                    else -> "  + ${result.id}: ${result.log.size} patched site${if (result.log.size == 1) "" else "s"}"
                }
                sink.onLog(line)
            }
        }
    }

    private fun reusable(record: BuiltRecord?, fingerprint: String, file: File): BuiltRecord? =
        record?.takeIf {
            fingerprint.isNotEmpty() && it.fingerprint == fingerprint && file.isFile &&
                file.length() == it.sizeBytes && sha256(file) == it.sha256
        }

    private fun cancel(sink: BuildSink, built: List<BuiltFile>): BuildOutcome {
        built.filterNot { it.reused }.forEach { it.file.delete() }
        outputsDir.listFiles { f -> f.name.endsWith(".apk.part") }?.forEach { it.delete() }
        sink.onLog("CANCELLED by the user")
        return BuildOutcome(false, null, built.filter { it.reused }, cancelled = true)
    }

    private fun readAsset(path: String): ByteArray = openAssetOrFail(path).use { it.readBytes() }

    private fun openAssetOrFail(path: String): InputStream =
        try {
            openAsset(path)
        } catch (e: IOException) {
            throw IOException("the built-in file $path is missing from this app build")
        }

    private fun fail(sink: BuildSink, message: String, built: List<BuiltFile>): BuildOutcome {
        sink.onLog("FAILED: $message")
        return BuildOutcome(false, message, built)
    }

    companion object {
        fun stageOf(event: String): BuildStage? = when {
            event == "check-request" || event == "verify-input" || event == "unpack" -> BuildStage.Unpack
            event == "detect-family" || event == "check-injections" -> BuildStage.Fingerprints
            event == "apply" || event.startsWith("pattern ") -> BuildStage.Apply
            event.startsWith("stage-") -> BuildStage.Rename
            event == "rebuild-dex" || event == "write" -> BuildStage.Rebuild
            event == "sign" -> BuildStage.Sign
            event.startsWith("verify-") -> BuildStage.SelfCheck
            else -> null
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
