package io.openshm.patcher.device.logs

import io.openshm.patcher.data.Device
import io.openshm.patcher.data.LoadedCatalogue
import io.openshm.patcher.device.LogDevice

/** The packages whose logs a device capture keeps, and how to find them in `pm list packages -U`. */
class PackageTargets(val exact: Set<String>, val templates: List<Regex>) {
    /** [installed] (package to uids) narrowed to the targets: exact names plus prefix-template hits. */
    fun select(installed: Map<String, List<Int>>): Map<String, List<Int>> =
        installed.filter { (name, _) -> name in exact || templates.any { it.matches(name) } }

    companion object {
        /**
         * Samsung Health on the watch (the "regular Health"). The catalogue has no entry for it because
         * the patcher neither builds nor installs it, but its logs explain most watch-side failures.
         */
        const val WEAR_SHEALTH = "com.samsung.android.wear.shealth"

        private const val PREFIX_TOKEN = "\${PKG_PREFIX}"

        /** What a generated package prefix looks like (dot-separated lowercase words). */
        private const val PREFIX_PATTERN = "[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)*"

        /**
         * Derived from the catalogue, never hard-coded: every input and output of [device], with output
         * names resolved for [prefix]. An output template is also kept as a regex so an app built under
         * another prefix (an earlier install) is still caught.
         */
        fun of(catalogue: LoadedCatalogue, device: LogDevice, prefix: String): PackageTargets {
            val target = if (device == LogDevice.Watch) Device.Watch else Device.Phone
            val exact = linkedSetOf<String>()
            val templates = mutableListOf<Regex>()
            catalogue.inputs.filter { it.device == target }.forEach { exact += it.packageName }
            for (output in catalogue.outputs.filter { it.device == target }) {
                exact += output.packageName(prefix)
                templateRegex(output.packageTemplate)?.let { templates += it }
            }
            if (device == LogDevice.Watch) exact += WEAR_SHEALTH
            return PackageTargets(exact, templates)
        }

        /** The regex for a template with a `${PKG_PREFIX}` placeholder (the rest literal), or null without one. */
        internal fun templateRegex(template: String): Regex? {
            if (!template.contains(PREFIX_TOKEN)) return null
            val body = template.split(PREFIX_TOKEN).joinToString(PREFIX_PATTERN) { Regex.escape(it) }
            return Regex(body)
        }

        private val PM_LINE = Regex("""^package:([A-Za-z0-9_.]+)\s+uid:([0-9,\s]+)""")

        /**
         * Parses `pm list packages -U` (`package:<name> uid:<n>`; a shared or multi-user uid may be a
         * comma list). Lines that do not look like that are ignored.
         */
        fun parsePackageUids(output: String): Map<String, List<Int>> {
            val result = LinkedHashMap<String, List<Int>>()
            for (raw in output.lineSequence()) {
                val match = PM_LINE.find(raw.trim()) ?: continue
                val uids = match.groupValues[2].split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it >= 0 }
                if (uids.isNotEmpty()) result[match.groupValues[1]] = uids.distinct()
            }
            return result
        }
    }
}
