package io.openshm.patcher.engine

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource

internal const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

/** JSON text -> parsed value, to write op arguments the way the catalogue does. */
@Suppress("UNCHECKED_CAST")
internal fun j(text: String): Map<String, Any?> = Json.parse(text) as Map<String, Any?>

/** [ManifestElement] over a JDK DOM, for device-free op tests. */
internal class DomElement(val node: Element) : ManifestElement {
    override fun equals(other: Any?) = other is DomElement && other.node === node
    override fun hashCode() = System.identityHashCode(node)

    override val tag: String get() = node.tagName
    override val parent: ManifestElement?
        get() = (node.parentNode as? Element)?.let { DomElement(it) }
    override val children: List<ManifestElement>
        get() = (0 until node.childNodes.length).map { node.childNodes.item(it) }
            .filterIsInstance<Element>().map { DomElement(it) }
    override val attributes: List<Pair<String, String>>
        get() = (0 until node.attributes.length).map { node.attributes.item(it) }
            .filter { it.namespaceURI != "http://www.w3.org/2000/xmlns/" }
            .map { (if (it.namespaceURI == ANDROID_NS) "android:" + it.localName else it.nodeName) to it.nodeValue }

    override fun attr(name: String): String? =
        if (name.startsWith("android:")) {
            node.getAttributeNodeNS(ANDROID_NS, name.substring(8))?.value
        } else {
            node.getAttributeNode(name)?.value
        }

    override fun setAttr(name: String, value: String) {
        if (name.startsWith("android:")) node.setAttributeNS(ANDROID_NS, name, value) else node.setAttribute(name, value)
    }

    override fun removeAttr(name: String): Boolean {
        val had = attr(name) != null
        if (name.startsWith("android:")) node.removeAttributeNS(ANDROID_NS, name.substring(8)) else node.removeAttribute(name)
        return had
    }

    override fun remove() { node.parentNode.removeChild(node) }

    override fun addChild(tag: String, attrs: List<Pair<String, String>>, index: Int): ManifestElement {
        val e = node.ownerDocument.createElement(tag)
        val child = DomElement(e)
        attrs.forEach { (k, v) -> child.setAttr(k, v) }
        val kids = children
        if (index < 0 || index >= kids.size) node.appendChild(e) else node.insertBefore(e, (kids[index] as DomElement).node)
        return child
    }
}

internal class DomManifest(xml: String) : ManifestDocument {
    val document: Document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(InputSource(StringReader(xml)))
    override val root: ManifestElement = DomElement(document.documentElement)

    fun all(tag: String): List<ManifestElement> {
        val out = ArrayList<ManifestElement>()
        fun walk(e: ManifestElement) { if (e.tag == tag) out.add(e); e.children.forEach { walk(it) } }
        walk(root)
        return out
    }

    fun names(tag: String): List<String?> = all(tag).map { it.attr("android:name") }

    /** Serialised text of every element/attribute, enough for `assertTrue("x" in text)` checks. */
    fun text(): String {
        val sb = StringBuilder()
        fun walk(e: ManifestElement) {
            sb.append('<').append(e.tag)
            e.attributes.forEach { (k, v) -> sb.append(' ').append(k).append("=\"").append(v).append('"') }
            sb.append(">\n")
            e.children.forEach { walk(it) }
        }
        walk(root)
        return sb.toString()
    }
}

internal class MapResources(files: Map<String, MutableMap<String, String>>) : ResourceTable {
    val files = files.toMutableMap()
    override fun hasString(name: String) = files.values.any { name in it }
    override fun hasStringsFile(qualifier: String) = qualifier in files
    override fun addString(qualifier: String, name: String, text: String) { files.getValue(qualifier)[name] = text }
    override fun string(qualifier: String, name: String) = files[qualifier]?.get(name)
    override fun entryAt(id: Int): String? = null
    override fun hasEntry(type: String, name: String) = false
    override fun defineNullEntry(type: String, name: String, id: Int) = throw UnsupportedOperationException()
}

internal fun op(name: String, argsJson: String): Op = OpRegistry.prepare(name, j(argsJson))

internal class Fixture(
    val smali: MemorySmaliTree = MemorySmaliTree(),
    var manifest: DomManifest? = null,
    var resources: MapResources? = null,
) {
    val ctx get() = PatchContext(smali, { manifest ?: error("no manifest") }, { resources ?: error("no resources") })
    fun apply(name: String, argsJson: String): List<String> = op(name, argsJson).apply(ctx)
    fun verify(name: String, argsJson: String): List<String> = op(name, argsJson).verifyApplied(ctx)
}

/** The schema-2 top-level keys (apps, strings, platform) for a synthetic catalogue with the given output ids. */
internal fun schema2Tail(vararg outputIds: String): String {
    val outs = outputIds.joinToString(", ") { "\"$it\"" }
    val labels = outputIds.joinToString(", ") { "\"output.$it.label\": \"$it\"" }
    return """, "apps": [{"id": "syn", "outputs": [$outs], "optional": false, "icon": "swap"}],
  "strings": {"en": {"app.syn.title": "Syn", "app.syn.summary": "Synthetic", $labels}},
  "platform": {"companionApp": {"package": "com.example.companion", "watchPlugins": []},
    "healthPlatform": {"package": "com.example.health", "settingsAction": "com.example.health.SETTINGS",
      "settingsComponent": "com.example.health/.Main", "titleViewId": "app:id/title", "devModeViewId": "app:id/sub",
      "taps": 10, "requiredBy": []},
    "logPackages": {"phone": [], "watch": []}, "revertSettings": []}"""
}
