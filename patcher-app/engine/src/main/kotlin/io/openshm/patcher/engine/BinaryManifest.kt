package io.openshm.patcher.engine

import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlAttribute
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.ValueType
import java.io.ByteArrayInputStream

/**
 * The binary `AndroidManifest.xml` of an APK as a [ManifestDocument] (ARSCLib).
 *
 * Values are read the way apktool prints them (`@string/app_name`, `true`, ...) and written with
 * the type aapt would give them. Only the types the patcher needs are written: strings, booleans,
 * resource references (app resources through [table], framework styles through [FrameworkIds]) and
 * the `protectionLevel` flags. Anything else fails instead of guessing a type.
 *
 * Attribute order is not managed here: ARSCLib sorts attributes by resource id when it writes
 * the document (the order aapt2 uses).
 *
 * [table] is only asked for when a reference has to be read or written.
 */
class BinaryManifest(bytes: ByteArray, private val table: () -> TableBlock?) : ManifestDocument {
    private val block: AndroidManifestBlock = AndroidManifestBlock.load(ByteArrayInputStream(bytes))

    override val root: ManifestElement = Element(block.manifestElement)

    /** `minSdkVersion` of `<uses-sdk>`, if declared. */
    val minSdk: Int? get() = block.minSdkVersion

    val packageName: String? get() = block.packageName

    /** True once an op changed the document; an untouched manifest is not rewritten. */
    var modified = false
        private set

    fun toBytes(): ByteArray {
        block.refresh()
        return block.bytes
    }

    private inner class Element(val node: ResXmlElement) : ManifestElement {
        override val tag: String get() = node.name

        override val parent: ManifestElement?
            get() = node.parentElement?.let { Element(it) }

        override val children: List<ManifestElement>
            get() {
                val out = ArrayList<ManifestElement>()
                val it = node.elements
                while (it.hasNext()) out.add(Element(it.next()))
                return out
            }

        override val attributes: List<Pair<String, String>>
            get() {
                val out = ArrayList<Pair<String, String>>()
                val it = node.attributes
                while (it.hasNext()) {
                    val a = it.next()
                    val n = nameOf(a) ?: continue
                    out.add(n to decode(a, n))
                }
                return out
            }

        override fun attr(name: String): String? = find(name)?.let { decode(it, name) }

        private fun find(name: String): ResXmlAttribute? {
            val it = node.attributes
            while (it.hasNext()) {
                val a = it.next()
                if (nameOf(a) == name) return a
            }
            return null
        }

        override fun setAttr(name: String, value: String) {
            modified = true
            val existing = find(name)
            val a = existing ?: create(name)
            try {
                encode(a, name, value, existing?.valueType)
            } catch (e: PatchFailure) {
                if (existing == null) a.removeSelf()
                throw e
            }
        }

        private fun create(name: String): ResXmlAttribute {
            if (name.startsWith("android:")) {
                val short = name.substring(8)
                val id = FrameworkIds.attr(short)
                    ?: throw PatchFailure("manifest: unknown framework attribute $name")
                return node.createAndroidAttribute(short, id)
            }
            if (':' in name) throw PatchFailure("manifest: unsupported attribute namespace in $name")
            return node.createAttribute(name, 0)
        }

        override fun removeAttr(name: String): Boolean {
            modified = true
            val a = find(name) ?: return false
            return node.removeAttribute(a)
        }

        override fun remove() {
            modified = true
            if (!node.removeSelf()) throw PatchFailure("manifest: cannot remove <${node.name}>")
        }

        override fun addChild(tag: String, attrs: List<Pair<String, String>>, index: Int): ManifestElement {
            modified = true
            val child = if (index < 0) node.newElement(tag) else node.newElementAt(index, tag)
            val wrapped = Element(child)
            try {
                for ((k, v) in attrs) wrapped.setAttr(k, v)
            } catch (e: PatchFailure) {
                child.removeSelf()
                throw e
            }
            return wrapped
        }

        override fun equals(other: Any?) = other is Element && other.node === node
        override fun hashCode() = System.identityHashCode(node)
        override fun toString() = "<${node.name}>"
    }

    // ------------------------------------------------------------------ values

    private fun nameOf(a: ResXmlAttribute): String? {
        val uri = a.uri
        return when {
            uri.isNullOrEmpty() -> a.name
            uri == ANDROID -> "android:" + a.name
            else -> null
        }
    }

    private fun decode(a: ResXmlAttribute, name: String): String {
        val data = a.data
        return when (a.valueType) {
            ValueType.HEX, ValueType.DEC -> if (name == "android:protectionLevel") protectionName(data) else numeric(a.valueType, data)
            ValueType.STRING -> a.valueString ?: ""
            ValueType.BOOLEAN -> if (data != 0) "true" else "false"
            ValueType.REFERENCE -> referenceName(data)
            else -> "#${a.valueType}:0x${Integer.toHexString(data)}" // opaque; never equals a catalogue value
        }
    }

    private fun numeric(type: ValueType, data: Int) = if (type == ValueType.DEC) data.toString() else "0x" + Integer.toHexString(data)

    /** `signature|privileged` for a protectionLevel value; hex if it has bits we have no name for. */
    private fun protectionName(data: Int): String {
        val base = BASE_LEVELS.getOrNull(data and 0xF) ?: return "0x" + Integer.toHexString(data)
        var flags = data and 0xF.inv()
        val names = ArrayList<String>()
        names.add(base)
        for ((n, bit) in FLAG_LEVELS) if (flags and bit != 0) { names.add(n); flags = flags and bit.inv() }
        return if (flags == 0) names.joinToString("|") else "0x" + Integer.toHexString(data)
    }

    private fun referenceName(id: Int): String {
        if (id == 0) return "@null"
        if ((id ushr 24) == 1) {
            val n = FrameworkIds.nameOf(id) ?: return "@0x" + Integer.toHexString(id)
            return "@android:$n"
        }
        val entry = table()?.getResource(id)
        if (entry != null && entry.name != null) return "@${entry.type}/${entry.name}"
        return "@0x" + Integer.toHexString(id)
    }

    private fun encode(a: ResXmlAttribute, name: String, value: String, existing: ValueType?) {
        when {
            value.startsWith("@") -> {
                a.valueType = ValueType.REFERENCE
                a.data = resolveReference(value)
            }
            (value == "true" || value == "false") && (existing == ValueType.BOOLEAN || name in BOOLEAN_ATTRS) -> {
                a.setValueAsBoolean(value == "true")
            }
            name == "android:protectionLevel" -> {
                a.valueType = ValueType.HEX
                a.data = protectionLevel(value)
            }
            existing == ValueType.STRING || name in STRING_ATTRS || !name.startsWith("android:") -> {
                a.setValueAsString(value)
            }
            else -> throw PatchFailure(
                "manifest: don't know how to encode '$value' for $name" +
                    (existing?.let { " (current type $it)" } ?: ""),
            )
        }
    }

    private fun resolveReference(ref: String): Int {
        val m = Regex("""^@(?:(android):)?(\w+)/([\w.]+)$""").matchEntire(ref)
            ?: throw PatchFailure("manifest: unsupported reference '$ref'")
        val (framework, type, name) = m.destructured
        if (framework.isNotEmpty()) {
            return FrameworkIds.resource(type, name)
                ?: throw PatchFailure("manifest: unknown framework resource '$ref'")
        }
        val t = table() ?: throw PatchFailure("manifest: no resource table to resolve '$ref'")
        val entry = t.getLocalResource(type, name)
            ?: throw PatchFailure("manifest: resource '$ref' does not exist")
        return entry.resourceId
    }

    private fun protectionLevel(value: String): Int {
        var out = 0
        for (token in value.split('|')) {
            out = out or (PROTECTION[token.trim()]
                ?: throw PatchFailure("manifest: unknown protectionLevel '$token'"))
        }
        return out
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"

        val BOOLEAN_ATTRS = setOf(
            "android:exported", "android:required", "android:enabled", "android:excludeFromRecents",
            "android:allowBackup", "android:supportsRtl", "android:extractNativeLibs",
            "android:directBootAware", "android:persistent", "android:debuggable",
        )

        // Attributes whose format is a plain string (or accepts one).
        val STRING_ATTRS = setOf(
            "android:name", "android:value", "android:authorities", "android:permission",
            "android:readPermission", "android:writePermission", "android:label", "android:process",
            "android:taskAffinity", "android:targetActivity", "android:host", "android:scheme",
            "android:mimeType", "android:path", "android:pathPrefix",
        )

        val BASE_LEVELS = listOf("normal", "dangerous", "signature", "signatureOrSystem")
        val FLAG_LEVELS = listOf(
            "privileged" to 0x10, "development" to 0x20, "appop" to 0x40, "pre23" to 0x80, "installer" to 0x100,
            "verifier" to 0x200, "preinstalled" to 0x400, "setup" to 0x800, "instant" to 0x1000, "runtime" to 0x2000,
            "oem" to 0x4000,
        )

        val PROTECTION = mapOf(
            "normal" to 0, "dangerous" to 1, "signature" to 2, "signatureOrSystem" to 3,
            "privileged" to 0x10, "system" to 0x10, "development" to 0x20, "appop" to 0x40,
            "pre23" to 0x80, "installer" to 0x100, "verifier" to 0x200, "preinstalled" to 0x400,
            "setup" to 0x800, "instant" to 0x1000, "runtime" to 0x2000, "oem" to 0x4000,
        )
    }
}
