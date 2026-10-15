package io.openshm.patcher.ui.screens.revert

import io.openshm.patcher.data.Device
import io.openshm.patcher.data.OutputApp
import io.openshm.patcher.data.PackagePrefix

/** What the revert does with one entry. */
enum class RevertAction {
    /** Remove an app we installed (with its data, grants and listener entries). */
    Uninstall,

    /** Turn a stock app back on that the setup had turned off. */
    Enable,

    /** Put a secure setting back to its default value. */
    RestoreSetting,

    /** Remove our entry from a list-valued global setting; the other entries are kept. */
    RemoveExemption,
}

/** What the probe found for an entry. */
enum class Presence {
    /** There is something to undo. */
    Present,

    /** Not installed (or nothing to undo): reported and skipped. */
    Absent,

    /** A system app: never touched. */
    System,

    /** The probe failed for this entry; the run checks it again before acting. */
    Unknown,
}

/** One thing the revert may undo on one device. [pkg] is the package name, or the setting key for the setting actions. */
data class RevertItem(
    val device: Device,
    val label: String,
    val pkg: String,
    val action: RevertAction,
    val presence: Presence = Presence.Present,
    /** The catalogue output the package belongs to; null for the stock app, the secure setting and the exemption. */
    val outputId: String? = null,
) {
    /** Identifies the item across probes and progress updates (the presence is not part of it). */
    val key: String get() = "${device.name}:${action.name}:$pkg"

    /** Whether the run acts on it. */
    val actionable: Boolean get() = presence == Presence.Present || presence == Presence.Unknown
}

/** A package the revert may remove, before the device was asked about it. */
data class RevertCandidate(val device: Device, val label: String, val pkg: String, val outputId: String? = null)

/**
 * The pure part of the revert: which prefixes our builds may have used, and which packages that
 * makes candidates. Probing the devices and acting is [RevertRunner]'s job.
 */
object RevertPlan {
    private const val PLACEHOLDER = "\${PKG_PREFIX}"

    /**
     * The package prefixes to look for: the stored [storedPrefix] (when set), plus every prefix that
     * [watchPackages] or [phoneListenerPackages] show for the catalogue's templates (so an install made
     * under an earlier prefix is found too). A template without the placeholder yields nothing.
     */
    fun prefixes(
        storedPrefix: String?,
        outputs: List<OutputApp>,
        watchPackages: Collection<String>,
        phoneListenerPackages: Collection<String>,
    ): List<String> {
        val found = LinkedHashSet<String>()
        storedPrefix?.takeIf { it.isNotBlank() }?.let { found += it }
        for (out in outputs) {
            val suffix = suffixOf(out) ?: continue
            val packages = if (out.device == Device.Watch) watchPackages else phoneListenerPackages
            for (pkg in packages) {
                if (!pkg.endsWith(suffix)) continue
                val prefix = pkg.removeSuffix(suffix)
                // Only a prefix the patcher itself would accept: this keeps out vendor namespaces, so a
                // vendor package never yields its own namespace as a prefix.
                if (PackagePrefix.validate(prefix) == null) found += prefix
            }
        }
        return found.toList()
    }

    /**
     * Every output of the catalogue (all profiles, Samsung Health on or off) resolved for each prefix,
     * deduplicated, in reverse install order (the last installed goes first). An output whose package
     * does not depend on the prefix appears once. [exclude] (the patcher itself) is never a candidate.
     */
    fun candidates(outputs: List<OutputApp>, prefixes: List<String>, exclude: Set<String> = emptySet()): List<RevertCandidate> {
        val seen = LinkedHashSet<Pair<Device, String>>()
        val result = ArrayList<RevertCandidate>()
        for (out in outputs.asReversed()) {
            val names = if (out.packageTemplate.contains(PLACEHOLDER)) prefixes.map { out.packageName(it) } else listOf(out.packageTemplate)
            for (name in names) {
                if (name in exclude) continue
                if (seen.add(out.device to name)) result += RevertCandidate(out.device, out.label, name, out.id)
            }
        }
        return result
    }

    /** What follows the placeholder in [out]'s package template (e.g. `.bridge.watch`), or null when the package does not depend on the prefix. */
    internal fun suffixOf(out: OutputApp): String? {
        val at = out.packageTemplate.indexOf(PLACEHOLDER)
        if (at < 0) return null
        return out.packageTemplate.substring(at + PLACEHOLDER.length).takeIf { it.startsWith(".") }
    }
}
