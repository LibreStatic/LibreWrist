package io.openshm.patcher.engine

/** Manifest selector evaluation (port of `_select_elements`). */
internal object ManifestSelect {
    private fun simple(v: String?) = (v ?: "\u0000").substringAfterLast('.')

    /** Elements of [root]'s tree matched by [sel], in path order, without duplicates. */
    fun select(root: ManifestElement, sel: Select): List<ManifestElement> {
        val seen = HashSet<ManifestElement>()
        val out = ArrayList<ManifestElement>()
        for (path in sel.paths) {
            val cands: List<ManifestElement> = when (path) {
                "." -> listOf(root)
                "**" -> all(root)
                else -> findAll(root, path.split('/'))
            }
            for (e in cands) {
                if (e in seen) continue
                if (sel.attr.any { (k, v) -> e.attr(k) != v }) continue
                if (sel.attrIn.any { (k, vals) -> e.attr(k) !in vals }) continue
                if (sel.simpleNameIn.any { (k, vals) -> simple(e.attr(k)) !in vals }) continue
                if (sel.parentAttr.isNotEmpty()) {
                    val par = e.parent
                    if (par == null || sel.parentAttr.any { (k, v) -> par.attr(k) != v }) continue
                }
                if (sel.hasChild.any { (tag, attrs) ->
                        e.children.none { c -> c.tag == tag && attrs.all { (k, v) -> c.attr(k) == v } }
                    }) continue
                seen.add(e)
                out.add(e)
            }
        }
        return out
    }

    private fun all(e: ManifestElement): List<ManifestElement> {
        val out = ArrayList<ManifestElement>()
        fun walk(x: ManifestElement) { out.add(x); x.children.forEach { walk(it) } }
        walk(e)
        return out
    }

    private fun findAll(root: ManifestElement, tags: List<String>): List<ManifestElement> {
        var level = listOf(root)
        for (t in tags) level = level.flatMap { p -> p.children.filter { it.tag == t } }
        return level
    }

    /** Applies [expect] to the matched elements (port of `_expect_elements`). */
    fun expect(sel: Select, elems: List<ManifestElement>, expect: Expect) {
        val label = "manifest select " + sel.describe()
        if (expect == Expect.EachListed) {
            val keys = listOfNotNull(
                if (sel.attrIn.isNotEmpty()) "attrIn" else null,
                if (sel.simpleNameIn.isNotEmpty()) "simpleNameIn" else null,
            )
            val source = if (keys.size == 1) (if (keys[0] == "attrIn") sel.attrIn else sel.simpleNameIn) else null
            if (source == null || source.size != 1) {
                throw OpArgError("expect \"eachListed\" needs exactly one attrIn/simpleNameIn entry")
            }
            val (attr, wanted) = source.entries.first()
            val found = elems.map { if (keys[0] == "simpleNameIn") simple(it.attr(attr)).let { s -> if (it.attr(attr) == null) "" else s } else it.attr(attr) ?: "" }
            if (found.sorted() != wanted.sorted()) {
                throw PatchFailure("$label: expected ${wanted.sorted()} and matched ${found.sorted()} " +
                    "(did the base change? check the anchors)")
            }
            return
        }
        checkExpect(label, elems.size, expect)
    }

    /** [sel] without the conditions on [attrs] (used by the applied-mode checks). */
    fun relaxed(sel: Select, attrs: Collection<String>): Select {
        val raw = LinkedHashMap(sel.raw)
        for (key in listOf("attr", "attrIn", "simpleNameIn")) {
            @Suppress("UNCHECKED_CAST")
            val m = (raw[key] as? Map<String, Any?>) ?: continue
            val kept = m.filterKeys { it !in attrs }
            if (kept.isEmpty()) raw.remove(key) else raw[key] = kept
        }
        return Select(raw)
    }
}

private fun describeSelect(s: Select) = s.describe()

private fun kv(m: Map<String, String>) = m.entries.joinToString(", ", "{", "}") { "'${it.key}': '${it.value}'" }

internal class ManifestExpect(a: Args) : Op("manifestExpect") {
    private val select = a.select("select")
    private val expect = a.expect("expect")

    override fun apply(ctx: PatchContext): List<String> {
        val elems = ManifestSelect.select(ctx.manifest.root, select)
        ManifestSelect.expect(select, elems, expect)
        return listOf("manifest: ${elems.size} element(s) match ${describeSelect(select)}")
    }

    // A precondition about the original manifest; later patterns may legitimately change it.
    override fun verifyApplied(ctx: PatchContext): List<String> =
        listOf("manifest: precondition on ${describeSelect(select)} (not re-checked)")
}

internal class ManifestRemove(a: Args) : Op("manifestRemove") {
    private val select = a.select("select")
    private val expect = a.expect("expect")

    override fun apply(ctx: PatchContext): List<String> {
        val elems = ManifestSelect.select(ctx.manifest.root, select)
        ManifestSelect.expect(select, elems, expect)
        if (elems.any { it.parent == null }) throw PatchFailure("cannot remove the manifest root")
        elems.forEach { it.remove() }
        return listOf("manifest: ${elems.size} element(s) removed (${describeSelect(select)})")
    }

    override fun verifyApplied(ctx: PatchContext): List<String> {
        val left = ManifestSelect.select(ctx.manifest.root, select)
        if (left.isNotEmpty()) {
            throw PatchFailure("manifest: ${left.size} element(s) still match ${describeSelect(select)}")
        }
        return listOf("manifest: none match ${describeSelect(select)}")
    }
}

internal class ManifestSetAttr(a: Args) : Op("manifestSetAttr") {
    private val select = a.select("select")
    private val attrs = a.strMap("attrs")!!
    private val expect = a.expect("expect")

    init { attrs.keys.forEach { attrName(it) } }

    override fun apply(ctx: PatchContext): List<String> {
        val elems = ManifestSelect.select(ctx.manifest.root, select)
        ManifestSelect.expect(select, elems, expect)
        for (e in elems) for ((k, v) in attrs) e.setAttr(k, v)
        return listOf("manifest: ${elems.size} element(s) set ${kv(attrs)} (${describeSelect(select)})")
    }

    override fun verifyApplied(ctx: PatchContext): List<String> {
        val relaxed = ManifestSelect.relaxed(select, attrs.keys)
        val set = ManifestSelect.select(ctx.manifest.root, relaxed)
            .filter { e -> attrs.all { (k, v) -> e.attr(k) == v } }
        return appliedCount("manifest setAttr ${describeSelect(select)}", set.map { it.tag }, expect)
    }
}

internal class ManifestRemoveAttr(a: Args) : Op("manifestRemoveAttr") {
    private val select = a.select("select")
    private val attrs = a.strs("attrs")
    private val expect = a.expect("expect")

    init { attrs.forEach { attrName(it) } }

    override fun apply(ctx: PatchContext): List<String> {
        val elems = ManifestSelect.select(ctx.manifest.root, select)
        ManifestSelect.expect(select, elems, expect)
        // Check everything first so a failing op leaves the document untouched.
        for (e in elems) for (k in attrs) {
            if (e.attr(k) == null) throw PatchFailure("manifest element has no $k (did the base change?)")
        }
        for (e in elems) for (k in attrs) e.removeAttr(k)
        return listOf("manifest: ${elems.size} element(s) lost $attrs (${describeSelect(select)})")
    }

    override fun verifyApplied(ctx: PatchContext): List<String> {
        val relaxed = ManifestSelect.relaxed(select, attrs)
        val clean = ManifestSelect.select(ctx.manifest.root, relaxed).filter { e -> attrs.all { e.attr(it) == null } }
        return appliedCount("manifest removeAttr ${describeSelect(select)}", clean.map { it.tag }, expect)
    }
}

internal class ManifestAddChild(a: Args) : Op("manifestAddChild") {
    private val parent = a.select("parent")
    private val tag = a.str("tag")
    private val attrs = a.strMap("attrs") ?: emptyMap()
    private val children = a.children("children") ?: emptyList()
    private val expect = a.expect("expect")

    init { attrs.keys.forEach { attrName(it) } }

    private fun build(into: ManifestElement, spec: ChildSpec) {
        val e = into.addChild(spec.tag, spec.attrs)
        spec.children.forEach { build(e, it) }
    }

    override fun apply(ctx: PatchContext): List<String> {
        val elems = ManifestSelect.select(ctx.manifest.root, parent)
        ManifestSelect.expect(parent, elems, expect)
        val spec = ChildSpec(tag, attrs.toList(), children)
        for (e in elems) build(e, spec)
        return listOf("manifest: <$tag> added to ${elems.size} element(s) (${describeSelect(parent)})" +
            (if (attrs.isNotEmpty()) " ${kv(attrs)}" else ""))
    }

    override fun verifyApplied(ctx: PatchContext): List<String> {
        val have = ManifestSelect.select(ctx.manifest.root, parent).filter { p ->
            p.children.any { c -> c.tag == tag && attrs.all { (k, v) -> c.attr(k) == v } }
        }
        return appliedCount("manifest addChild <$tag> to ${describeSelect(parent)}", have.map { it.tag }, expect)
    }
}

internal class ManifestRebrand(a: Args) : Op("manifestRebrand") {
    private val oldPkg = a.str("oldPkg")
    private val newPkg = a.str("newPkg")

    /** The attribute rewrites of the text rule, in the order of the Python op. */
    private fun rewrite(root: ManifestElement): Boolean {
        var changed = false
        fun all(e: ManifestElement, f: (ManifestElement) -> Unit) { f(e); e.children.forEach { all(it, f) } }
        all(root) { e ->
            // package="<old>" (exact value, any element carries it only on the root)
            if (e.attr("package") == oldPkg) { e.setAttr("package", newPkg); changed = true }
        }
        all(root) { e ->
            if (e.tag == "permission" || e.tag == "uses-permission") {
                val v = e.attr("android:name")
                if (v != null && v.startsWith("$oldPkg.")) { e.setAttr("android:name", newPkg + v.substring(oldPkg.length)); changed = true }
            }
        }
        for (name in listOf("android:authorities", "android:readPermission", "android:permission")) {
            all(root) { e ->
                val v = e.attr(name)
                if (v != null && v.startsWith("$oldPkg.")) { e.setAttr(name, newPkg + v.substring(oldPkg.length)); changed = true }
            }
        }
        return changed
    }

    override fun apply(ctx: PatchContext): List<String> {
        if (!rewrite(ctx.manifest.root)) return emptyList()
        val log = arrayListOf("AndroidManifest.xml :: rebrand $oldPkg -> $newPkg")
        // aapt2 takes the package name of resources.arsc from the manifest, and the app resolves
        // resources by name against its own package, so the table's package chunk follows the rename.
        if (oldPkg != newPkg && ctx.hasFiles && "resources.arsc" in ctx.files.names) {
            ArscPackages.encodeName(newPkg)
            ctx.arscPackageRename = oldPkg to newPkg
            log.add("resources.arsc :: package $oldPkg -> $newPkg")
        }
        return log
    }

    override fun verifyApplied(ctx: PatchContext): List<String> {
        if (oldPkg == newPkg) return emptyList()
        val pkg = ctx.manifest.root.attr("package")
        if (pkg != newPkg) throw PatchFailure("manifest package is '$pkg', expected '$newPkg'")
        val log = arrayListOf("AndroidManifest.xml :: package $newPkg")
        if (ctx.hasFiles && "resources.arsc" in ctx.files.names) {
            val names = ArscPackages.packageNames(ctx.files.open("resources.arsc"))
            if (oldPkg in names || newPkg !in names) {
                throw PatchFailure("resources.arsc package is ${names.joinToString { "'$it'" }}, expected '$newPkg'")
            }
            log.add("resources.arsc :: package $newPkg")
        }
        return log
    }
}

internal class ManifestAddQueryPackage(a: Args) : Op("manifestAddQueryPackage") {
    private val pkg = a.str("pkg")

    private fun has(root: ManifestElement): Boolean {
        fun walk(e: ManifestElement): Boolean =
            (e.tag == "package" && e.attr("android:name") == pkg) || e.children.any { walk(it) }
        return walk(root)
    }

    override fun apply(ctx: PatchContext): List<String> {
        val root = ctx.manifest.root
        if (has(root)) return listOf("AndroidManifest.xml :: <queries> already declares $pkg")
        val queries = firstQueries(root) ?: throw PatchFailure("manifest has no <queries> block")
        queries.addChild("package", listOf("android:name" to pkg), 0)
        return listOf("AndroidManifest.xml :: <queries> + $pkg")
    }

    private fun firstQueries(e: ManifestElement): ManifestElement? {
        if (e.tag == "queries") return e
        for (c in e.children) firstQueries(c)?.let { return it }
        return null
    }

    override fun verifyApplied(ctx: PatchContext): List<String> {
        if (!has(ctx.manifest.root)) throw PatchFailure("manifest <queries> does not declare $pkg")
        return listOf("AndroidManifest.xml :: <queries> declares $pkg")
    }
}

internal class AddStringResources(a: Args) : Op("addStringResources") {
    private val resName = a.str("name")
    private val values = a.strMap("values")!!

    override fun apply(ctx: PatchContext): List<String> {
        val res = ctx.resources
        if (res.hasString(resName)) throw PatchFailure("string $resName already exists")
        for (q in values.keys) {
            if (!res.hasStringsFile(q)) throw PatchFailure("res/values$q/strings.xml not found")
        }
        val done = ArrayList<String>()
        for ((q, text) in values) {
            res.addString(q, resName, text)
            done.add("res/values$q/strings.xml :: + $resName")
        }
        return done
    }

    override fun verifyApplied(ctx: PatchContext): List<String> {
        val res = ctx.resources
        val done = ArrayList<String>()
        for ((q, text) in values) {
            val have = res.string(q, resName)
            if (have != text) throw PatchFailure("res/values$q/strings.xml: string $resName is ${have?.let { "'$it'" } ?: "missing"}")
            done.add("res/values$q/strings.xml :: $resName present")
        }
        return done
    }
}

internal class DefineResourceEntry(a: Args) : Op("defineResourceEntry") {
    private val type = a.str("type")
    private val resName = a.str("name")
    private val idText = a.str("id")
    private val id: Int

    init {
        if (!Regex("0x[0-9a-fA-F]{8}").matches(idText)) throw OpArgError("op defineResourceEntry.id: expected 0x + 8 hex digits, got '$idText'")
        id = idText.substring(2).toLong(16).toInt()
    }

    override fun apply(ctx: PatchContext): List<String> {
        val res = ctx.resources
        if (res.hasEntry(type, resName)) throw PatchFailure("resource $type/$resName already exists")
        res.defineNullEntry(type, resName, id)
        return listOf("resources.arsc :: + $type/$resName = @null at $idText")
    }

    override fun verifyApplied(ctx: PatchContext): List<String> {
        val have = ctx.resources.entryAt(id)
        if (have != "$type/$resName") throw PatchFailure("resource $idText is ${have ?: "undefined"}, expected $type/$resName")
        return listOf("resources.arsc :: $idText is $type/$resName")
    }
}
