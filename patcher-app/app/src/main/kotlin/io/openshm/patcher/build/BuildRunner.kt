package io.openshm.patcher.build

import io.openshm.patcher.engine.Catalogue
import io.openshm.patcher.engine.CatalogueOutput
import io.openshm.patcher.engine.DexInjection
import io.openshm.patcher.engine.InjectionSource
import io.openshm.patcher.engine.OutputSource
import io.openshm.patcher.engine.OwnAppBuilder
import io.openshm.patcher.engine.OwnAppRequest
import io.openshm.patcher.engine.PatchListener
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
    /** `shm-bridge/phone` (catalogue `ownApp` path) becomes `owncode/apps/bridge-phone.apk`. */
    fun ownApp(path: String): String {
        val name = path.removePrefix("shm-bridge/")
        require(name != path && name.isNotEmpty() && '/' !in name) { "unknown own app path '$path'" }
        return "owncode/apps/bridge-$name.apk"
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
)

/** One finished output. */
class BuiltFile(val outputId: String, val file: File, val sizeBytes: Long, val sha256: String)

class BuildOutcome(val ok: Boolean, val error: String?, val files: List<BuiltFile>)

/** Progress and log sink; called on the building thread. */
interface BuildSink {
    /** [stage] is reached for output number [index] (0-based) of [count]. Never goes backwards within one output. */
    fun onStage(outputId: String, index: Int, count: Int, stage: BuildStage)
    fun onLog(line: String)
}

/**
 * Builds the outputs of a request one after the other, in the catalogue `installOrder`, and stops at the
 * first failure. Each output goes to `<outputs>/<id>.apk.part` and is renamed to `<id>.apk` only when
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
) {
    fun run(request: BuildRequest, sink: BuildSink): BuildOutcome {
        val outputs = catalogue.installOrder.map { step -> catalogue.outputs.first { it.id == step.output } }
            .filter { it.id in request.outputIds }
        val missing = request.outputIds.filter { id -> outputs.none { it.id == id } }
        if (missing.isNotEmpty()) return fail(sink, "unknown outputs: ${missing.joinToString()}", emptyList())
        for (out in outputs) {
            val from = out.from
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
                val part = File(outputsDir, "${out.id}.apk.part")
                val final = File(outputsDir, "${out.id}.apk")
                part.delete()
                sink.onLog("== ${out.id} (${index + 1}/${outputs.size}) ==")
                val result = try {
                    buildOne(out, request, part, listener(out.id, index, outputs.size, sink))
                } catch (e: Exception) {
                    part.delete()
                    return fail(sink, "${out.id}: ${e.message ?: e.javaClass.simpleName}", built)
                }
                result.text.lineSequence().filter { it.isNotBlank() }.forEach(sink::onLog)
                if (!result.ok || !part.isFile) {
                    part.delete()
                    return fail(sink, result.error ?: "${out.id}: no output was produced", built)
                }
                Files.move(part.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                built += BuiltFile(out.id, final, final.length(), sha256(final))
            }
        } finally {
            workDir.deleteRecursively()
        }
        return BuildOutcome(true, null, built)
    }

    private fun buildOne(out: CatalogueOutput, request: BuildRequest, target: File, listener: PatchListener): StepResult =
        when (val from = out.from) {
            is OutputSource.Patched -> {
                val names = catalogue.profile(from.profile).inject[from.input].orEmpty()
                val injections: Map<String, InjectionSource> = names.associateWith { name ->
                    DexInjection(readAsset(BuildAssets.injection(name)), request.prefix)
                }
                engine.patch(
                    PatchRequest(
                        inputApk = inputFile(from.input), inputId = from.input, profile = from.profile,
                        prefix = request.prefix, injections = injections, outputApk = target,
                        signingKey = request.signingKey, workDir = workDir,
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
                        knownSignerCert = request.knownSignerCert.takeIf { out.id == PHONE_BRIDGE },
                        outputApk = target, signingKey = request.signingKey, workDir = workDir,
                    ),
                    listener,
                ).also { placeholder.delete() }
            }
        }

    private fun listener(outputId: String, index: Int, count: Int, sink: BuildSink): PatchListener {
        var current: BuildStage? = null
        return PatchListener { event ->
            val stage = stageOf(event) ?: return@PatchListener
            if (current == null || stage.ordinal > current!!.ordinal) {
                current = stage
                sink.onStage(outputId, index, count, stage)
            }
        }
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
        /** The only output that gets the patcher's own certificate as its known signer. */
        const val PHONE_BRIDGE = "bridge-phone"

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
