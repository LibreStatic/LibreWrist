package io.openshm.patcher.preview

import androidx.compose.runtime.Composable
import io.openshm.patcher.ui.screens.acquire.AcquireScreen
import io.openshm.patcher.ui.screens.acquire.GuidedBrowserScreen
import io.openshm.patcher.ui.screens.build.BuildDoneScreen
import io.openshm.patcher.ui.screens.build.BuildScreen
import io.openshm.patcher.ui.screens.customize.CustomizeScreen
import io.openshm.patcher.ui.screens.devmode.DevModeScreen
import io.openshm.patcher.ui.screens.disable.DisableStockScreen
import io.openshm.patcher.ui.screens.home.HomeScreen
import io.openshm.patcher.ui.screens.install.InstallPhoneScreen
import io.openshm.patcher.ui.screens.install.WatchInstallScreen
import io.openshm.patcher.ui.screens.profile.ProfileScreen
import io.openshm.patcher.ui.screens.selftest.AllSetScreen
import io.openshm.patcher.ui.screens.selftest.SelfTestScreen
import io.openshm.patcher.ui.screens.signingkey.SigningKeyForm
import io.openshm.patcher.ui.screens.signingkey.SigningKeyScreen
import io.openshm.patcher.ui.screens.watchpair.WatchPairScreen
import io.openshm.patcher.ui.screens.wearable.WearablePairingScreen
import io.openshm.patcher.ui.screens.welcome.WelcomeScreen
import io.openshm.patcher.ui.screens.build.BuildStage

/*
 * The real, stateless production screens fed with fake UiState and no-op callbacks. Previews and
 * the Compose Driver both render these, so there is exactly one sample per screen.
 */

@Composable
internal fun WelcomeSample(sheet: Boolean = false) = WelcomeScreen(PreviewData.welcome(sheet), {}, {}, {})

@Composable
internal fun ProfileSample() = ProfileScreen(PreviewData.profile(), {}, {}, {})

@Composable
internal fun WearablePairingSample() = WearablePairingScreen(PreviewData.wearable(), {}, {}, {})

@Composable
internal fun WatchPairSample(ready: Boolean = false) =
    WatchPairScreen(if (ready) PreviewData.watchPairReady() else PreviewData.watchPairEmpty(), {}, {})

@Composable
internal fun AcquireSample() = AcquireScreen(PreviewData.acquire(), {}, {}, {})

@Composable
internal fun GuidedBrowserSample() = GuidedBrowserScreen(PreviewData.guidedBrowser(), {})

@Composable
internal fun CustomizeSample() = CustomizeScreen(PreviewData.customize(), {}, {}, {})

@Composable
internal fun BuildSample(logSheet: Boolean = false) =
    BuildScreen(PreviewData.build(BuildStage.Apply).copy(logVisible = logSheet, log = listOf("start unpack", "start fingerprints", "start apply")), {}, {})

@Composable
internal fun BuildDoneSample(backupDue: Boolean = false) = BuildDoneScreen(PreviewData.buildDone(backupDue), {}, {})

@Composable
internal fun InstallPhoneSample(grants: Boolean = false) =
    InstallPhoneScreen(if (grants) PreviewData.installPhoneGrants() else PreviewData.installPhone(), {}, {}, {})

@Composable
internal fun WatchInstallSample() = WatchInstallScreen(PreviewData.installWatch(), {}, {}, {})

@Composable
internal fun DevModeSample() = DevModeScreen(PreviewData.devMode(), {}, {}, {})

@Composable
internal fun DisableStockSample() = DisableStockScreen(PreviewData.disableStock(), {}, {}, {})

@Composable
internal fun SelfTestSample(variant: SelfTestVariant = SelfTestVariant.Passed) = SelfTestScreen(
    when (variant) {
        SelfTestVariant.Passed -> PreviewData.selfTest()
        SelfTestVariant.Failures -> PreviewData.selfTestFailures()
        SelfTestVariant.WatchOff -> PreviewData.selfTestWatchOff()
    },
    {}, {}, {},
)

internal enum class SelfTestVariant { Passed, Failures, WatchOff }

@Composable
internal fun AllSetSample() = AllSetScreen(PreviewData.allSet(), {}, {})

@Composable
internal fun HomeSample(restoreSheet: Boolean = false, menu: Boolean = false) =
    HomeScreen(PreviewData.home(restoreSheet = restoreSheet, menu = menu), {})

@Composable
internal fun SigningKeySample(
    keyExists: Boolean = true,
    form: SigningKeyForm = SigningKeyForm.None,
    formError: Int? = null,
    confirmation: Boolean = false,
) = SigningKeyScreen(PreviewData.signingKey(keyExists, form, formError, confirmation = confirmation), {}, {})
