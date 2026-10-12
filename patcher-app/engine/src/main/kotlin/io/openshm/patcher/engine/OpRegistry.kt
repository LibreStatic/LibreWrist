package io.openshm.patcher.engine

/** The op names, parameters (JSON spelling) and constructors; the equivalent of `OPS` + `prepare_op`. */
internal object OpRegistry {
    private class Def(val params: List<P>, val build: (Args) -> Op)

    private val selector = listOf(
        opt("fileRegex", T.STR), opt("fileAll", T.STRS), opt("fileAny", T.STRS),
        opt("sigExclude", T.STRS), opt("bodyAll", T.STRS), opt("bodyNone", T.STRS),
    )
    private val atLeastOne = opt("expect", T.EXPECT, Expect.AtLeastOne)

    private val defs: Map<String, Def> = mapOf(
        "insertAtMethodStart" to Def(
            listOf(req("sigRegex", T.STR), req("insert", T.TEXT)) + selector +
                listOf(opt("needRegs", T.INT, 1), opt("tail", T.TEXT), atLeastOne)) { InsertAtMethodStart(it) },
        "returnSingleton" to Def(
            listOf(req("sigRegex", T.STR), req("field", T.STR)) + selector + listOf(atLeastOne)) { ReturnSingleton(it) },
        "setMethodBody" to Def(
            listOf(req("sigRegex", T.STR), req("body", T.TEXT)) + selector +
                listOf(opt("expect", T.EXPECT, Expect.Count(1)))) { SetMethodBody(it) },
        "insertInMethodAtMatch" to Def(
            listOf(req("sigRegex", T.STR), req("pattern", T.STR), req("insert", T.TEXT)) + selector +
                listOf(opt("enumFields", T.ENUMFIELDS), opt("expect", T.EXPECT, Expect.Count(1)))) { InsertInMethodAtMatch(it) },
        "swapConst" to Def(listOf(req("fileRegex", T.STR), req("old", T.STR), req("new", T.STR),
            opt("context", T.STR), atLeastOne)) { SwapConst(it) },
        "enumOrdinalsZero" to Def(listOf(req("fileRegex", T.STR), req("values", T.STRS), atLeastOne)) { EnumOrdinalsZero(it) },
        "sweepStringCompareAutoTrue" to Def(listOf(req("constValue", T.STR), opt("replaceValue", T.STR),
            opt("compare", T.STRS), opt("excludeRegex", T.STRS), atLeastOne)) { SweepStringCompareAutoTrue(it) },
        "forceConstAfterMove" to Def(listOf(req("anchor", T.STR), opt("value", T.STR, "0x1"), atLeastOne)) { ForceConstAfterMove(it) },
        "replaceConstStringExact" to Def(listOf(req("old", T.STR), req("new", T.STR), atLeastOne)) { ReplaceConstStringExact(it) },
        "replaceAllInFilesWithAnchor" to Def(listOf(req("fileContains", T.STR), req("old", T.STR), req("new", T.STR),
            atLeastOne)) { ReplaceAllInFilesWithAnchor(it) },
        "forceInvokeResult" to Def(listOf(req("fileAnchor", T.STR), req("invokeRegex", T.STR),
            opt("precedingConst", T.STR), opt("value", T.STR, "0x1"), atLeastOne)) { ForceInvokeResult(it) },
        "verifyClassContains" to Def(listOf(req("checks", T.CHECKS))) { VerifyClassContains(it) },
        "replaceInClass" to Def(listOf(req("className", T.STR), req("old", T.TEXT), req("new", T.TEXT),
            opt("count", T.INT, 1))) { ReplaceInClass(it) },
        "replaceClassMethod" to Def(listOf(req("className", T.STR), req("header", T.STR), req("body", T.TEXT),
            opt("mustContain", T.STRS))) { ReplaceClassMethod(it) },
        "insertInClassMethod" to Def(listOf(req("className", T.STR), req("header", T.STR), req("insert", T.TEXT))) { InsertInClassMethod(it) },
        "removeSmaliDir" to Def(listOf(req("rel", T.STR))) { RemoveSmaliDir(it) },
        "addStringResources" to Def(listOf(req("name", T.STR), req("values", T.STRMAP))) { AddStringResources(it) },
        "defineResourceEntry" to Def(listOf(req("type", T.STR), req("name", T.STR), req("id", T.STR))) { DefineResourceEntry(it) },
        "manifestRebrand" to Def(listOf(req("oldPkg", T.STR), req("newPkg", T.STR))) { ManifestRebrand(it) },
        "manifestAddQueryPackage" to Def(listOf(req("pkg", T.STR))) { ManifestAddQueryPackage(it) },
        "manifestExpect" to Def(listOf(req("select", T.SELECT), req("expect", T.MEXPECT))) { ManifestExpect(it) },
        "manifestRemove" to Def(listOf(req("select", T.SELECT), req("expect", T.MEXPECT))) { ManifestRemove(it) },
        "manifestSetAttr" to Def(listOf(req("select", T.SELECT), req("attrs", T.STRMAP), req("expect", T.MEXPECT))) { ManifestSetAttr(it) },
        "manifestRemoveAttr" to Def(listOf(req("select", T.SELECT), req("attrs", T.STRS), req("expect", T.MEXPECT))) { ManifestRemoveAttr(it) },
        "manifestAddChild" to Def(listOf(req("parent", T.SELECT), req("tag", T.STR), opt("attrs", T.STRMAP),
            opt("children", T.CHILDREN), opt("expect", T.MEXPECT, Expect.Count(1)))) { ManifestAddChild(it) },
    )

    val names: Set<String> get() = defs.keys

    /** Validates a step (without the "op" key) and builds the op; throws [OpArgError] on any problem. */
    fun prepare(name: String, args: Map<String, Any?>): Op {
        val def = defs[name] ?: throw OpArgError("unknown op '$name'")
        val byJson = def.params.associateBy { it.json }
        val values = HashMap<String, Any?>()
        for ((key, value) in args) {
            val p = byJson[key] ?: throw OpArgError("op $name: unknown argument '$key'")
            values[key] = Args.normalise(p.type, value, "op $name.$key")
        }
        val missing = def.params.filter { it.required && it.json !in values }.map { it.json }
        if (missing.isNotEmpty()) throw OpArgError("op $name: missing argument(s) $missing")
        for (p in def.params) if (p.json !in values) values[p.json] = p.default
        return def.build(Args(values))
    }
}
