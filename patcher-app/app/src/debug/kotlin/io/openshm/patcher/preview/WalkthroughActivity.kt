package io.openshm.patcher.preview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Debug-only: renders one Compose Driver sample full screen on a device or emulator, so the README
 * walkthrough can be captured with real system bars. The data is fake (see [PreviewData]).
 *   adb shell am start -n io.openshm.patcher/.preview.WalkthroughActivity --es screen WelcomeDriver
 */
class WalkthroughActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val screen = intent.getStringExtra("screen").orEmpty()
        setContent { WalkthroughScreen(screen) }
    }
}

@Composable
private fun WalkthroughScreen(name: String) {
    when (name) {
        "WelcomeDriver" -> WelcomeDriver()
        "WelcomeChangesDriver" -> WelcomeChangesDriver()
        "ProfileDetectedDriver" -> ProfileDetectedDriver()
        "WearablePairingDriver" -> WearablePairingDriver()
        "WatchPairDriver" -> WatchPairDriver()
        "WatchPairGuideWirelessDriver" -> WatchPairGuideWirelessDriver()
        "WatchPairPairingDriver" -> WatchPairPairingDriver()
        "WatchPairReadyDriver" -> WatchPairReadyDriver()
        "AcquireDriver" -> AcquireDriver()
        "GuidedBrowserDriver" -> GuidedBrowserDriver()
        "CustomizeDriver" -> CustomizeDriver()
        "BuildDriver" -> BuildDriver()
        "BuildDoneDriver" -> BuildDoneDriver()
        "InstallPhoneDriver" -> InstallPhoneDriver()
        "InstallPhoneGrantsDriver" -> InstallPhoneGrantsDriver()
        "WatchInstallDriver" -> WatchInstallDriver()
        "DevModeDriver" -> DevModeDriver()
        "DisableStockDriver" -> DisableStockDriver()
        "SelfTestDriver" -> SelfTestDriver()
        "AllSetDriver" -> AllSetDriver()
        "HomeDriver" -> HomeDriver()
        else -> PreviewHost(ColorMode.Fallback) { Text("Unknown screen: $name") }
    }
}
