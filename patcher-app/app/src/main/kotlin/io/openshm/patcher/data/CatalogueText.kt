package io.openshm.patcher.data

import java.util.Locale

/**
 * Catalogue `strings` for the user's locale list. A key is looked up per locale (exact tag such as
 * `zh-TW`, then language and country, then language), then in `en`; callers pass their own fallback.
 */
class CatalogueText(private val strings: Map<String, Map<String, String>>, locales: List<Locale>) {
    private val tables: List<Map<String, String>> =
        (locales.flatMap(::tagsOf) + EN).distinct().mapNotNull { strings[it] }

    /** The text for [key] in the best matching locale, or null when no table has it. */
    fun get(key: String): String? = tables.firstNotNullOfOrNull { it[key] }

    /** The text for [key], or [fallback]. */
    fun text(key: String, fallback: String): String = get(key) ?: fallback

    companion object {
        private const val EN = "en"

        /** Tags to try for [locale], most specific first; legacy Java language codes map to their BCP-47 form. */
        internal fun tagsOf(locale: Locale): List<String> {
            val language = LEGACY.getOrDefault(locale.language, locale.language)
            if (language.isEmpty()) return emptyList()
            return listOfNotNull(
                locale.toLanguageTag().takeIf { it != "und" }?.replaceFirst(locale.language, language),
                locale.country.takeIf { it.isNotEmpty() }?.let { "$language-$it" },
                language,
            ).distinct()
        }

        private val LEGACY = mapOf("in" to "id", "iw" to "he", "ji" to "yi")
    }
}
