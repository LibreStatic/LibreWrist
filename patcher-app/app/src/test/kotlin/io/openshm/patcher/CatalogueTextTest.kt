package io.openshm.patcher

import io.openshm.patcher.data.CatalogueText
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogueTextTest {
    private val strings = mapOf(
        "en" to mapOf("a" to "en-a", "b" to "en-b", "c" to "en-c"),
        "es" to mapOf("a" to "es-a", "b" to "es-b"),
        "es-AR" to mapOf("a" to "es-AR-a"),
        "zh-TW" to mapOf("a" to "zh-TW-a"),
        "id" to mapOf("a" to "id-a"),
    )

    private fun text(vararg tags: String) = CatalogueText(strings, tags.map(Locale::forLanguageTag))

    @Test
    fun theExactTagWinsThenTheLanguageThenEnglish() {
        val t = text("es-AR")
        assertEquals("es-AR-a", t.get("a"))
        assertEquals("es-b", t.get("b"))
        assertEquals("en-c", t.get("c"))
    }

    @Test
    fun aCountryWithoutItsOwnTableUsesTheLanguage() {
        assertEquals("es-a", text("es-MX").get("a"))
    }

    @Test
    fun theLocaleListIsTriedInOrder() {
        assertEquals("zh-TW-a", text("zh-TW", "es").get("a"))
        assertEquals("es-b", text("fr", "es").get("b"))
    }

    @Test
    fun legacyJavaLanguageCodesMapToTheirBcp47Tag() {
        assertEquals("id-a", CatalogueText(strings, listOf(Locale("in", "ID"))).get("a"))
    }

    @Test
    fun anUnknownLocaleFallsBackToEnglishAndThenToTheCallersFallback() {
        val t = text("ja")
        assertEquals("en-a", t.get("a"))
        assertNull(t.get("missing"))
        assertEquals("fallback", t.text("missing", "fallback"))
    }
}
