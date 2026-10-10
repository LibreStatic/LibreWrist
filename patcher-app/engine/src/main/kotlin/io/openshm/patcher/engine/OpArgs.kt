package io.openshm.patcher.engine

/** Site-count assertion of an op (`expect`). */
sealed class Expect {
    data class Count(val n: Int) : Expect()
    object AtLeastOne : Expect()
    object Any : Expect()
    object EachListed : Expect()

    override fun toString(): String = when (this) {
        is Count -> n.toString()
        AtLeastOne -> "atLeastOne"
        Any -> "any"
        EachListed -> "eachListed"
    }
}

/** Raises unless [found] sites satisfy [expect] (exact count, "atLeastOne" or "any"). */
internal fun checkExpect(label: String, found: Int, expect: Expect) {
    when (expect) {
        Expect.Any -> return
        Expect.AtLeastOne, Expect.EachListed -> if (found < 1) {
            throw PatchFailure("$label: matched nothing (did the base change? check the anchors)")
        }
        is Expect.Count -> if (found != expect.n) {
            throw PatchFailure("$label: expected ${expect.n} site(s) and found $found " +
                "(did the base change? check the anchors)")
        }
    }
}

/** Minimum number of sites the patched form must show in the applied-mode self-check. */
internal fun requiredSites(expect: Expect): Int = when (expect) {
    is Expect.Count -> expect.n
    Expect.AtLeastOne, Expect.EachListed -> 1
    Expect.Any -> 0
}

/** A manifest element selector (see the manifest select syntax of docs/patches.md). */
class Select(val raw: Map<String, Any?>) {
    val paths: List<String>
    val attr: Map<String, String>
    val attrIn: Map<String, List<String>>
    val simpleNameIn: Map<String, List<String>>
    val parentAttr: Map<String, String>
    val hasChild: List<Pair<String, Map<String, String>>>

    init {
        val extra = raw.keys - SELECT_KEYS
        if (extra.isNotEmpty()) throw OpArgError("select: unknown key(s) ${extra.sorted()}")
        val path = raw["path"]
        val ps = when (path) {
            is String -> listOf(path)
            is List<*> -> path
            else -> null
        }
        if (ps.isNullOrEmpty() || !ps.all { it is String && PATH_RE.matches(it) }) {
            throw OpArgError("select: \"path\" must be a path string or a non-empty list of them")
        }
        paths = ps.map { it as String }
        attr = strMap("attr")
        parentAttr = strMap("parentAttr")
        attrIn = listMap("attrIn")
        simpleNameIn = listMap("simpleNameIn")
        hasChild = raw["hasChild"]?.let { hc ->
            if (hc !is List<*>) throw OpArgError("select.hasChild: must be a list of {\"tag\", \"attr\"} objects")
            hc.map { c ->
                if (c !is Map<*, *> || !(c.keys - setOf("tag", "attr")).isEmpty() || c["tag"] !is String) {
                    throw OpArgError("select.hasChild: must be a list of {\"tag\", \"attr\"} objects")
                }
                val a = c["attr"]?.let { Args.strMap(it, "select.hasChild.attr") } ?: emptyMap()
                a.keys.forEach { attrName(it) }
                (c["tag"] as String) to a
            }
        } ?: emptyList()
    }

    private fun strMap(key: String): Map<String, String> {
        val v = raw[key] ?: return emptyMap()
        val m = Args.strMap(v, "select.$key")
        m.keys.forEach { attrName(it) }
        return m
    }

    private fun listMap(key: String): Map<String, List<String>> {
        val v = raw[key] ?: return emptyMap()
        if (v !is Map<*, *> || !v.values.all { x -> x is List<*> && x.all { it is String } }) {
            throw OpArgError("select.$key: must map attribute names to lists of strings")
        }
        val out = LinkedHashMap<String, List<String>>()
        for ((k, x) in v) {
            attrName(k as String)
            @Suppress("UNCHECKED_CAST")
            out[k] = x as List<String>
        }
        return out
    }

    /** Short text for logs and error labels. */
    fun describe(): String = raw.entries.joinToString(" ") { "${it.key}=${it.value}" }.take(90)

    companion object {
        private val SELECT_KEYS = setOf("path", "attr", "attrIn", "simpleNameIn", "parentAttr", "hasChild")
        private val PATH_RE = Regex("""^(\.|\*\*|[\w.-]+(/[\w.-]+)*)$""")
    }
}

/** Only the `android:` prefix is supported; returns the name unchanged when valid. */
internal fun attrName(name: String): String {
    if (':' in name && !name.startsWith("android:")) {
        throw OpArgError("attribute '$name': only the android: prefix is supported")
    }
    return name
}

/** A `manifestAddChild` element template. */
class ChildSpec(val tag: String, val attrs: List<Pair<String, String>>, val children: List<ChildSpec>)

internal enum class T { STR, TEXT, INT, STRS, STRMAP, EXPECT, MEXPECT, SELECT, CHILDREN, CHECKS, ENUMFIELDS }

internal class P(val json: String, val type: T, val required: Boolean, val default: Any? = null)

internal fun req(json: String, type: T) = P(json, type, true)
internal fun opt(json: String, type: T, default: Any? = null) = P(json, type, false, default)

/** Validated, typed arguments of one op step. */
internal class Args(private val values: Map<String, Any?>) {
    fun str(k: String) = values[k] as String
    fun strOrNull(k: String) = values[k] as String?
    fun int(k: String) = values[k] as Int
    fun strs(k: String): List<String> = strsOrNull(k) ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    fun strsOrNull(k: String) = values[k] as List<String>?

    @Suppress("UNCHECKED_CAST")
    fun strMap(k: String) = values[k] as Map<String, String>?
    fun expect(k: String) = values[k] as Expect
    fun select(k: String) = values[k] as Select

    @Suppress("UNCHECKED_CAST")
    fun children(k: String) = values[k] as List<ChildSpec>?

    @Suppress("UNCHECKED_CAST")
    fun checks(k: String) = values[k] as List<Pair<String, String>>

    @Suppress("UNCHECKED_CAST")
    fun enumFields(k: String) = values[k] as Map<String, Pair<String, String>>?

    companion object {
        fun strMap(v: Any?, what: String): Map<String, String> {
            if (v !is Map<*, *> || !v.all { (k, x) -> k is String && x is String }) {
                throw OpArgError("$what: must be an object of strings")
            }
            @Suppress("UNCHECKED_CAST")
            return v as Map<String, String>
        }

        private fun isInt(v: Any?) = v is Long && v >= 0 && v <= Int.MAX_VALUE

        private fun children(v: Any?, what: String): List<ChildSpec> {
            if (v !is List<*>) throw OpArgError("$what: must be a list")
            return v.map { c ->
                if (c !is Map<*, *> || (c.keys - setOf("tag", "attrs", "children")).isNotEmpty() || c["tag"] !is String) {
                    throw OpArgError("$what: entries are {\"tag\", \"attrs\", \"children\"} objects")
                }
                val attrs = c["attrs"]?.let { strMap(it, "$what.attrs") } ?: emptyMap()
                attrs.keys.forEach { attrName(it) }
                val kids = c["children"]?.let { children(it, "$what.children") } ?: emptyList()
                ChildSpec(c["tag"] as String, attrs.toList(), kids)
            }
        }

        /** Checks [value] against the type tag [t] (the equivalent of `_normalise`). */
        fun normalise(t: T, value: Any?, what: String): Any? {
            val bad = { OpArgError("$what: expected ${t.name.lowercase()}, got $value".take(200)) }
            return when (t) {
                T.STR -> value as? String ?: throw bad()
                T.TEXT -> when {
                    value is String -> value
                    value is List<*> && value.all { it is String } -> value.joinToString("\n")
                    else -> throw bad()
                }
                T.INT -> if (isInt(value)) (value as Long).toInt() else throw bad()
                T.STRS -> if (value is List<*> && value.all { it is String }) value else throw bad()
                T.STRMAP -> strMap(value, what)
                T.EXPECT -> when {
                    isInt(value) -> Expect.Count((value as Long).toInt())
                    value == "atLeastOne" -> Expect.AtLeastOne
                    value == "any" -> Expect.Any
                    else -> throw bad()
                }
                T.MEXPECT -> when {
                    isInt(value) -> Expect.Count((value as Long).toInt())
                    value == "atLeastOne" -> Expect.AtLeastOne
                    value == "eachListed" -> Expect.EachListed
                    else -> throw bad()
                }
                T.SELECT -> {
                    if (value !is Map<*, *>) throw OpArgError("$what: must be an object")
                    @Suppress("UNCHECKED_CAST")
                    Select(value as Map<String, Any?>)
                }
                T.CHILDREN -> children(value, what)
                T.CHECKS -> {
                    if (value is List<*> && value.isNotEmpty() && value.all {
                            it is Map<*, *> && it.keys == setOf("className", "contains") &&
                                it.values.all { x -> x is String }
                        }) {
                        value.map { (it as Map<*, *>)["className"] as String to it["contains"] as String }
                    } else throw bad()
                }
                T.ENUMFIELDS -> {
                    if (value is Map<*, *> && value.values.all {
                            it is Map<*, *> && it.keys == setOf("enumType", "constant") &&
                                it.values.all { x -> x is String }
                        }) {
                        value.entries.associate { (k, s) ->
                            (k as String) to ((s as Map<*, *>)["enumType"] as String to s["constant"] as String)
                        }
                    } else throw bad()
                }
            }
        }
    }
}
