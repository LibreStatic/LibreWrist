package io.openshm.patcher.data

import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import java.util.Locale
import java.util.TimeZone

/** The package of Samsung Health; its China build is the one published on YingYongBao. */
const val SHEALTH_PACKAGE = "com.sec.android.app.shealth"

/** What the phone says about where it is. A null or blank value means the signal is not available. */
data class RegionSignals(
    val simCountryIso: String?,
    val networkCountryIso: String?,
    val localeRegion: String?,
    val timeZoneId: String?,
    /** The versionName of the Samsung Health already installed on the phone, when there is one. */
    val stockShealthVersion: String?,
)

private val CHINA_TIME_ZONES = setOf("Asia/Shanghai", "Asia/Urumqi", "Asia/Chongqing", "Asia/Harbin")

private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/**
 * True when the phone looks to be in mainland China: a SIM or network registered there, a stock
 * Samsung Health that is a China build ([chinaShealthVersions]), or a CN locale together with a
 * mainland time zone (the locale alone is not enough). Hong Kong, Macau and Taiwan do not count.
 */
fun detectChina(signals: RegionSignals, chinaShealthVersions: Set<String>): Boolean {
    if (signals.simCountryIso.clean().equals("cn", ignoreCase = true)) return true
    if (signals.networkCountryIso.clean().equals("cn", ignoreCase = true)) return true
    val stock = signals.stockShealthVersion.clean()
    if (stock != null && stock in chinaShealthVersions) return true
    return signals.localeRegion.clean().equals("CN", ignoreCase = true) && signals.timeZoneId.clean() in CHINA_TIME_ZONES
}

/**
 * The versionNames of the Samsung Health inputs that come from YingYongBao: only China builds are
 * published there, so these identify an installed China build.
 */
fun chinaShealthVersions(inputs: List<InputApp>): Set<String> =
    inputs.filter { it.packageName == SHEALTH_PACKAGE && it.onYyb }.mapTo(mutableSetOf()) { it.versionName }

/**
 * Whether this phone was detected to be in mainland China, read once by [init] (the app does it in
 * `PatcherApplication`); false before that, which is what previews and unit tests see. The user's
 * choice lives in [AutomationState.chinaMode] and overrides it.
 */
object Region {
    @Volatile
    var detected: Boolean = false
        private set

    fun init(context: Context) {
        val app = context.applicationContext
        val telephony = app.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val stock = try {
            app.packageManager.getPackageInfo(SHEALTH_PACKAGE, 0).versionName
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        val signals = RegionSignals(
            simCountryIso = telephony?.simCountryIso,
            networkCountryIso = telephony?.networkCountryIso,
            localeRegion = Locale.getDefault().country,
            timeZoneId = TimeZone.getDefault().id,
            stockShealthVersion = stock,
        )
        detected = detectChina(signals, AppCatalogue.current.chinaShealthVersions)
    }
}
