package io.openshm.patcher.engine

/**
 * The binary-XML side of the ops, expressed on a tiny DOM so the ops are testable without an APK.
 * [BinaryManifest] implements it on ARSCLib; tests use an in-memory XML implementation.
 *
 * Attribute names are `android:<name>` (the android namespace) or a plain name (no namespace);
 * attributes in other namespaces are invisible. Values are the decoded text apktool would print
 * (`@string/app_name`, `true`, `com.example.Foo`, ...).
 *
 * Implementations must give element wrappers identity semantics (`equals` / `hashCode` of the
 * underlying node), because the ops de-duplicate and compare elements.
 */
interface ManifestElement {
    val tag: String
    val parent: ManifestElement?

    /** Child elements in document order. */
    val children: List<ManifestElement>

    /** (name, decoded value) in document order. */
    val attributes: List<Pair<String, String>>

    fun attr(name: String): String?

    /** Replaces the value of an existing attribute (keeping its position) or appends a new one. */
    fun setAttr(name: String, value: String)

    fun removeAttr(name: String): Boolean

    /** Detaches the element (with its subtree) from its parent. */
    fun remove()

    /** Adds a child element with [attrs] in the given order; [index] < 0 appends. */
    fun addChild(tag: String, attrs: List<Pair<String, String>>, index: Int = -1): ManifestElement
}

interface ManifestDocument {
    val root: ManifestElement
}

/** The string resources (`res/values<qualifier>/strings.xml`) the ops can add to. */
interface ResourceTable {
    /** True if a string named [name] exists in any configuration. */
    fun hasString(name: String): Boolean

    /** True if `res/values<qualifier>/strings.xml` exists (`""` default, `"-es"`, ...). */
    fun hasStringsFile(qualifier: String): Boolean

    fun addString(qualifier: String, name: String, text: String)

    /** Value of string [name] in `values<qualifier>`, or null. */
    fun string(qualifier: String, name: String): String?

    /** `type/name` of the entry defined at resource id [id] in any configuration, or null if the slot is empty everywhere. */
    fun entryAt(id: Int): String?

    /** True if an entry named [name] of type [type] exists in any configuration. */
    fun hasEntry(type: String, name: String): Boolean

    /**
     * Defines [type]/[name] at [id] in the default configuration with the value `@null` (a reference
     * with data 0, as aapt2 compiles `@null`). Throws [PatchFailure] if the table has no [type] with
     * the id's type byte or the slot is defined in some configuration.
     */
    fun defineNullEntry(type: String, name: String, id: Int)
}

/** What the ops operate on: smali classes, the manifest and the resource table. */
class PatchContext(
    val smali: SmaliTree,
    private val manifestProvider: () -> ManifestDocument = { throw PatchFailure("no manifest available") },
    private val resourcesProvider: () -> ResourceTable = { throw PatchFailure("no resources available") },
    /**
     * Maps patch text to the form compared by [Op.verifyApplied]; the identity, except for the
     * self-check of a rebuilt APK where labels are canonical (see [CanonicalLabelsTree]).
     */
    val canon: (String) -> String = { it },
) {
    val manifest: ManifestDocument by lazy(manifestProvider)
    val resources: ResourceTable by lazy(resourcesProvider)
}
