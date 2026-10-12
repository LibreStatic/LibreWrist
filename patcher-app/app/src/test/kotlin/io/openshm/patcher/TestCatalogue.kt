package io.openshm.patcher

import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.LoadedCatalogue
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Loads the repository catalogue through the real verify + parse path for JVM tests. */
object TestCatalogue {
    private fun repoFile(name: String): File =
        listOf("../../catalogue/$name", "../catalogue/$name").map(::File).first { it.isFile }

    val bytes: ByteArray get() = repoFile("catalogue.json").readBytes()
    val signature: ByteArray get() = repoFile("catalogue.json.sig").readBytes()
    val publicKeyPem: String get() = repoFile("catalogue.pub").readText()

    /** String resources by id, read from `res/values/strings.xml` (the JVM has no Android resources). */
    val resolve: (Int) -> String by lazy {
        val names = R.string::class.java.fields.associate { it.getInt(null) to it.name }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values/strings.xml"))
        val texts = doc.getElementsByTagName("string").let { nodes ->
            (0 until nodes.length).associate {
                val n = nodes.item(it)
                n.attributes.getNamedItem("name").nodeValue to n.textContent.replace("\\'", "'")
            }
        }
        val lookup: (Int) -> String = { id -> texts.getValue(names.getValue(id)) }
        lookup
    }

    fun load(appVersionCode: Int = BuildConfig.VERSION_CODE, bytes: ByteArray = this.bytes): LoadedCatalogue =
        AppCatalogue.fromBytes(bytes, signature, publicKeyPem, appVersionCode, resolve)

    fun install() = AppCatalogue.install(load())
}
