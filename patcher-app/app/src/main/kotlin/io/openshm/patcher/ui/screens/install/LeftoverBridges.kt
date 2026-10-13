package io.openshm.patcher.ui.screens.install

import io.openshm.patcher.data.Device
import io.openshm.patcher.data.OutputApp
import io.openshm.patcher.data.PackagePrefix
import io.openshm.patcher.ui.screens.revert.RevertPlan

/**
 * Finds copies of our bridge apps that an earlier package prefix left on one device. They would
 * compete with the current bridge for the phone link, so the install steps remove them.
 *
 * Only outputs named `<prefix>.shmbridge.<x>` count: Samsung Health+ (`<prefix>.shealth`, user health
 * data), stock apps and anything else is never a leftover.
 */
object LeftoverBridges {
    private const val BRIDGE_INFIX = ".shmbridge."

    /**
     * The packages of [installed] on [device] that are bridge copies of a prefix other than [currentPrefix].
     * [exclude] (the patcher itself) is never returned. Nothing is returned while [currentPrefix] is
     * not a valid prefix: without it every bridge would look like a leftover.
     */
    fun find(
        outputs: List<OutputApp>,
        device: Device,
        currentPrefix: String,
        installed: Collection<String>,
        exclude: Set<String> = emptySet(),
    ): List<String> {
        if (PackagePrefix.validate(currentPrefix) != null) return emptyList()
        val found = LinkedHashSet<String>()
        for (out in outputs) {
            if (out.device != device) continue
            val suffix = RevertPlan.suffixOf(out)?.takeIf { it.startsWith(BRIDGE_INFIX) } ?: continue
            for (pkg in installed) {
                if (!pkg.endsWith(suffix) || pkg in exclude) continue
                val prefix = pkg.removeSuffix(suffix)
                if (prefix != currentPrefix && PackagePrefix.validate(prefix) == null) found += pkg
            }
        }
        return found.toList()
    }
}
