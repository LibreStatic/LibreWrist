package io.openshm.patcher.ui.screens.home

import io.openshm.patcher.build.BuiltBuild
import io.openshm.patcher.build.buildFacts
import io.openshm.patcher.data.LoadedCatalogue
import io.openshm.patcher.data.sourceInputId

/**
 * China mode switched the official app an output is built from (Samsung Health+ from the global to the China
 * build or back): the last build [built] used the other input, so the output needs a rebuild whatever the
 * catalogue serial. Offers the old and the new version of that app.
 */
private fun sourceSwitchOffer(catalogue: LoadedCatalogue, built: BuiltBuild, outputIds: Collection<String>, chinaMode: Boolean): UpdateOffer? {
    for (out in catalogue.outputs.filter { it.id in outputIds }) {
        val now = out.sourceInputId(chinaMode) ?: continue
        val other = out.sourceInputId(!chinaMode) ?: continue
        if (now == other || "input.$now" in built.facts) continue
        val old = built.facts["input.$other"] ?: continue
        return UpdateOffer(catalogue.input(now).label, old.substringAfter('|', ""), catalogue.input(now).versionName)
    }
    return null
}

/**
 * The Home "update" offer: the loaded [catalogue] is newer than the one the last build ([built]) was
 * made with AND it changes something for [outputIds] (the user's outputs): an input with another
 * hash (offer: its old and new version), else changed patches (offer: [UpdateOffer.patchesOnly]).
 * No offer without a build, or when the record predates the recorded facts (nothing to compare).
 */
internal fun updateOfferFor(
    catalogue: LoadedCatalogue, built: BuiltBuild?, outputIds: Collection<String>, chinaMode: Boolean = false,
): UpdateOffer? {
    if (built == null || built.facts.isEmpty()) return null
    sourceSwitchOffer(catalogue, built, outputIds, chinaMode)?.let { return it }
    if (catalogue.serial <= built.catalogueSerial) return null
    val now = buildFacts(catalogue, outputIds, chinaMode)
    for ((key, value) in now) {
        val id = key.removePrefix("input.").takeIf { key.startsWith("input.") } ?: continue
        val old = built.facts[key] ?: continue
        if (old == value) continue
        val input = catalogue.input(id)
        return UpdateOffer(input.label, old.substringAfter('|', ""), input.versionName)
    }
    for ((key, value) in now) {
        val id = key.removePrefix("patches.").takeIf { key.startsWith("patches.") } ?: continue
        val old = built.facts[key] ?: continue
        if (old == value) continue
        val input = catalogue.input(id)
        return UpdateOffer(input.label, input.versionName, input.versionName, patchesOnly = true)
    }
    return null
}
