package io.openshm.patcher.ui.theme

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont

/**
 * Roboto Flex (UI) and Roboto Mono (package ids) come from the Google Fonts provider in Google
 * Play services; no font binary is bundled. Until a font is delivered, or when the provider is
 * missing, Compose renders with the platform default (Roboto) and [FontFamily.Monospace].
 */
internal object PatcherFonts {
    private const val PROVIDER_AUTHORITY = "com.google.android.gms.fonts"
    private const val PROVIDER_PACKAGE = "com.google.android.gms"

    private val uiWeights = listOf(400, 500, 600, 700, 800, 900)
    private val monoWeights = listOf(400, 500, 700)

    fun uiFamily(context: Context): FontFamily = family(context, "Roboto Flex", uiWeights, FontFamily.SansSerif)

    fun monoFamily(context: Context): FontFamily = family(context, "Roboto Mono", monoWeights, FontFamily.Monospace)

    private fun family(context: Context, name: String, weights: List<Int>, fallback: FontFamily): FontFamily {
        val provider = provider(context) ?: return fallback
        val googleFont = GoogleFont(name)
        return FontFamily(weights.map { Font(googleFont = googleFont, fontProvider = provider, weight = FontWeight(it)) })
    }

    /**
     * The provider is used only when it is a system app. Its signing certificates are then read from
     * the installed package instead of being copied into the repo, which is how the platform's own
     * emoji font loader treats the same provider.
     */
    private fun provider(context: Context): GoogleFont.Provider? {
        val certificates = runCatching { systemProviderCertificates(context.packageManager) }.getOrNull()
        if (certificates.isNullOrEmpty()) return null
        return GoogleFont.Provider(PROVIDER_AUTHORITY, PROVIDER_PACKAGE, listOf(certificates))
    }

    private fun systemProviderCertificates(pm: PackageManager): List<ByteArray>? {
        val info = pm.getPackageInfo(PROVIDER_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
        val isSystem = ((info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) != 0
        if (!isSystem) return null
        val signingInfo = info.signingInfo ?: return null
        val signers = if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory
        }
        return signers?.map { it.toByteArray() }
    }
}
