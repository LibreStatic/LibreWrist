package io.openshm.patcher.build

import io.openshm.patcher.data.LoadedCatalogue
import io.openshm.patcher.engine.OutputSource
import java.io.InputStream
import java.security.MessageDigest

/**
 * What a build depended on, recorded next to it so a later catalogue can be compared without keeping
 * the old one: per patched input the file hash and version (`input.<id>` = `sha256|versionName`) and
 * a fingerprint of the patterns and injections applied to it (`patches.<id>`). Plain JVM code.
 */
fun buildFacts(catalogue: LoadedCatalogue, outputIds: Collection<String>): Map<String, String> {
    val engine = catalogue.engine
    val facts = sortedMapOf<String, String>()
    val patched = engine.outputs.filter { it.id in outputIds }.mapNotNull { it.from as? OutputSource.Patched }
    for (from in patched.distinctBy { it.input }) {
        val input = catalogue.input(from.input)
        facts["input.${from.input}"] = "${input.sha256}|${input.versionName}"
        val profile = engine.profile(from.profile)
        val families = profile.families[from.input].orEmpty()
        val canonical = buildString {
            for (id in families) append("pattern:").append(id).append('=').append(engine.patterns[id]).append('\n')
            for (id in profile.inject[from.input].orEmpty()) append("inject:").append(id).append('\n')
        }
        facts["patches.${from.input}"] =
            MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    return facts
}

/** Bumped when the layout of the canonical text in [outputFingerprint] changes. */
const val FINGERPRINT_VERSION = 1

/**
 * Fingerprint of one output: lowercase hex SHA-256 of everything its bytes depend on. Two builds with the
 * same fingerprint produce the same APK, so a stored one can be reused instead of patching again.
 * [signingCertSha256] is the certificate of the signing key, [engineFingerprint] the hash of the engine and
 * build code (`BuildConfig.ENGINE_FINGERPRINT`), [ownCertSha256] the patcher's own certificate (it ends up
 * in the phone bridge), [openAsset] opens the built-in injection and own-app assets. Plain JVM code.
 */
fun outputFingerprint(
    catalogue: LoadedCatalogue,
    outputId: String,
    prefix: String,
    disabled: Collection<String>,
    signingCertSha256: String,
    engineFingerprint: String,
    ownCertSha256: String?,
    openAsset: (path: String) -> InputStream,
): String {
    val engine = catalogue.engine
    val out = engine.outputs.first { it.id == outputId }
    val canonical = StringBuilder()
    canonical.append("version=").append(FINGERPRINT_VERSION).append('\n')
    canonical.append("output=").append(outputId).append('\n')
    canonical.append("prefix=").append(prefix).append('\n')
    canonical.append("cert=").append(signingCertSha256).append('\n')
    canonical.append("engine=").append(engineFingerprint).append('\n')
    when (val from = out.from) {
        is OutputSource.Patched -> {
            val input = catalogue.input(from.input)
            canonical.append("input=").append(from.input).append('=').append(input.sha256).append('|').append(input.versionName).append('\n')
            canonical.append("profile=").append(from.profile).append('\n')
            val profile = engine.profile(from.profile)
            val families = profile.families[from.input].orEmpty()
            for (id in families) canonical.append("pattern:").append(id).append('=').append(engine.patterns[id]).append('\n')
            for (id in disabled.filter { it in families }.toSortedSet()) canonical.append("disabled:").append(id).append('\n')
            for (name in profile.inject[from.input].orEmpty()) {
                canonical.append("inject:").append(name).append('=').append(sha256Of(openAsset(BuildAssets.injection(name)))).append('\n')
            }
        }
        is OutputSource.OwnApp -> {
            canonical.append("app=").append(sha256Of(openAsset(BuildAssets.ownApp(from.path)))).append('\n')
            if (outputId == BuildRunner.PHONE_BRIDGE) canonical.append("knownSigner=").append(ownCertSha256.orEmpty()).append('\n')
        }
    }
    return hex(MessageDigest.getInstance("SHA-256").digest(canonical.toString().toByteArray()))
}

private fun sha256Of(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    input.use { stream ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return hex(digest.digest())
}

private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
