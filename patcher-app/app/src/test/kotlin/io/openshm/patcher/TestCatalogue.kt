package io.openshm.patcher

import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.LoadedCatalogue
import java.io.File
import java.util.Locale

/** Loads the repository catalogue through the real verify + parse path for JVM tests. */
object TestCatalogue {
    private fun repoFile(name: String): File =
        listOf("../../catalogue/$name", "../catalogue/$name").map(::File).first { it.isFile }

    val bytes: ByteArray get() = repoFile("catalogue.json").readBytes()
    val signature: ByteArray get() = repoFile("catalogue.json.sig").readBytes()
    val publicKeyPem: String get() = repoFile("catalogue.pub").readText()

    /** The locale list tests resolve catalogue text for. */
    val locales = listOf(Locale.ENGLISH)

    fun load(appVersionCode: Int = BuildConfig.VERSION_CODE, bytes: ByteArray = this.bytes): LoadedCatalogue =
        AppCatalogue.fromBytes(bytes, signature, publicKeyPem, appVersionCode, locales)

    fun install() = AppCatalogue.install(load())
}
