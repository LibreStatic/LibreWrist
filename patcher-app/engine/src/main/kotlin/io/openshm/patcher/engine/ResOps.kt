package io.openshm.patcher.engine

import com.reandroid.arsc.chunk.xml.ResXmlAttribute
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.ValueType
import java.io.ByteArrayInputStream

/**
 * `renameFile` gives a resource file the extension of its real content (`res/<type dir>/<stem>.<ext>`
 * -> `<stem>.<new ext>`). It exists for the Python pipeline only: aapt2 refuses e.g. WebP bytes
 * stored as `.png`, so `apktool b` needs the file renamed in its tree. This engine never recompiles
 * resources (the stored files and resources.arsc are copied as they are, and Android decodes images
 * by content), so the op checks its arguments and leaves the APK untouched. Loading the table to
 * rename the entries would not fit a phone's heap either: Samsung Health's resources.arsc is 175 MB.
 */
internal class RenameFile(a: Args) : Op("renameFile") {
    private val path = a.str("path")
    private val newName = a.str("name")

    init {
        val parts = path.split('/')
        if (parts.size != 3 || parts[0] != "res" || '.' !in parts[2]) throw OpArgError("renameFile: path must be res/<type dir>/<name>.<ext>, got '$path'")
        val stem = parts[2].substringBeforeLast('.')
        if ('/' in newName || newName.substringBeforeLast('.', "") != stem || newName.endsWith('.')) {
            throw OpArgError("renameFile: name must be $stem.<ext> (only the extension changes), got '$newName'")
        }
    }

    private fun log() = listOf("$path :: kept as stored (no resource recompilation here; the Python pipeline renames it to $newName)")

    override fun apply(ctx: PatchContext): List<String> = log()

    override fun verifyApplied(ctx: PatchContext): List<String> = log()
}

/**
 * `resXmlReplaceString` rewrites string attribute values that are exactly `old` in the compiled
 * XML resources under `res/` (never AndroidManifest.xml). The files are found by content, not by
 * name: a real APK obfuscates the paths (`res/Ab.xml`, even `res/-0_.png`), and resolving names
 * through resources.arsc is out of the question (Samsung Health's table is 175 MB). An entry is a
 * candidate if its chunk header says binary XML and its bytes contain `old` as UTF-8 or UTF-16LE
 * (the two string-pool encodings); only then is it parsed. A matching attribute gets a new pool
 * string, so a pool string shared with other attributes or with a name stays as it was. Changed
 * entries go to [PatchContext.replacedEntries]; `expect` counts changed files.
 */
internal class ResXmlReplaceString(a: Args) : Op("resXmlReplaceString") {
    private val old = a.str("old")
    private val new = a.str("new")
    private val expect = a.expect("expect")

    init {
        if (old.isEmpty()) throw OpArgError("resXmlReplaceString: old must not be empty")
    }

    override fun apply(ctx: PatchContext): List<String> {
        val done = ArrayList<String>()
        for (name in ctx.files.names) {
            if (!isResEntry(name)) continue
            val replaced = ctx.replacedEntries[name]
            val bytes = replaced ?: candidateBytes(ctx.files, name, old) ?: continue
            val doc = parse(bytes)
            var n = 0
            for (attr in doc.recursiveAttributes().asSequence().toList()) {
                if (attr.valueType == ValueType.STRING && attr.valueAsString == old) {
                    attr.setValueAsString(new)
                    n++
                }
            }
            if (n == 0) continue
            doc.refreshFull()
            ctx.replacedEntries[name] = doc.bytes
            done.add("$name :: ${old.take(50)}")
        }
        checkExpect("resXmlReplaceString " + old.take(40), done.size, expect)
        return done
    }

    override fun verifyApplied(ctx: PatchContext): List<String> {
        val done = ArrayList<String>()
        for (name in ctx.files.names) {
            if (!isResEntry(name)) continue
            val bytes = ctx.replacedEntries[name] ?: (candidateBytes(ctx.files, name, old, new) ?: continue)
            val values = parse(bytes).recursiveAttributes().asSequence()
                .filter { it.valueType == ValueType.STRING }.map { it.valueAsString }.toList()
            if (old != new && !new.contains(old) && old in values) {
                throw PatchFailure("resXmlReplaceString ${old.take(40)}: $name still has the original value (the APK does not carry this patch)")
            }
            if (new in values) done.add("$name :: ${new.take(50)}")
        }
        return appliedCount("resXmlReplaceString " + old.take(40), done, expect)
    }

    private fun parse(bytes: ByteArray): ResXmlDocument =
        ResXmlDocument().also { it.readBytes(ByteArrayInputStream(bytes)) }

    internal companion object {
        fun isResEntry(name: String) = name.startsWith("res/") && !name.endsWith("/")

        /** The entry's bytes if it is binary XML containing one of [needles] (UTF-8 or UTF-16LE), else null. */
        fun candidateBytes(files: ApkFiles, name: String, vararg needles: String): ByteArray? {
            val head = files.head(name, 4)
            // RES_XML_TYPE (0x0003), header size 8
            if (head.size < 4 || head[0].toInt() != 3 || head[1].toInt() != 0 || head[2].toInt() != 8 || head[3].toInt() != 0) return null
            val bytes = files.read(name)
            for (n in needles) {
                if (indexOf(bytes, n.toByteArray(Charsets.UTF_8)) >= 0 || indexOf(bytes, n.toByteArray(Charsets.UTF_16LE)) >= 0) return bytes
            }
            return null
        }

        fun indexOf(hay: ByteArray, needle: ByteArray): Int {
            if (needle.isEmpty() || needle.size > hay.size) return -1
            val first = needle[0]
            var i = 0
            val last = hay.size - needle.size
            while (i <= last) {
                if (hay[i] == first) {
                    var j = 1
                    while (j < needle.size && hay[i + j] == needle[j]) j++
                    if (j == needle.size) return i
                }
                i++
            }
            return -1
        }
    }
}

/**
 * `adaptiveIconBadge` draws a small vector badge over layers of the adaptive launcher icons (see
 * the Python op of the same name). The files are found by content like [ResXmlReplaceString] does:
 * every binary XML under `res/` whose root element is `adaptive-icon`; resources.arsc is not
 * loaded. A layer named in `layers` becomes a `<layer-list>` of two `<item>`s, the original
 * drawable and a `<vector>`. Values get the types aapt2 gives them (dimension `108dp`, float
 * viewport and stroke width, `#AARRGGBB` colours, enums as decimal ints), so the result equals
 * what the Python pipeline compiles.
 */
internal class AdaptiveIconBadge(a: Args) : Op("adaptiveIconBadge") {
    private val layers = a.layers("layers")
    private val expect = a.expect("expect")

    override fun apply(ctx: PatchContext): List<String> {
        val done = ArrayList<String>()
        for (name in ctx.files.names) {
            if (!ResXmlReplaceString.isResEntry(name)) continue
            val bytes = ctx.replacedEntries[name] ?: ResXmlReplaceString.candidateBytes(ctx.files, name, ROOT) ?: continue
            val doc = parse(bytes)
            val root = doc.documentElement
            if (root == null || root.name != ROOT) continue
            val changed = ArrayList<String>()
            for ((layerName, specs) in layers) {
                val layer = child(root, layerName) ?: continue
                badge(layerName, layer, specs)
                changed.add(layerName)
            }
            if (changed.isEmpty()) continue
            doc.refreshFull()
            ctx.replacedEntries[name] = doc.bytes
            done.add("$name :: ${changed.joinToString(",")}")
        }
        checkExpect("adaptiveIconBadge", done.size, expect)
        return done
    }

    override fun verifyApplied(ctx: PatchContext): List<String> {
        val done = ArrayList<String>()
        for (name in ctx.files.names) {
            if (!ResXmlReplaceString.isResEntry(name)) continue
            val bytes = ctx.replacedEntries[name] ?: ResXmlReplaceString.candidateBytes(ctx.files, name, ROOT) ?: continue
            val root = parse(bytes).documentElement
            if (root == null || root.name != ROOT) continue
            val present = layers.filter { child(root, it.key) != null }
            if (present.isEmpty()) continue
            for ((layerName, specs) in present) {
                if (badgeOf(child(root, layerName)!!) != specs.first().pathData) {
                    throw PatchFailure("adaptiveIconBadge: $name has no badge on its $layerName layer (the APK does not carry this patch)")
                }
            }
            done.add("$name :: ${present.keys.joinToString(",")}")
        }
        return appliedCount("adaptiveIconBadge", done, expect)
    }

    private fun parse(bytes: ByteArray): ResXmlDocument =
        ResXmlDocument().also { it.readBytes(ByteArrayInputStream(bytes)) }

    private fun children(e: ResXmlElement, tag: String): List<ResXmlElement> {
        val out = ArrayList<ResXmlElement>()
        val it = e.elements
        while (it.hasNext()) {
            val c = it.next()
            if (c.name == tag) out.add(c)
        }
        return out
    }

    private fun child(e: ResXmlElement, tag: String): ResXmlElement? = children(e, tag).firstOrNull()

    private fun allChildren(e: ResXmlElement): List<ResXmlElement> {
        val out = ArrayList<ResXmlElement>()
        val it = e.elements
        while (it.hasNext()) out.add(it.next())
        return out
    }

    /** pathData of the first path of the vector in the last item of the layer's `<layer-list>`, if any. */
    private fun badgeOf(layer: ResXmlElement): String? {
        val list = child(layer, "layer-list") ?: return null
        val item = children(list, "item").lastOrNull() ?: return null
        val vector = child(item, "vector") ?: return null
        val path = child(vector, "path") ?: return null
        return path.searchAttributeByResourceId(PATH_DATA)?.valueAsString
    }

    private fun badge(layerName: String, layer: ResXmlElement, specs: List<BadgePath>) {
        val label = "adaptiveIconBadge $layerName"
        if (badgeOf(layer) == specs.first().pathData) throw PatchFailure("$label: the layer already carries this badge")
        val drawable = layer.searchAttributeByResourceId(DRAWABLE)
        val kids = allChildren(layer)
        if (drawable != null && kids.isNotEmpty()) throw PatchFailure("$label: the layer has both a drawable attribute and a child")
        if (drawable == null && kids.size != 1) {
            throw PatchFailure("$label: the layer must have an android:drawable attribute or exactly one child element")
        }
        val list = layer.newElement("layer-list")
        val item1 = list.newElement("item")
        if (drawable != null) {
            copyAttribute(drawable, item1)
            layer.removeAttribute(drawable)
        } else {
            copyElement(kids[0], item1)
            kids[0].removeSelf()
        }
        val item2 = list.newElement("item")
        val vector = item2.newElement("vector")
        android(vector, "width", WIDTH).also { dimension(it, 108) }
        android(vector, "height", HEIGHT).also { dimension(it, 108) }
        android(vector, "viewportWidth", VIEWPORT_WIDTH).also { float(it, 108f) }
        android(vector, "viewportHeight", VIEWPORT_HEIGHT).also { float(it, 108f) }
        for (spec in specs) {
            val path = vector.newElement("path")
            for ((k, v) in spec.entries) {
                when (k) {
                    "pathData" -> android(path, k, PATH_DATA).setValueAsString(v as String)
                    "fillColor" -> color(android(path, k, FILL_COLOR), v as String)
                    "strokeColor" -> color(android(path, k, STROKE_COLOR), v as String)
                    "strokeWidth" -> float(android(path, k, STROKE_WIDTH), (v as Number).toFloat())
                    "strokeLineCap" -> enumInt(android(path, k, STROKE_LINE_CAP), CAPS.indexOf(v as String))
                    "strokeLineJoin" -> enumInt(android(path, k, STROKE_LINE_JOIN), JOINS.indexOf(v as String))
                    else -> throw PatchFailure("$label: unsupported path attribute $k")
                }
            }
        }
    }

    private fun android(e: ResXmlElement, name: String, id: Int): ResXmlAttribute = e.createAndroidAttribute(name, id)

    private fun dimension(a: ResXmlAttribute, dp: Int) {
        // aapt2: unit dp (1), radix 23p0 (0), the integer in the mantissa
        a.valueType = ValueType.DIMENSION
        a.data = (dp shl 8) or 1
    }

    private fun float(a: ResXmlAttribute, v: Float) {
        a.valueType = ValueType.FLOAT
        a.data = java.lang.Float.floatToIntBits(v)
    }

    private fun color(a: ResXmlAttribute, argb: String) {
        a.valueType = ValueType.COLOR_ARGB8
        a.data = argb.substring(1).toLong(16).toInt()
    }

    private fun enumInt(a: ResXmlAttribute, v: Int) {
        a.valueType = ValueType.DEC
        a.data = v
    }

    private fun copyAttribute(src: ResXmlAttribute, dst: ResXmlElement) {
        val uri = src.uri
        val id = src.nameId
        val made = when {
            uri == ANDROID && id != 0 -> dst.createAndroidAttribute(src.name, id)
            uri.isNullOrEmpty() -> dst.createAttribute(src.name, id)
            else -> throw PatchFailure("adaptiveIconBadge: unsupported attribute namespace on ${src.name}")
        }
        if (src.valueType == ValueType.STRING) {
            made.setValueAsString(src.valueAsString)
        } else {
            made.valueType = src.valueType
            made.data = src.data
        }
    }

    /** Copies [src] (attributes and element children) as a new last child of [into]. */
    private fun copyElement(src: ResXmlElement, into: ResXmlElement) {
        if (src.textsCount > 0) throw PatchFailure("adaptiveIconBadge: <${src.name}> has text content")
        val copy = into.newElement(src.name)
        val attrs = src.attributes
        while (attrs.hasNext()) copyAttribute(attrs.next(), copy)
        for (c in allChildren(src)) copyElement(c, copy)
    }

    private companion object {
        const val ROOT = "adaptive-icon"
        const val ANDROID = "http://schemas.android.com/apk/res/android"

        // public framework attr ids (framework-ids.txt, from the SDK jar)
        val DRAWABLE = FrameworkIds.attr("drawable")!!
        val WIDTH = FrameworkIds.attr("width")!!
        val HEIGHT = FrameworkIds.attr("height")!!
        val VIEWPORT_WIDTH = FrameworkIds.attr("viewportWidth")!!
        val VIEWPORT_HEIGHT = FrameworkIds.attr("viewportHeight")!!
        val PATH_DATA = FrameworkIds.attr("pathData")!!
        val FILL_COLOR = FrameworkIds.attr("fillColor")!!
        val STROKE_COLOR = FrameworkIds.attr("strokeColor")!!
        val STROKE_WIDTH = FrameworkIds.attr("strokeWidth")!!
        val STROKE_LINE_CAP = FrameworkIds.attr("strokeLineCap")!!
        val STROKE_LINE_JOIN = FrameworkIds.attr("strokeLineJoin")!!

        val CAPS = listOf("butt", "round", "square")
        val JOINS = listOf("miter", "round", "bevel")
    }
}
