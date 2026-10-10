package io.openshm.patcher.ui.screens.home

import io.openshm.patcher.build.BuiltBuild
import io.openshm.patcher.build.buildFacts
import io.openshm.patcher.data.LoadedCatalogue

/**
 * The Home "update" offer: the loaded [catalogue] is newer than the one the last build ([built]) was
 * made with AND it changes something for [outputIds] (the user's outputs): an input with another
 * hash (offer: its old and new version), else changed patches (offer: [UpdateOffer.patchesOnly]).
 * No offer without a build, or when the record predates the recorded facts (nothing to compare).
 */
internal fun updateOfferFor(catalogue: LoadedCatalogue, built: BuiltBuild?, outputIds: Collection<String>): UpdateOffer? {
    if (built == null || built.facts.isEmpty() || catalogue.serial <= built.catalogueSerial) return null
    val now = buildFacts(catalogue, outputIds)
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
