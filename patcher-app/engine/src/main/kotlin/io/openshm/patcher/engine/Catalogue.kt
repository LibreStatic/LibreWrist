package io.openshm.patcher.engine

/** The catalogue is malformed; [problems] lists every problem found. */
class CatalogueException(val problems: List<String>) :
    IllegalArgumentException("invalid catalogue:\n  " + problems.joinToString("\n  "))

/** An official input APK as described by the catalogue. */
data class CatalogueInput(
    val id: String, val file: String, val sha256: String, val label: String, val packageName: String,
    val versionName: String, val versionCode: Long, val target: String, val role: String,
    val raw: Map<String, Any?>,
)

data class FamilyRule(val id: String, val packageName: String, val markerClass: String?)

data class CataloguePattern(
    val id: String,
    /** Family ids, or null for every family (`"*"`). */
    val families: List<String>?,
    val title: String,
    val description: String,
    val optional: Boolean,
    val steps: List<Map<String, Any?>>,
    /** Optional patterns sharing a group are always enabled or disabled together; null when ungrouped. */
    val optionGroup: String? = null,
    /** Optional pattern switched off unless the user turns it on (builds ship without it by default). */
    val offByDefault: Boolean = false,
)

data class CatalogueProfile(
    val name: String,
    val description: String,
    val families: Map<String, List<String>>,
    val inject: Map<String, List<String>>,
)

sealed class OutputSource {
    data class Patched(val input: String, val profile: String) : OutputSource()
    data class OwnApp(val path: String) : OutputSource()
}

data class CatalogueOutput(val id: String, val device: String, val packageName: String, val from: OutputSource)

data class InstallStep(val output: String, val then: String?)

data class Grant(val kind: String, val fields: Map<String, String>, val optional: Boolean, val note: String?)

data class StockApp(val device: String, val packageName: String, val optional: Boolean, val reason: String)

/** The parsed and validated catalogue. Build it with [Catalogue.parse]. */
class Catalogue private constructor(
    val schema: Int,
    val serial: Long,
    val minAppVersion: Long,
    val inputs: List<CatalogueInput>,
    val families: List<FamilyRule>,
    val patterns: Map<String, CataloguePattern>,
    val profiles: Map<String, CatalogueProfile>,
    val outputs: List<CatalogueOutput>,
    val installOrder: List<InstallStep>,
    val grants: Map<String, List<Grant>>,
    val stockAppsToDisable: List<StockApp>,
) {
    /** A runner for this catalogue's patterns with the package prefix [prefix]. */
    fun runner(prefix: String) = ProfileRunner(patterns, prefix)

    fun profile(name: String): CatalogueProfile = profiles[name]
        ?: throw NoSuchElementException("unknown profile '$name' (available: ${profiles.keys.sorted().joinToString(", ")})")

    /**
     * Family id of an APK: the first rule whose `package` equals [manifestPackage] and, if it has a
     * `markerClass`, whose class exists in [smali]; `unknown:<package>` otherwise.
     */
    fun detectFamily(manifestPackage: String, smali: SmaliTree): String {
        for (rule in families) {
            if (rule.packageName != manifestPackage) continue
            if (rule.markerClass == null || smali.classPath(rule.markerClass) != null) return rule.id
        }
        return "unknown:$manifestPackage"
    }

    /**
     * Checks a set of patterns the user switched off: every id must exist and be `optional`, and an
     * `optionGroup` is switched off as a whole or not at all. Throws [PatchFailure] otherwise.
     */
    fun requireDisablable(disabled: Set<String>) {
        val unknown = disabled.filter { it !in patterns }.sorted()
        if (unknown.isNotEmpty()) throw PatchFailure("cannot disable unknown patterns: ${unknown.joinToString(", ")}")
        val required = disabled.filter { !patterns.getValue(it).optional }.sorted()
        if (required.isNotEmpty()) throw PatchFailure("cannot disable required patterns: ${required.joinToString(", ")}")
        for (group in disabled.mapNotNull { patterns.getValue(it).optionGroup }.toSet()) {
            val members = patterns.values.filter { it.optionGroup == group }.map { it.id }
            if (!disabled.containsAll(members)) {
                throw PatchFailure("option group '$group' must be disabled as a whole: ${members.joinToString(", ")}")
            }
        }
    }

    companion object {
        private val OPTION_GROUP_RE = Regex("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")

        const val SCHEMA = 1
        const val SAMPLE_PREFIX = "com.example"

        private val TOP_KEYS = listOf("schema", "serial", "minAppVersion", "inputs", "families", "profiles",
            "patterns", "outputs", "installOrder", "grants", "stockAppsToDisable")
        private val DEVICES = listOf("phone", "watch")
        private val INSTALL_STEPS = listOf("userOnboarding")

        private val ID_RE = Regex("^[a-z][a-z0-9-]*$")
        private val PACKAGE_RE = Regex("^[a-zA-Z]\\w*(\\.[a-zA-Z]\\w*)+$")
        private val OWN_APP_RE = Regex("^[a-z][a-z0-9-]*(/[a-z][a-z0-9-]*)*$")
        private val PERMISSION_RE = Regex("^[a-zA-Z][\\w.]*$")
        private val APPOP_RE = Regex("^[A-Z][A-Z0-9_]*$")
        private val SETTING_KEY_RE = Regex("^[a-z][a-z0-9_]*$")
        private val SETTING_VALUE_RE = Regex("^\\w[\\w.-]{0,63}$")
        private val GLOBAL_SETTING_VALUE_RE = Regex("^L[a-zA-Z0-9_/\$-]{0,127}\$")
        private val COMPONENT_RE = Regex("^(\\.[A-Za-z]\\w*)+$|^[A-Za-z]\\w*(\\.[A-Za-z]\\w*)+$")
        private val NOTE_RE = Regex("^[^\\u0000-\\u001f\\u007f]{1,300}$")
        private val ALLOW_RE = Regex("^allow$")

        /** The regexes use ASCII `\w`, like Python's re.ASCII; Kotlin's `\w` is ASCII by default. */
        private val GRANT_KINDS: Map<String, Map<String, Regex>> = mapOf(
            "permission" to mapOf("name" to PERMISSION_RE),
            "appop" to mapOf("op" to APPOP_RE, "mode" to ALLOW_RE),
            "notificationListener" to mapOf("component" to COMPONENT_RE),
            "deviceIdleWhitelist" to emptyMap(),
            "secureSetting" to mapOf("key" to SETTING_KEY_RE, "value" to SETTING_VALUE_RE),
            "globalSetting" to mapOf("key" to SETTING_KEY_RE, "value" to GLOBAL_SETTING_VALUE_RE),
        )

        private val PLACEHOLDER = Regex("\\$\\{([^\\}]*)\\}")

        /** Package prefix substitutions for `${PKG_PREFIX}` / `${PKG_PATH}`. */
        fun placeholders(prefix: String): Map<String, String> =
            mapOf("PKG_PREFIX" to prefix, "PKG_PATH" to prefix.replace('.', '/'))

        /** Expands the placeholders in every string value of a (nested) JSON value; unknown ones are errors. */
        fun expand(value: Any?, prefix: String): Any? {
            val known = placeholders(prefix)
            return when (value) {
                is String -> PLACEHOLDER.replace(value) { m ->
                    known[m.groupValues[1]] ?: throw OpArgError("unknown placeholder \${${m.groupValues[1]}}")
                }
                is List<*> -> value.map { expand(it, prefix) }
                is Map<*, *> -> value.entries.associateTo(LinkedHashMap()) { (k, v) -> k as String to expand(v, prefix) }
                else -> value
            }
        }

        /** Validates one step (expansion + op/argument check) and builds the op. */
        internal fun prepareStep(step: Any?, prefix: String): Op {
            if (step !is Map<*, *> || step["op"] !is String) {
                throw OpArgError("a step must be an object with an \"op\" name")
            }
            @Suppress("UNCHECKED_CAST")
            val expanded = expand(step, prefix) as Map<String, Any?>
            return OpRegistry.prepare(expanded["op"] as String, expanded.filterKeys { it != "op" })
        }

        /** Parses [text] (the exact catalogue.json content) and validates it; throws [CatalogueException]. */
        fun parse(text: String): Catalogue {
            val cat = try {
                Json.parse(text)
            } catch (e: Json.ParseException) {
                throw CatalogueException(listOf("not valid JSON: ${e.message}"))
            }
            val problems = validate(cat)
            if (problems.isNotEmpty()) throw CatalogueException(problems)
            @Suppress("UNCHECKED_CAST")
            return try {
                build(cat as Map<String, Any?>)
            } catch (e: RuntimeException) {
                // validate() should have caught it; never let a malformed catalogue escape as anything else
                throw CatalogueException(listOf("malformed catalogue: ${e.message ?: e.javaClass.simpleName}"))
            }
        }

        /**
         * The only entry point for a catalogue from outside the app: verifies [signature] over the
         * exact [bytes] with [publicKey], decodes them as strict UTF-8 and parses them. Throws
         * [CatalogueException] on any failure, including a bad signature.
         */
        fun parseSigned(bytes: ByteArray, signature: ByteArray, publicKey: java.security.PublicKey): Catalogue {
            if (!CatalogueVerifier.verify(bytes, signature, publicKey)) {
                throw CatalogueException(listOf("the catalogue signature does not verify"))
            }
            val text = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (e: java.nio.charset.CharacterCodingException) {
                throw CatalogueException(listOf("the catalogue is not valid UTF-8"))
            }
            return parse(text)
        }

        // ------------------------------------------------------------------ validation

        private fun isStr(v: Any?) = v is String
        private fun isInt(v: Any?) = v is Long
        private fun mapOfAny(v: Any?): Map<*, *>? = v as? Map<*, *>
        private fun sampleName(v: Any?): String? {
            if (v !is String) return null
            val out = v.replace("\${PKG_PREFIX}", SAMPLE_PREFIX)
            return if ('$' in out || '{' in out) null else out
        }

        private fun profileFamilies(profile: Any?): Map<*, *> =
            (profile as? Map<*, *>)?.get("families") as? Map<*, *> ?: emptyMap<Any?, Any?>()

        private fun validateGrant(where: String, g: Any?): List<String> {
            val kind = (g as? Map<*, *>)?.get("kind")
            if (g !is Map<*, *> || kind !is String || kind !in GRANT_KINDS) {
                return listOf("$where: \"kind\" must be one of ${GRANT_KINDS.keys.sorted()}")
            }
            val fields = GRANT_KINDS.getValue(kind)
            val out = ArrayList<String>()
            val unknown = g.keys.map { it as String }.toSet() - setOf("kind", "optional", "note") - fields.keys
            if (unknown.isNotEmpty()) out.add("$where: unknown fields ${unknown.sorted()} for kind '$kind'")
            for ((name, rx) in fields) {
                val v = g[name]
                if (v !is String || !rx.matches(v)) out.add("$where: field '$name' missing or not matching ${rx.pattern}")
            }
            if ("optional" in g && g["optional"] !is Boolean) out.add("$where: optional must be a boolean")
            if ("note" in g && !(g["note"] is String && NOTE_RE.matches(g["note"] as String))) {
                out.add("$where: note must be a single-line string of 1..300 characters")
            }
            return out
        }

        /** Port of `_validate_install`: problems of outputs / installOrder / grants / stockAppsToDisable. */
        private fun validateInstall(cat: Map<*, *>, inputTargets: Map<String, Any?>, profiles: Map<*, *>): List<String> {
            val problems = ArrayList<String>()
            var outputs = cat["outputs"]
            val outIds = ArrayList<String>()
            val seenPkgs = HashSet<Pair<String, String>>()
            if (outputs !is List<*> || outputs.isEmpty()) {
                problems.add("outputs must be a non-empty list")
                outputs = emptyList<Any?>()
            }
            for ((i, o) in (outputs as List<*>).withIndex()) {
                val where = "outputs[$i]"
                if (o !is Map<*, *> || o.keys != setOf("id", "device", "package", "from")) {
                    problems.add("$where: expected exactly id, device, package, from")
                    continue
                }
                val id = o["id"]
                if (id !is String || !ID_RE.matches(id)) {
                    problems.add("$where: id must match ${ID_RE.pattern}")
                } else if (id in outIds) {
                    problems.add("$where: duplicate id '$id'")
                } else {
                    outIds.add(id)
                }
                if (o["device"] !in DEVICES) problems.add("$where: device must be one of $DEVICES")
                val pkg = sampleName(o["package"])
                if (pkg == null || !PACKAGE_RE.matches(pkg)) {
                    problems.add("$where: package '${o["package"]}' is not a valid package name " +
                        "(only \${PKG_PREFIX} may be used as a placeholder)")
                } else if (!seenPkgs.add(o["device"].toString() to pkg)) {
                    problems.add("$where: package '${o["package"]}' repeated on device ${o["device"]}")
                }
                val src = o["from"]
                if (src is Map<*, *> && src.keys == setOf("input", "profile")) {
                    val input = src["input"]
                    val profile = src["profile"]
                    if (input !is String || profile !is String) {
                        problems.add("$where: from.input and from.profile must be strings")
                    } else {
                        if (input !in inputTargets) {
                            problems.add("$where: from.input '$input' is not in inputs")
                        } else if (inputTargets[input] != o["device"]) {
                            problems.add("$where: from.input '$input' targets '${inputTargets[input]}', not '${o["device"]}'")
                        }
                        if (profile !in profiles) {
                            problems.add("$where: from.profile '$profile' is not in profiles")
                        } else if (input !in profileFamilies(profiles[profile])) {
                            problems.add("$where: profile '$profile' has no patterns for '$input'")
                        }
                    }
                } else if (src is Map<*, *> && src.keys == setOf("ownApp")) {
                    val a = src["ownApp"]
                    if (a !is String || !OWN_APP_RE.matches(a)) problems.add("$where: from.ownApp must match ${OWN_APP_RE.pattern}")
                } else {
                    problems.add("$where: from must be {\"input\", \"profile\"} or {\"ownApp\"}")
                }
            }

            var order = cat["installOrder"]
            if (order !is List<*>) {
                problems.add("installOrder must be a list")
                order = emptyList<Any?>()
            }
            val ordered = ArrayList<String>()
            for ((i, step) in (order as List<*>).withIndex()) {
                val where = "installOrder[$i]"
                if (step !is Map<*, *> || !setOf("output", "then").containsAll(step.keys) || "output" !in step) {
                    problems.add("$where: expected {\"output\", \"then\"?}")
                    continue
                }
                val out = step["output"]
                if (out !in outIds) {
                    problems.add("$where: unknown output '$out'")
                } else if (out in ordered) {
                    problems.add("$where: output '$out' listed twice")
                } else {
                    ordered.add(out as String)
                }
                if ("then" in step && step["then"] !in INSTALL_STEPS) problems.add("$where: then must be one of $INSTALL_STEPS")
            }
            for (oid in outIds) if (oid !in ordered) problems.add("installOrder: output '$oid' is missing")

            var grants = cat["grants"]
            if (grants !is Map<*, *>) {
                problems.add("grants must be an object")
                grants = emptyMap<String, Any?>()
            }
            for ((oid, lst) in grants as Map<*, *>) {
                val where = "grants/$oid"
                if (oid !in outIds) problems.add("$where: not an output id")
                if (lst !is List<*>) {
                    problems.add("$where: must be a list")
                    continue
                }
                val seen = ArrayList<String?>()
                for ((i, g) in lst.withIndex()) {
                    problems.addAll(validateGrant("$where[$i]", g))
                    val key = (g as? Map<*, *>)?.let { m ->
                        m.entries.filter { it.key != "optional" && it.key != "note" }
                            .sortedBy { it.key as String }.joinToString(",") { "${it.key}=${it.value}" }
                    }
                    if (key != null && key in seen) problems.add("$where[$i]: duplicate grant")
                    seen.add(key)
                }
            }

            var stock = cat["stockAppsToDisable"]
            if (stock !is List<*>) {
                problems.add("stockAppsToDisable must be a list")
                stock = emptyList<Any?>()
            }
            for ((i, s) in (stock as List<*>).withIndex()) {
                val where = "stockAppsToDisable[$i]"
                if (s !is Map<*, *> || s.keys != setOf("device", "package", "optional", "reason")) {
                    problems.add("$where: expected exactly device, package, optional, reason")
                    continue
                }
                if (s["device"] !in DEVICES) problems.add("$where: device must be one of $DEVICES")
                val p = s["package"]
                if (p !is String || !PACKAGE_RE.matches(p)) problems.add("$where: package is not a valid package name")
                if (s["optional"] !is Boolean) problems.add("$where: optional must be a boolean")
                val r = s["reason"]
                if (!(r is String && NOTE_RE.matches(r))) problems.add("$where: reason must be a single-line string of 1..300 characters")
            }
            return problems
        }

        private val SHA256_RE = Regex("^[0-9a-f]{64}$")
        private val INPUT_ROLES = listOf("patched", "installed-stock")

        /** The checks of `scripts/gen_sha256sums.py validate` on the catalogue's copy of the inputs. */
        private fun validateInputs(inputs: Any?): List<String> {
            if (inputs !is List<*>) return listOf("inputs must be a list")
            val problems = ArrayList<String>()
            val ids = HashSet<String>()
            for ((n, e) in inputs.withIndex()) {
                val where = "inputs[$n]"
                if (e !is Map<*, *>) {
                    problems.add("$where: must be an object")
                    continue
                }
                val id = e["id"]
                if (id !is String || !ID_RE.matches(id)) problems.add("$where: id must match ${ID_RE.pattern}")
                else if (!ids.add(id)) problems.add("$where: duplicate id '$id'")
                for (k in listOf("file", "versionName")) if (e[k] !is String) problems.add("$where: $k must be a string")
                val sha = e["sha256"]
                if (sha !is String || !SHA256_RE.matches(sha)) problems.add("$where: sha256 is not 64 lowercase hex chars")
                val pkg = e["package"]
                if (pkg !is String || !PACKAGE_RE.matches(pkg)) problems.add("$where: package is not a valid package name")
                if (!isInt(e["versionCode"])) problems.add("$where: versionCode must be an integer")
                if (e["target"] !in DEVICES) problems.add("$where: target must be one of $DEVICES")
                if (e["role"] !in INPUT_ROLES) problems.add("$where: role must be one of $INPUT_ROLES")
                if (e.containsKey("label") && e["label"] !is String) problems.add("$where: label must be a string")
            }
            return problems
        }

        /** Problems of the structure, cross references and every op step. Empty list = valid. */
        fun validate(cat: Any?): List<String> {
            if (cat !is Map<*, *>) return listOf("top level must be an object")
            val problems = ArrayList<String>()
            for (k in TOP_KEYS) if (k !in cat) problems.add("missing top-level key '$k'")
            for (k in cat.keys) if (k !in TOP_KEYS) problems.add("unknown top-level key '$k'")
            if (problems.isNotEmpty()) return problems
            if (cat["schema"] != SCHEMA.toLong()) problems.add("schema is ${cat["schema"]}, this engine understands $SCHEMA")
            for (k in listOf("serial", "minAppVersion")) {
                val v = cat[k]
                if (!(isInt(v) && (v as Long) >= 1)) problems.add("$k must be an integer >= 1")
            }
            problems.addAll(validateInputs(cat["inputs"]))

            val famIds = ArrayList<String>()
            val fams = cat["families"]
            if (fams !is List<*> || fams.isEmpty()) {
                problems.add("families must be a non-empty list")
            } else {
                for ((i, rule) in fams.withIndex()) {
                    if (rule !is Map<*, *> || rule["id"] !is String || rule["package"] !is String ||
                        (rule.keys - setOf("id", "package", "markerClass")).isNotEmpty() ||
                        (rule.containsKey("markerClass") && rule["markerClass"] !is String)) {
                        problems.add("families[$i]: expected {\"id\", \"package\", \"markerClass\"?}")
                        continue
                    }
                    if (rule["id"] in famIds) problems.add("families[$i]: duplicate id '${rule["id"]}'")
                    famIds.add(rule["id"] as String)
                }
            }

            val patternsRaw = cat["patterns"]
            val patterns: Map<*, *> = patternsRaw as? Map<*, *> ?: emptyMap<Any?, Any?>()
            if (patternsRaw !is Map<*, *>) problems.add("patterns must be an object")
            val optionGroups = LinkedHashMap<String, MutableList<Any?>>()
            for ((pid, p) in patterns) {
                val where = "pattern $pid"
                val keys = setOf("families", "title", "description", "optional", "ops")
                if (p !is Map<*, *> || !p.keys.containsAll(keys) ||
                    (p.keys - keys - "optionGroup" - "offByDefault").isNotEmpty()) {
                    problems.add("$where: must have exactly the keys ${keys.sorted()} " +
                        "(plus optionally \"optionGroup\" and \"offByDefault\")")
                    continue
                }
                val pf = p["families"]
                if (pf != "*" && !(pf is List<*> && pf.isNotEmpty() && pf.all { it in famIds })) {
                    problems.add("$where: families must be \"*\" or a list of known family ids")
                }
                for (k in listOf("title", "description")) {
                    val v = p[k]
                    if (v !is String || v.isEmpty()) problems.add("$where: $k must be a non-empty string")
                }
                if (p["optional"] !is Boolean) problems.add("$where: optional must be a boolean")
                if ("offByDefault" in p) {
                    if (p["offByDefault"] !is Boolean) {
                        problems.add("$where: offByDefault must be a boolean")
                    } else if (p["offByDefault"] == true && p["optional"] != true) {
                        problems.add("$where: offByDefault is only allowed on optional patterns")
                    }
                }
                if ("optionGroup" in p) {
                    val group = p["optionGroup"]
                    if (group !is String || !OPTION_GROUP_RE.matches(group)) {
                        problems.add("$where: optionGroup must be a lowercase kebab-case string")
                    } else if (p["optional"] != true) {
                        problems.add("$where: optionGroup is only allowed on optional patterns")
                    } else {
                        optionGroups.getOrPut(group) { ArrayList() }.add(pid)
                    }
                }
                val ops = p["ops"]
                if (ops !is List<*> || ops.isEmpty()) {
                    problems.add("$where: ops must be a non-empty list")
                    continue
                }
                for ((i, step) in ops.withIndex()) {
                    try {
                        prepareStep(step, SAMPLE_PREFIX)
                    } catch (e: OpArgError) {
                        problems.add("$where step ${i + 1}: ${e.message}")
                    } catch (e: RuntimeException) {
                        problems.add("$where step ${i + 1}: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
            }

            for ((group, members) in optionGroups) {
                if (members.size < 2) problems.add("optionGroup '$group': needs at least two patterns, has $members")
            }

            val used = HashSet<Any?>()
            val profilesRaw = cat["profiles"]
            val profiles: Map<*, *> = profilesRaw as? Map<*, *> ?: emptyMap<Any?, Any?>()
            if (profilesRaw !is Map<*, *>) problems.add("profiles must be an object")
            for ((name, prof) in profiles) {
                val where = "profile $name"
                if (prof !is Map<*, *> || prof.keys != setOf("description", "families", "inject") ||
                    prof["families"] !is Map<*, *> || prof["inject"] !is Map<*, *>) {
                    problems.add("$where: must have exactly description, families, inject")
                    continue
                }
                for ((fam, ids) in prof["families"] as Map<*, *>) {
                    if (fam !in famIds) problems.add("$where: unknown family '$fam'")
                    if (ids !is List<*> || ids.size != ids.toSet().size) {
                        problems.add("$where/$fam: must be a list without duplicates")
                        continue
                    }
                    for (pid in ids) {
                        used.add(pid)
                        val pat = patterns[pid]
                        if (pat == null) {
                            problems.add("$where/$fam: unknown pattern '$pid'")
                        } else if (pat is Map<*, *> && pat["families"] != "*" &&
                            fam !in ((pat["families"] as? List<*>) ?: emptyList<Any?>())) {
                            problems.add("$where/$fam: pattern '$pid' is not valid for $fam")
                        }
                    }
                }
                for ((fam, names) in prof["inject"] as Map<*, *>) {
                    if (fam !in (prof["families"] as Map<*, *>)) problems.add("$where: inject for family '$fam' without patterns")
                    if (names !is List<*> || !names.all { it is String }) problems.add("$where/inject/$fam: must be a list of names")
                }
            }
            for (pid in patterns.keys) if (pid !in used) problems.add("pattern $pid: not used by any profile")
            val inputs = cat["inputs"]
            if (inputs is List<*>) {
                val targets = LinkedHashMap<String, Any?>()
                for (i in inputs) if (i is Map<*, *> && i["id"] is String) targets[i["id"] as String] = i["target"]
                problems.addAll(validateInstall(cat, targets, profiles))
            }
            return problems
        }

        // ------------------------------------------------------------------ model

        @Suppress("UNCHECKED_CAST")
        private fun build(cat: Map<String, Any?>): Catalogue {
            val inputs = (cat["inputs"] as List<Map<String, Any?>>).map { i ->
                CatalogueInput(
                    id = i["id"] as String, file = i["file"] as? String ?: "", sha256 = i["sha256"] as? String ?: "",
                    label = i["label"] as? String ?: "", packageName = i["package"] as? String ?: "",
                    versionName = i["versionName"] as? String ?: "", versionCode = i["versionCode"] as? Long ?: 0L,
                    target = i["target"] as? String ?: "", role = i["role"] as? String ?: "", raw = i,
                )
            }
            val families = (cat["families"] as List<Map<String, Any?>>).map {
                FamilyRule(it["id"] as String, it["package"] as String, it["markerClass"] as String?)
            }
            val patterns = (cat["patterns"] as Map<String, Map<String, Any?>>).mapValues { (id, p) ->
                CataloguePattern(
                    id = id, families = (p["families"] as? List<String>), title = p["title"] as String,
                    description = p["description"] as String, optional = p["optional"] as Boolean,
                    steps = p["ops"] as List<Map<String, Any?>>, optionGroup = p["optionGroup"] as String?,
                    offByDefault = p["offByDefault"] as? Boolean ?: false,
                )
            }
            val profiles = (cat["profiles"] as Map<String, Map<String, Any?>>).mapValues { (name, p) ->
                CatalogueProfile(name, p["description"] as String,
                    p["families"] as Map<String, List<String>>, p["inject"] as Map<String, List<String>>)
            }
            val outputs = (cat["outputs"] as List<Map<String, Any?>>).map { o ->
                val from = o["from"] as Map<String, String>
                CatalogueOutput(o["id"] as String, o["device"] as String, o["package"] as String,
                    if ("ownApp" in from) OutputSource.OwnApp(from.getValue("ownApp"))
                    else OutputSource.Patched(from.getValue("input"), from.getValue("profile")))
            }
            val order = (cat["installOrder"] as List<Map<String, Any?>>).map { InstallStep(it["output"] as String, it["then"] as String?) }
            val grants = (cat["grants"] as Map<String, List<Map<String, Any?>>>).mapValues { (_, l) ->
                l.map { g ->
                    Grant(g["kind"] as String,
                        g.filterKeys { it != "kind" && it != "optional" && it != "note" }.mapValues { it.value as String },
                        g["optional"] as Boolean? ?: false, g["note"] as String?)
                }
            }
            val stock = (cat["stockAppsToDisable"] as List<Map<String, Any?>>).map {
                StockApp(it["device"] as String, it["package"] as String, it["optional"] as Boolean, it["reason"] as String)
            }
            return Catalogue(SCHEMA, cat["serial"] as Long, cat["minAppVersion"] as Long, inputs, families, patterns,
                profiles, outputs, order, grants, stock)
        }
    }
}

/** What one pattern did (or why it failed). */
class PatternResult(val id: String, val description: String, val log: List<String>, val error: String? = null)

/** A listed pattern did not match or raised: the build must abort. [report] holds the partial results. */
class PatternRequiredException(message: String, val report: List<PatternResult> = emptyList()) : RuntimeException(message)

/** Runs the patterns of a profile on a [PatchContext]. */
class ProfileRunner(private val patterns: Map<String, CataloguePattern>, private val prefix: String) {

    private fun ops(pattern: CataloguePattern): List<Pair<String, Op>> =
        pattern.steps.mapIndexed { i, step ->
            val op = try {
                Catalogue.prepareStep(step, prefix)
            } catch (e: OpArgError) {
                throw PatchFailure("step ${i + 1} (${(step["op"] ?: "?")}): ${e.message}")
            }
            (step["op"] as String) to op
        }

    private fun runPattern(ctx: PatchContext, pattern: CataloguePattern, verify: Boolean): List<String> {
        val log = ArrayList<String>()
        val prepared = ops(pattern)
        for ((i, p) in prepared.withIndex()) {
            val (name, op) = p
            try {
                log.addAll(if (verify) op.verifyApplied(ctx) else op.apply(ctx))
            } catch (e: PatchFailure) {
                throw PatchFailure("step ${i + 1} ($name): ${e.message}")
            } catch (e: OpArgError) {
                throw PatchFailure("step ${i + 1} ($name): ${e.message}")
            }
        }
        return log
    }

    /**
     * Runs the listed patterns IN ORDER. Every listed pattern is required: an exception or an empty
     * result aborts with [PatternRequiredException] (an `optional` pattern may log nothing but must
     * not raise). With [verify] the ops check the patched form instead of applying (self-check of a
     * finished APK); an optional pattern that cannot be verified is reported, not fatal.
     */
    fun run(ctx: PatchContext, ids: List<String>, verify: Boolean = false, onPattern: (PatternResult) -> Unit = {}): List<PatternResult> {
        val unknown = ids.filter { it !in patterns }
        if (unknown.isNotEmpty()) throw PatternRequiredException("unknown pattern ids in profile: " + unknown.joinToString(", "))
        val report = ArrayList<PatternResult>()
        var fatal: String? = null
        for (pid in ids) {
            val p = patterns.getValue(pid)
            var log: List<String> = emptyList()
            var error: String? = null
            try {
                log = runPattern(ctx, p, verify)
            } catch (e: PatchFailure) {
                error = e.message ?: "failed"
            } catch (e: RuntimeException) {
                error = "${e.javaClass.simpleName}: ${e.message}"
            }
            if (error != null) {
                if (verify && p.optional) {
                    log = listOf("optional, not present: $error")
                    error = null
                } else {
                    fatal = "pattern '$pid' raised: $error"
                }
            } else if (log.isEmpty() && !p.optional) {
                fatal = "pattern '$pid' matched nothing (did the base change? check the anchors)"
                error = "matched nothing"
            }
            val result = PatternResult(pid, p.description, log, error)
            report.add(result)
            onPattern(result)
            if (fatal != null) break
        }
        if (fatal != null) throw PatternRequiredException("BUILD FAILED - $fatal", report)
        return report
    }
}
