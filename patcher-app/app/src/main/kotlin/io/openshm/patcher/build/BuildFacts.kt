package io.openshm.patcher.build

import io.openshm.patcher.data.LoadedCatalogue
import io.openshm.patcher.engine.OutputSource
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
