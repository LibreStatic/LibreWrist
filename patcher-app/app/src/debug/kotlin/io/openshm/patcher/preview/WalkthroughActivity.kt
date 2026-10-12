package io.openshm.patcher.preview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.openshm.patcher.device.PhoneSettings
import io.openshm.patcher.device.SelfAdbController
import io.openshm.patcher.device.SelfAdbPurpose
import io.openshm.patcher.device.SelfAdbService
import io.openshm.patcher.ui.screens.install.AdbOutcome
import io.openshm.patcher.ui.screens.install.InstallEvent
import io.openshm.patcher.ui.screens.install.InstallPhoneScreen
import io.openshm.patcher.ui.screens.install.NEEDS_PAIRING_SCREEN
import io.openshm.patcher.ui.screens.install.SelfAdbPhase

/**
 * Debug-only: renders one Compose Driver sample full screen on a device or emulator, so the README
 * walkthrough can be captured with real system bars. The data is fake (see [PreviewData]).
 *   adb shell am start -n io.openshm.patcher/.preview.WalkthroughActivity --es screen WelcomeDriver
 * `--es screen SelfAdbLive` instead runs the real Wireless debugging pairing with this phone (the
 * notification, Settings and the code entry are real) over the fake install list, and grants nothing.
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
        "InstallPhoneAdbReadyDriver" -> InstallPhoneAdbReadyDriver()
        "InstallPhoneAdbSuccessDriver" -> InstallPhoneAdbSuccessDriver()
        "SelfAdbLive" -> PreviewHost(ColorMode.Fallback) { SelfAdbLive() }
        else -> PreviewHost(ColorMode.Fallback) { Text("Unknown screen: $name") }
    }
}

/** The real self-ADB attempt behind the fake install list; once connected it shows success and ends. */
@Composable
private fun SelfAdbLive() {
    val context = LocalContext.current
    val snapshot by SelfAdbController.state.collectAsState()
    var paired by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (SelfAdbController.begin(SelfAdbPurpose.PhoneGrants)) SelfAdbService.start(context)
    }
    val phase = snapshot.phase
    LaunchedEffect(phase) {
        if (phase in NEEDS_PAIRING_SCREEN) {
            PhoneSettings.wirelessDebugging().let { context.startActivity(it.intent) }
        }
        if (phase == SelfAdbPhase.Granting) {
            SelfAdbController.takeClient(SelfAdbPurpose.PhoneGrants)
            SelfAdbController.finish()
            paired = true
        }
    }
    if (paired) {
        InstallPhoneScreen(PreviewData.installPhoneAdbOutcome(AdbOutcome.Success), {}, {}, {})
        return
    }
    InstallPhoneScreen(
        PreviewData.installPhoneGrants(phase),
        { event -> if (event is InstallEvent.SubmitPairingCode) SelfAdbController.submitCode(event.code) },
        {},
        {},
    )
}
