package io.openshm.patcher.engine

import java.util.regex.Pattern

/**
 * One catalogue step. [apply] mutates the context and returns the touched sites (log lines),
 * raising [PatchFailure] when an anchor is missing, ambiguous or has the wrong count.
 * [verifyApplied] re-checks, on an already patched APK, that the patched form is present.
 */
abstract class Op(val name: String) {
    abstract fun apply(ctx: PatchContext): List<String>
    abstract fun verifyApplied(ctx: PatchContext): List<String>
}

/** The shared method selector of insertAtMethodStart / returnSingleton / setMethodBody / ... */
internal class MethodSelector(a: Args) {
    val fileRegex = a.strOrNull("fileRegex")
    val fileAll = a.strsOrNull("fileAll") ?: emptyList()
    val fileAny = a.strsOrNull("fileAny") ?: emptyList()
    val sigRegexText = a.str("sigRegex")
    val sigRegex: Pattern = PyRegex.compile(sigRegexText)
    val sigExclude = a.strsOrNull("sigExclude") ?: emptyList()
    val bodyAll = a.strsOrNull("bodyAll") ?: emptyList()
    val bodyNone = a.strsOrNull("bodyNone") ?: emptyList()

    class Hit(val path: String, val sigs: List<String>)

    /** Files with at least one selected method, and those methods' signatures. */
    fun select(tree: SmaliTree, ignoreBodyNone: Boolean = false, ignoreBodyAll: Boolean = false): List<Hit> {
        val out = ArrayList<Hit>()
        val filter = ClassFilter(pathRegex = fileRegex,
            requireAll = fileAll + (if (ignoreBodyAll) emptyList() else bodyAll), requireAny = fileAny)
        for (path in tree.candidates(filter)) {
            val src = tree.read(path)
            if (fileAll.any { it !in src }) continue
            if (fileAny.isNotEmpty() && fileAny.none { it in src }) continue
            val sigs = ArrayList<String>()
            for ((sig, body) in SmaliText.iterMethods(src)) {
                if (!sigRegex.matcher(sig).matches()) continue
                if (sigExclude.any { it in sig }) continue
                if (!ignoreBodyAll && bodyAll.any { it !in body }) continue
                if (!ignoreBodyNone && bodyNone.any { it in body }) continue
                sigs.add(sig)
            }
            if (sigs.isNotEmpty()) out.add(Hit(path, sigs))
        }
        return out
    }
}

private fun site(path: String, sig: String) = "$path :: ${sig.take(60)}"

internal class InsertAtMethodStart(a: Args) : Op("insertAtMethodStart") {
    private val sel = MethodSelector(a)
    private val insert = a.str("insert")
    private val needRegs = a.int("needRegs")
    private val tail = a.strOrNull("tail")
    private val expect = a.expect("expect")

    override fun apply(ctx: PatchContext): List<String> {
        val done = ArrayList<String>()
        for (hit in sel.select(ctx.smali)) {
            var src = ctx.smali.read(hit.path)
            val orig = src
            for (sig in hit.sigs) {
                var (src2, ok) = SmaliText.insertAtMethodStart(src, sig, insert, needRegs)
                if (!ok) continue
                if (!tail.isNullOrEmpty()) {
                    val span = SmaliText.methodSpan(src2, sig)!!
                    var body = src2.substring(span[0], span[1])
                    if (tail !in body) {
                        val idx = maxOf(body.lastIndexOf("    return "), 0)
                        body = body.substring(0, idx) + tail + body.substring(idx)
                        src2 = src2.substring(0, span[0]) + body + src2.substring(span[1])
                    }
                }
                src = src2
                done.add(site(hit.path, sig))
            }
            if (src != orig) ctx.smali.write(hit.path, src)
        }
        checkExpect("insertAtMethodStart " + sel.sigRegexText.take(50), done.size, expect)
        return done
    }

    override fun verifyApplied(ctx: PatchContext): List<String> {
        val done = ArrayList<String>()
        for (hit in sel.select(ctx.smali, ignoreBodyNone = true)) {
            val src = ctx.smali.read(hit.path)
            for (sig in hit.sigs) {
                if (!SmaliText.startsWithInsert(src, sig, ctx.canon(insert), afterDirectives = true)) continue
                if (!tail.isNullOrEmpty()) {
                    val span = SmaliText.methodSpan(src, sig) ?: continue
                    if (ctx.canon(tail) !in src.substring(span[0], span[1])) continue
                }
                done.add(site(hit.path, sig))
            }
        }
        return appliedCount("insertAtMethodStart " + sel.sigRegexText.take(50), done, expect)
    }
}

/** Fails unless at least the expected number of patched sites is present. */
internal fun appliedCount(label: String, done: List<String>, expect: Expect): List<String> {
    if (done.size < requiredSites(expect)) {
        throw PatchFailure("$label: patched form found at ${done.size} site(s), expected at least " +
            "${requiredSites(expect)} (the APK does not carry this patch)")
    }
    return done
}

private val RETURN_TYPE = PyRegex.compile("""\)(L[^;]+;)$""")

internal class ReturnSingleton(a: Args) : Op("returnSingleton") {
    private val sel = MethodSelector(a)
    private val field = a.str("field")
    private val expect = a.expect("expect")

    private fun insertFor(sig: String): String {
        val m = RETURN_TYPE.matcher(sig)
        if (!m.find()) throw PatchFailure("returnSingleton: '$sig' does not return an object type")
        val t = m.group(1)
        return "\n    sget-object v0, $t->$field:$t\n\n    return-object v0\n"
    }

    override fun apply(ctx: PatchContext): List<String> {
        val done = ArrayList<String>()
        for (hit in sel.select(ctx.smali)) {
            var src = ctx.smali.read(hit.path)
            val orig = src
            for (sig in hit.sigs) {
                val (src2, ok) = SmaliText.insertAtMethodStart(src, sig, insertFor(sig), 1)
                if (ok) {
                    src = src2
                    done.add(site(hit.path, sig))
                }
            }
            if (src != orig) ctx.smali.write(hit.path, src)
        }
        checkExpect("returnSingleton " + sel.sigRegexText.take(50), done.size, expect)
        return done
    }

    override fun verifyApplied(ctx: PatchContext): List<String> {
        val done = ArrayList<String>()
        for (hit in sel.select(ctx.smali, ignoreBodyNone = true)) {
            val src = ctx.smali.read(hit.path)
            for (sig in hit.sigs) {
                if (RETURN_TYPE.matcher(sig).find() && SmaliText.startsWithInsert(src, sig, insertFor(sig), afterDirectives = true)) {
                    done.add(site(hit.path, sig))
                }
            }
        }
        return appliedCount("returnSingleton " + sel.sigRegexText.take(50), done, expect)
    }
}

internal class SetMethodBody(a: Args) : Op("setMethodBody") {
    private val sel = MethodSelector(a)
    private val body = a.str("body")
    private val expect = a.expect("expect")

    private fun newText(sig: String) = ".method $sig\n$body\n.end method"

    override fun apply(ctx: PatchContext): List<String> {
        val done = ArrayList<String>()
        for (hit in sel.select(ctx.smali)) {
            var src = ctx.smali.read(hit.path)
            val orig = src
            for (sig in hit.sigs) {
                val span = SmaliText.methodSpan(src, sig)
                    ?: throw PatchFailure("setMethodBody: method '${sig.take(60)}' vanished")
                val new = newText(sig)
                if (src.substring(span[0], span[1]) != new) {
                    src = src.substring(0, span[0]) + new + src.substring(span[1])
                }
                done.add(site(hit.path, sig))
            }
            if (src != orig) ctx.smali.write(hit.path, src)
        }
        checkExpect("setMethodBody " + sel.sigRegexText.take(50), done.size, expect)
        return done
    }

    override fun verifyApplied(ctx: PatchContext): List<String> {
        val done = ArrayList<String>()
        // The old body anchors are gone by design: select by signature only, then compare bodies.
        for (hit in sel.select(ctx.smali, ignoreBodyNone = true, ignoreBodyAll = true)) {
            val src = ctx.smali.read(hit.path)
            for (sig in hit.sigs) {
                val span = SmaliText.methodSpan(src, sig) ?: continue
                if (SmaliText.dropBlankLines(src.substring(span[0], span[1])) ==
                    SmaliText.dropBlankLines(ctx.canon(newText(sig)))) done.add(site(hit.path, sig))
            }
        }
        return appliedCount("setMethodBody " + sel.sigRegexText.take(50), done, expect)
    }
}

internal class InsertInMethodAtMatch(a: Args) : Op("insertInMethodAtMatch") {
    private val sel = MethodSelector(a)
    private val pattern = a.str("pattern")
    private val insert = a.str("insert")
    private val enumFields = a.enumFields("enumFields")
    private val expect = a.expect("expect")
    private val regex: Pattern
    private val names: List<String>

    init {
        if (!Regex("""\(\?P<at>""").containsMatchIn(pattern)) {
            throw OpArgError("insertInMethodAtMatch: pattern must define the named group 'at'")
        }
        regex = PyRegex.compile(pattern)
        names = PyRegex.groupNames(pattern)
    }

    private val templateVar = PyRegex.compile("""\{\{(\w+)\}\}""")

    /** enumFields lookup (reads the enum class's <clinit>). */
    private fun enumField(tree: SmaliTree, enumType: String, constName: String): String {
        val path = tree.classPath(enumType.substring(1, enumType.length - 1))
            ?: throw PatchFailure("enum $enumType: smali not found")
        val src = tree.read(path)
        val m = PyRegex.compile("""const-string \w+, "${PyRegex.escape(constName)}"(?:(?!const-string).)*?""" +
            """sput-object \w+, ${PyRegex.escape(enumType)}->(\w+):""", dotAll = true).matcher(src)
        if (!m.find()) throw PatchFailure("enum $enumType: constant $constName not found")
        return m.group(1)
    }

    /** (rendered insert, offset of the 'at' group in [text]) for the method text [text]. */
    private fun render(tree: SmaliTree, sig: String, text: String): Pair<String, Int> {
        val m = regex.matcher(text)
        if (!m.find()) {
            throw PatchFailure("${sig.take(50)}: insertion pattern not found (did the base change? check the anchors)")
        }
        val vars = LinkedHashMap<String, String?>()
        for (n in names) if (n != "at") vars[n] = m.group(PyRegex.javaGroupName(n))
        for ((v, spec) in enumFields ?: emptyMap()) {
            if (v in vars) throw PatchFailure("enumFields variable '$v' collides with a capture")
            val type = vars[spec.first] ?: throw PatchFailure("enumFields: capture '${spec.first}' did not match")
            vars[v] = enumField(tree, type, spec.second)
        }
        val rendered = SmaliText.sub(templateVar, insert) { mm ->
            val k = mm.group(1)
            if (k !in vars) throw PatchFailure("unknown template variable {{$k}}")
            vars[k] ?: throw PatchFailure("template variable {{$k}} did not match")
        }
        return rendered to m.start(PyRegex.javaGroupName("at"))
    }

    override fun apply(ctx: PatchContext): List<String> {
        val done = ArrayList<String>()
        for (hit in sel.select(ctx.smali)) {
            var src = ctx.smali.read(hit.path)
            val orig = src
            for (sig in hit.sigs) {
                val span = SmaliText.methodSpan(src, sig)
                    ?: throw PatchFailure("insertInMethodAtMatch: method '${sig.take(60)}' vanished")
                val text = src.substring(span[0], span[1])
                val (rendered, at0) = render(ctx.smali, sig, text)
                if (rendered in text) {
                    done.add("${hit.path} :: already applied")
                    continue
                }
                val at = span[0] + at0
                src = src.substring(0, at) + rendered + src.substring(at)
                done.add("${hit.path} :: ${sig.take(60)} (+insert at match)")
            }
            if (src != orig) ctx.smali.write(hit.path, src)
        }
        checkExpect("insertInMethodAtMatch " + sel.sigRegexText.take(50), done.size, expect)
        return done
    }

    override fun verifyApplied(ctx: PatchContext): List<String> {
        val done = ArrayList<String>()
        for (hit in sel.select(ctx.smali, ignoreBodyNone = true)) {
            val src = ctx.smali.read(hit.path)
            for (sig in hit.sigs) {
                val span = SmaliText.methodSpan(src, sig) ?: continue
                val text = src.substring(span[0], span[1])
                val rendered = try { render(ctx.smali, sig, text).first } catch (e: PatchFailure) { continue }
                if (ctx.canon(rendered) in text) done.add(site(hit.path, sig))
            }
        }
        return appliedCount("insertInMethodAtMatch " + sel.sigRegexText.take(50), done, expect)
    }
}

// ---------------------------------------------------------------- whole-tree smali ops

internal class SwapConst(a: Args) : Op("swapConst") {
    private val fileRegex = a.str("fileRegex")
    private val old = a.str("old")
    private val new = a.str("new")
    private val context = a.strOrNull("context")
    private val expect = a.expect("expect")

    init { PyRegex.compile(fileRegex) }

    override fun apply(ctx: PatchContext): List<String> {
        val done = ArrayList<String>()
        for (path in ctx.smali.candidates(ClassFilter(pathRegex = fileRegex, requireAll = listOfNotNull(old, context)))) {
            val src = ctx.smali.read(path)
            if (SmaliText.count(src, old) != 1) continue
            if (!context.isNullOrEmpty() && context !in src) continue
            ctx.smali.write(path, SmaliText.replaceFirst(src, old, new))
            done.add(path)
        }
        checkExpect("swapConst " + old.take(40), done.size, expect)
        return done
    }

    override fun verifyApplied(ctx: PatchContext): List<String> {
        val done = ctx.smali.candidates(ClassFilter(pathRegex = fileRegex, requireAll = listOf(new)))
            .filter { p -> ctx.smali.read(p).let { new in it && (context.isNullOrEmpty() || context in it) } }
        return appliedCount("swapConst " + old.take(40), done, expect)
    }
}

internal class EnumOrdinalsZero(a: Args) : Op("enumOrdinalsZero") {
    private val fileRegex = a.str("fileRegex")
    private val values = a.strs("values")
    private val expect = a.expect("expect")

    init { PyRegex.compile(fileRegex) }

    override fun apply(ctx: PatchContext): List<String> {
        val done = ArrayList<String>()
        for (path in ctx.smali.candidates(ClassFilter(pathRegex = fileRegex))) {
            var src = ctx.smali.read(path)
            var changed = false
            for (v in values) {
                val m = PyRegex.search("""const/4 (v\d), """ + PyRegex.escape(v), src) ?: continue
                if (SmaliText.count(src, m.group()) == 1) {
                    src = SmaliText.replaceFirst(src, m.group(), "const/4 ${m.group(1)}, 0x0")
                    changed = true
                }
            }
            if (changed) {
                ctx.smali.write(path, src)
                done.add(path)
            }
        }
        checkExpect("enumOrdinalsZero", done.size, expect)
        return done
    }

    override fun verifyApplied(ctx: PatchContext): List<String> {
        // Patched form: no listed `const/4 vN, <value>` is left as a lone occurrence.
        val done = ctx.smali.candidates(ClassFilter(pathRegex = fileRegex)).filter { p ->
            val src = ctx.smali.read(p)
            "const/4 v" in src && values.none { v ->
                PyRegex.search("""const/4 (v\d), """ + PyRegex.escape(v), src)
                    ?.let { SmaliText.count(src, it.group()) == 1 } ?: false
            }
        }
        return appliedCount("enumOrdinalsZero", done, expect)
    }
}

private val COMPARE_INVOKES = mapOf(
    "contains" to ("""Ljava/lang/String;->contains\(Ljava/lang/CharSequence;\)Z""" to
        "Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z"),
    "compareToIgnoreCase" to ("""Ljava/lang/String;->compareToIgnoreCase\(Ljava/lang/String;\)I""" to
        "Ljava/lang/String;->compareToIgnoreCase(Ljava/lang/String;)I"),
    "equals" to ("""Ljava/lang/String;->equals\(Ljava/lang/Object;\)Z""" to
        "Ljava/lang/String;->equals(Ljava/lang/Object;)Z"),
)

internal class SweepStringCompareAutoTrue(a: Args) : Op("sweepStringCompareAutoTrue") {
    private val constValue = a.str("constValue")
    private val replaceValue = a.strOrNull("replaceValue")
    private val compare = a.strsOrNull("compare") ?: listOf("contains", "compareToIgnoreCase", "equals")
    private val excludeRegex = a.strsOrNull("excludeRegex") ?: emptyList()
    private val expect = a.expect("expect")
    private val invokes = compare.map { COMPARE_INVOKES[it] ?: throw OpArgError("sweepStringCompareAutoTrue: unknown comparison '$it'") }

    init { excludeRegex.forEach { PyRegex.compile(it) } }

    private val needle = "\"" + constValue + "\""

    override fun apply(ctx: PatchContext): List<String> {
        val done = ArrayList<String>()
        val constRe = PyRegex.compile("""const-string ((?:v|p)\d), """ + "\"" + PyRegex.escape(constValue) + "\"")
        for (path in ctx.smali.candidates(ClassFilter(excludePathRegexes = excludeRegex, requireAll = listOf(needle)))) {
            val src0 = ctx.smali.read(path)
            var src = src0
            data class Edit(val s: Int, val e: Int, val t: String)
            val edits = ArrayList<Edit>()
            val m = constRe.matcher(src0)
            while (m.find()) {
                val reg = m.group(1)
                val tail = src0.substring(m.end(), minOf(src0.length, m.end() + 600))
                for ((invPat, invText) in invokes) {
                    val m2 = PyRegex.search("""invoke-virtual \{((?:v|p)\d), """ + reg + """\}, """ + invPat, tail)
                    if (m2 != null) {
                        edits.add(Edit(m.end() + m2.start(), m.end() + m2.end(), "invoke-virtual {$reg, $reg}, $invText"))
                        if (!replaceValue.isNullOrEmpty() && replaceValue != constValue) {
                            edits.add(Edit(m.start(), m.end(), "const-string $reg, \"$replaceValue\""))
                        }
                        break
                    }
                }
            }
            for (e in edits.sortedWith(compareByDescending<Edit> { it.s }.thenByDescending { it.e }.thenByDescending { it.t })) {
                src = src.substring(0, e.s) + e.t + src.substring(e.e)
            }
            if (src != src0) {
                ctx.smali.write(path, src)
                done.add(path)
            }
        }
        checkExpect("sweepStringCompareAutoTrue $constValue", done.size, expect)
        return done
    }

    override fun verifyApplied(ctx: PatchContext): List<String> {
        val shown = if (!replaceValue.isNullOrEmpty()) replaceValue else constValue
        // After the rebuild the constant may sit past string index 0xFFFF (const-string/jumbo).
        val constRe = PyRegex.compile("""const-string(?:/jumbo)? ((?:v|p)\d+), """ + "\"" + PyRegex.escape(shown) + "\"")
        val done = ctx.smali.candidates(ClassFilter(excludePathRegexes = excludeRegex, requireAll = listOf("\"" + shown + "\""))).filter { p ->
            val src = ctx.smali.read(p)
            val m = constRe.matcher(src)
            var ok = false
            while (m.find() && !ok) {
                val reg = m.group(1)
                val tail = src.substring(m.end(), minOf(src.length, m.end() + 600))
                ok = invokes.any { (_, text) -> "invoke-virtual {$reg, $reg}, $text" in tail }
            }
            ok
        }
        return appliedCount("sweepStringCompareAutoTrue $constValue", done, expect)
    }
}

internal class ForceConstAfterMove(a: Args) : Op("forceConstAfterMove") {
    private val anchor = a.str("anchor")
    private val value = a.str("value")
    private val expect = a.expect("expect")

    override fun apply(ctx: PatchContext): List<String> {
        val done = ArrayList<String>()
        val re = PyRegex.compile("""move-result (v\d)\n""")
        for (path in ctx.smali.candidates(ClassFilter(requireAll = listOf(anchor)))) {
            val src = ctx.smali.read(path)
            if (anchor !in src) continue
            val i = src.indexOf(anchor)
            val seg = src.substring(0, i)
            val m = re.matcher(seg)
            var start = -1
            var end = -1
            var reg = ""
            while (m.find()) { start = m.start(); end = m.end(); reg = m.group(1) }
            if (start < 0) continue
            val insert = "move-result $reg\n\n    const/4 $reg, $value\n"
            ctx.smali.write(path, seg.substring(0, start) + insert + src.substring(end))
            done.add(path)
        }
        checkExpect("forceConstAfterMove", done.size, expect)
        return done
    }

    override fun verifyApplied(ctx: PatchContext): List<String> {
        val done = ctx.smali.candidates(ClassFilter(requireAll = listOf(anchor))).filter { p ->
            val src = ctx.smali.read(p)
            val i = src.indexOf(anchor)
            if (i < 0) return@filter false
            val seg = src.substring(0, i)
            val m = PyRegex.compile("""move-result (v\d)\n""").matcher(seg)
            var last: java.util.regex.MatchResult? = null
            while (m.find()) last = m.toMatchResult()
            last != null && seg.startsWith("\n    const/4 ${last.group(1)}, $value\n", last.end())
        }
        return appliedCount("forceConstAfterMove", done, expect)
    }
}

internal class ReplaceConstStringExact(a: Args) : Op("replaceConstStringExact") {
    private val old = a.str("old")
    private val new = a.str("new")
    private val expect = a.expect("expect")

    override fun apply(ctx: PatchContext): List<String> {
        val done = ArrayList<String>()
        val pat = constPattern(old)
        for (path in ctx.smali.candidates(ClassFilter(requireAll = listOf("\"" + old + "\"")))) {
            val src = ctx.smali.read(path)
            val m = pat.matcher(src)
            if (m.find()) {
                ctx.smali.write(path, SmaliText.sub(pat, src) { "${it.group(1)} ${it.group(2)}, \"$new\"" })
                done.add(path)
            }
        }
        checkExpect("replaceConstStringExact " + old.take(50), done.size, expect)
        return done
    }

    override fun verifyApplied(ctx: PatchContext): List<String> {
        // The rebuilt dex may hold the new string past index 0xFFFF, so it disassembles as
        // const-string/jumbo: both forms count.
        val oldPat = constPattern(old)
        val newPat = constPattern(new)
        val stale = ctx.smali.candidates(ClassFilter(requireAll = listOf("\"" + old + "\"")))
            .filter { oldPat.matcher(ctx.smali.read(it)).find() }
        if (stale.isNotEmpty()) {
            throw PatchFailure("replaceConstStringExact ${old.take(50)}: the original constant is still " +
                "present in ${stale.size} file(s), e.g. ${stale.first()}")
        }
        val done = ctx.smali.candidates(ClassFilter(requireAll = listOf("\"" + new + "\"")))
            .filter { newPat.matcher(ctx.smali.read(it)).find() }
        return appliedCount("replaceConstStringExact " + old.take(50), done, expect)
    }

    /** `const-string` or `const-string/jumbo`, any v#/p# register, value exactly [value]. */
    private fun constPattern(value: String) =
        PyRegex.compile("""(const-string(?:/jumbo)?) ((?:v|p)\d+), "${PyRegex.escape(value)}"""")
}

internal class ReplaceAllInFilesWithAnchor(a: Args) : Op("replaceAllInFilesWithAnchor") {
    private val fileContains = a.str("fileContains")
    private val old = a.str("old")
    private val new = a.str("new")
    private val expect = a.expect("expect")

    override fun apply(ctx: PatchContext): List<String> {
        val done = ArrayList<String>()
        for (path in ctx.smali.candidates(ClassFilter(requireAll = listOf(fileContains, old)))) {
            val src = ctx.smali.read(path)
            if (fileContains !in src || old !in src) continue
            ctx.smali.write(path, src.replace(old, new))
            done.add("$path :: ${old.take(50)}")
        }
        checkExpect("replaceAllInFilesWithAnchor " + old.take(40), done.size, expect)
        return done
    }

    override fun verifyApplied(ctx: PatchContext): List<String> {
        val done = ctx.smali.candidates(ClassFilter(requireAll = listOf(fileContains, new))).filter { p ->
            val src = ctx.smali.read(p)
            fileContains in src && new in src && (new.contains(old) || old !in src)
        }
        return appliedCount("replaceAllInFilesWithAnchor " + old.take(40), done, expect)
    }
}

internal class ForceInvokeResult(a: Args) : Op("forceInvokeResult") {
    private val fileAnchor = a.str("fileAnchor")
    private val invokeRegex = a.str("invokeRegex")
    private val precedingConst = a.strOrNull("precedingConst")
    private val value = a.str("value")
    private val expect = a.expect("expect")
    private val invokeRe = PyRegex.compile("""invoke-[\w]+ \{[^}]+\}, """ + invokeRegex)
    private val moveRe = PyRegex.compile("""\n\s*move-result ((?:v|p)\d+)""")

    override fun apply(ctx: PatchContext): List<String> {
        val done = ArrayList<String>()
        for (path in ctx.smali.candidates(ClassFilter(requireAll = listOf(fileAnchor)))) {
            var src = ctx.smali.read(path)
            if (fileAnchor.isNotEmpty() && fileAnchor !in src) continue
            val m = invokeRe.matcher(src)
            var changed = false
            while (m.find()) {
                if (!precedingConst.isNullOrEmpty()) {
                    val pre = src.substring(maxOf(0, m.start() - 200), m.start())
                    if (precedingConst !in pre.takeLast(120)) continue
                }
                val tail = src.substring(m.end(), minOf(src.length, m.end() + 60))
                val m2 = moveRe.matcher(tail)
                if (!m2.find()) continue
                val reg = m2.group(1)
                val insAt = m.end() + m2.end()
                src = src.substring(0, insAt) + "\n\n    const/4 $reg, $value" + src.substring(insAt)
                changed = true
                done.add("$path :: ${invokeRegex.take(50)} -> $value")
                break
            }
            if (changed) ctx.smali.write(path, src)
        }
        checkExpect("forceInvokeResult", done.size, expect)
        return done
    }

    override fun verifyApplied(ctx: PatchContext): List<String> {
        val done = ArrayList<String>()
        for (path in ctx.smali.candidates(ClassFilter(requireAll = listOf(fileAnchor)))) {
            val src = ctx.smali.read(path)
            if (fileAnchor.isNotEmpty() && fileAnchor !in src) continue
            val m = invokeRe.matcher(src)
            while (m.find()) {
                if (!precedingConst.isNullOrEmpty()) {
                    val pre = src.substring(maxOf(0, m.start() - 200), m.start())
                    if (precedingConst !in pre.takeLast(120)) continue
                }
                val tail = src.substring(m.end(), minOf(src.length, m.end() + 60 + 40))
                val m2 = moveRe.matcher(tail)
                if (!m2.find()) continue
                if (tail.startsWith("\n\n    const/4 ${m2.group(1)}, $value", m2.end())) {
                    done.add("$path :: ${invokeRegex.take(50)} -> $value")
                    break
                }
            }
        }
        return appliedCount("forceInvokeResult", done, expect)
    }
}

// ---------------------------------------------------------------- class-targeted ops

private fun classSource(tree: SmaliTree, cls: String): Pair<String, String> {
    val path = tree.classPath(cls) ?: throw PatchFailure("class $cls: smali not found (did the base change?)")
    return path to tree.read(path)
}

internal class VerifyClassContains(a: Args) : Op("verifyClassContains") {
    private val checks = a.checks("checks")

    override fun apply(ctx: PatchContext): List<String> {
        val done = ArrayList<String>()
        for ((cls, needle) in checks) {
            val (_, src) = classSource(ctx.smali, cls)
            if (needle !in src) throw PatchFailure("$cls: missing '$needle' (a dependent component needs it)")
            done.add("$cls :: ${needle.trim()}")
        }
        return done
    }

    // A precondition about the original build; other patterns may legitimately rewrite these classes.
    override fun verifyApplied(ctx: PatchContext): List<String> =
        checks.map { "${it.first} :: precondition (not re-checked)" }
}

internal class ReplaceInClass(a: Args) : Op("replaceInClass") {
    private val className = a.str("className")
    private val old = a.str("old")
    private val new = a.str("new")
    private val count = a.int("count")

    override fun apply(ctx: PatchContext): List<String> {
        val (path, src) = classSource(ctx.smali, className)
        val found = SmaliText.count(src, old)
        if (found != count) {
            throw PatchFailure("$className: expected $count occurrence(s) of '${old.take(70)}' and found " +
                "$found (did the base change? check the anchors)")
        }
        ctx.smali.write(path, src.replace(old, new))
        return listOf("$className :: ${old.trim().lines().firstOrNull().orEmpty().take(60)} x$count")
    }

    override fun verifyApplied(ctx: PatchContext): List<String> {
        val (_, src) = classSource(ctx.smali, className)
        val cNew = ctx.canon(new)
        val cOld = ctx.canon(old)
        val n = SmaliText.count(src, cNew)
        if (new.isNotEmpty() && n < count) throw PatchFailure("$className: patched text found $n time(s), expected $count")
        if (old.isNotEmpty() && cOld !in cNew && cOld in src) throw PatchFailure("$className: the original text is still present")
        return listOf("$className :: patched text present")
    }
}

private fun classMethodSpan(src: String, cls: String, header: String): IntArray {
    val ml = Pattern.compile("(?m)^\\.method " + PyRegex.escape(header) + "\\n", Pattern.UNIX_LINES).matcher(src)
    var hits = 0
    var start = -1
    while (ml.find()) { hits++; start = ml.start() }
    if (hits != 1) {
        throw PatchFailure("$cls: expected one method '$header' and found $hits (did the base change? check the anchors)")
    }
    return SmaliText.spanFrom(src, start)
}

internal class ReplaceClassMethod(a: Args) : Op("replaceClassMethod") {
    private val className = a.str("className")
    private val header = a.str("header")
    private val body = a.str("body")
    private val mustContain = a.strs("mustContain")

    override fun apply(ctx: PatchContext): List<String> {
        val (path, src) = classSource(ctx.smali, className)
        val span = classMethodSpan(src, className, header)
        val old = src.substring(span[0], span[1])
        for (needle in mustContain) {
            if (needle !in old) {
                throw PatchFailure("$className.$header: old body lacks '$needle' (did the base change? check the anchors)")
            }
        }
        ctx.smali.write(path, src.substring(0, span[0]) + ".method $header\n$body\n.end method" + src.substring(span[1]))
        return listOf("$className :: ${header.take(60)}")
    }

    override fun verifyApplied(ctx: PatchContext): List<String> {
        val (_, src) = classSource(ctx.smali, className)
        val span = classMethodSpan(src, className, header)
        if (SmaliText.dropBlankLines(src.substring(span[0], span[1])) !=
            SmaliText.dropBlankLines(ctx.canon(".method $header\n$body\n.end method"))) {
            throw PatchFailure("$className.$header: the method does not carry the patched body")
        }
        return listOf("$className :: ${header.take(60)}")
    }
}

internal class InsertInClassMethod(a: Args) : Op("insertInClassMethod") {
    private val className = a.str("className")
    private val header = a.str("header")
    private val insert = a.str("insert")
    private val locals = PyRegex.compile("""\n[ ]*\.locals \d+\n""")

    override fun apply(ctx: PatchContext): List<String> {
        val (path, src) = classSource(ctx.smali, className)
        val span = classMethodSpan(src, className, header)
        val body = src.substring(span[0], span[1])
        val m = locals.matcher(body)
        if (!m.find()) throw PatchFailure("$className.$header: no .locals line")
        val newBody = body.substring(0, m.end()) + insert + body.substring(m.end())
        ctx.smali.write(path, src.substring(0, span[0]) + newBody + src.substring(span[1]))
        return listOf("$className :: ${header.take(60)} (+insert)")
    }

    override fun verifyApplied(ctx: PatchContext): List<String> {
        val (_, src) = classSource(ctx.smali, className)
        val span = classMethodSpan(src, className, header)
        val body = src.substring(span[0], span[1])
        val m = locals.matcher(body)
        if (!m.find() || !body.startsWith(ctx.canon(insert), m.end())) {
            throw PatchFailure("$className.$header: the insert does not follow the .locals line")
        }
        return listOf("$className :: ${header.take(60)} (+insert)")
    }
}

internal class RemoveSmaliDir(a: Args) : Op("removeSmaliDir") {
    private val rel = a.str("rel")

    override fun apply(ctx: PatchContext): List<String> {
        val done = ctx.smali.removeDir(rel)
        if (done.isEmpty()) throw PatchFailure("smali dir $rel: not found (did the base change?)")
        return done
    }

    override fun verifyApplied(ctx: PatchContext): List<String> {
        val left = ctx.smali.candidates(ClassFilter(pathRegex = "^smali[^/]*/" + PyRegex.escape(rel) + "/"))
        if (left.isNotEmpty()) throw PatchFailure("smali dir $rel: still present (${left.size} classes)")
        return listOf("$rel absent")
    }
}
