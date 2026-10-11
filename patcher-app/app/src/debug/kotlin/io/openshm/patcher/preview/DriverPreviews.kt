package io.openshm.patcher.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.openshm.patcher.R
import io.openshm.patcher.ui.components.TimeBackdrop
import io.openshm.patcher.ui.components.WelcomeHero
import io.openshm.patcher.ui.screens.profile.GuideStep
import io.openshm.patcher.ui.screens.profile.WatchVersionGuide
import io.openshm.patcher.ui.screens.signingkey.SigningKeyForm

/*
 * Public zero-argument composables for the Compose Driver (tools/compose-driver). They use the
 * fallback palette so renders do not depend on the wallpaper. Run:
 *   scripts/compose-driver.sh list
 *   scripts/compose-driver.sh start WelcomeDriver [--dark] [--font-scale 2.0]
 */

@Composable
fun WelcomeDriver() = PreviewHost(ColorMode.Fallback) { WelcomeSample() }

/** Welcome over the floating backdrop, as PatcherApp layers them (still, like every driver). */
@Composable
fun WelcomeBackdropDriver() = PreviewHost(ColorMode.Fallback) {
    Box {
        TimeBackdrop(dispersed = false)
        WelcomeSample()
    }
}

/** The welcome hero frozen mid-heartbeat while the watch shows the ECG trace. */
@Composable
fun WelcomeHeroEcgDriver() = PreviewHost(ColorMode.Fallback) {
    WelcomeHero(Modifier.fillMaxWidth().height(220.dp), frozenAt = 4.488f)
}

/** The watch-version guide's content (outside its dialog window) at each step. */
@Composable
fun GuidePanelDriver() = GuideDriver(GuideStep.Panel)

@Composable
fun GuideSettingsDriver() = GuideDriver(GuideStep.Settings)

@Composable
fun GuideAboutDriver() = GuideDriver(GuideStep.About)

@Composable
fun GuideSoftwareDriver() = GuideDriver(GuideStep.Software)

@Composable
private fun GuideDriver(step: GuideStep) = PreviewHost(ColorMode.Fallback) {
    WatchVersionGuide(step = step, onStep = {}, onDone = {}, modifier = Modifier.padding(16.dp))
}

@Composable
fun WelcomeChangesDriver() = PreviewHost(ColorMode.Fallback) { WelcomeSample(sheet = true) }

@Composable
fun ProfileDriver() = PreviewHost(ColorMode.Fallback) { ProfileSample() }

@Composable
fun ProfileCertainHintDriver() = PreviewHost(ColorMode.Fallback) { ProfileSample(ProfileVariant.CertainHint) }

@Composable
fun ProfileDependsHintDriver() = PreviewHost(ColorMode.Fallback) { ProfileSample(ProfileVariant.DependsHint) }

@Composable
fun ProfileDetectedDriver() = PreviewHost(ColorMode.Fallback) { ProfileSample(ProfileVariant.Detected) }

@Composable
fun WatchPairCorrectionDriver() = PreviewHost(ColorMode.Fallback) { WatchPairSample(correction = true) }

@Composable
fun WearablePairingDriver() = PreviewHost(ColorMode.Fallback) { WearablePairingSample() }

@Composable
fun WatchPairDriver() = PreviewHost(ColorMode.Fallback) { WatchPairSample() }

@Composable
fun WatchPairReadyDriver() = PreviewHost(ColorMode.Fallback) { WatchPairSample(ready = true) }

@Composable
fun AcquireDriver() = PreviewHost(ColorMode.Fallback) { AcquireSample() }

@Composable
fun GuidedBrowserDriver() = PreviewHost(ColorMode.Fallback) { GuidedBrowserSample() }

@Composable
fun GuidedBrowserStep1Driver() = PreviewHost(ColorMode.Fallback) { GuidedBrowserSample(step = 1) }

@Composable
fun GuidedBrowserWrongVersionDriver() = PreviewHost(ColorMode.Fallback) { GuidedBrowserSample(step = 1, wrongVersion = true) }

@Composable
fun GuidedBrowserStep3Driver() = PreviewHost(ColorMode.Fallback) { GuidedBrowserSample(step = 3) }

@Composable
fun CustomizeDriver() = PreviewHost(ColorMode.Fallback) { CustomizeSample() }

@Composable
fun BuildDriver() = PreviewHost(ColorMode.Fallback) { BuildSample() }

@Composable
fun BuildLogDriver() = PreviewHost(ColorMode.Fallback) { BuildSample(logSheet = true) }

@Composable
fun BuildDoneDriver() = PreviewHost(ColorMode.Fallback) { BuildDoneSample() }

@Composable
fun BuildDoneBackupDriver() = PreviewHost(ColorMode.Fallback) { BuildDoneSample(backupDue = true) }

@Composable
fun InstallPhoneDriver() = PreviewHost(ColorMode.Fallback) { InstallPhoneSample() }

@Composable
fun InstallPhoneGrantsDriver() = PreviewHost(ColorMode.Fallback) { InstallPhoneSample(grants = true) }

@Composable
fun WatchInstallDriver() = PreviewHost(ColorMode.Fallback) { WatchInstallSample() }

@Composable
fun DevModeDriver() = PreviewHost(ColorMode.Fallback) { DevModeSample() }

@Composable
fun DisableStockDriver() = PreviewHost(ColorMode.Fallback) { DisableStockSample() }

@Composable
fun SelfTestDriver() = PreviewHost(ColorMode.Fallback) { SelfTestSample() }

@Composable
fun SelfTestFailuresDriver() = PreviewHost(ColorMode.Fallback) { SelfTestSample(SelfTestVariant.Failures) }

@Composable
fun SelfTestWatchOffDriver() = PreviewHost(ColorMode.Fallback) { SelfTestSample(SelfTestVariant.WatchOff) }

@Composable
fun AllSetDriver() = PreviewHost(ColorMode.Fallback) { AllSetSample() }

@Composable
fun HomeDriver() = PreviewHost(ColorMode.Fallback) { HomeSample() }

@Composable
fun HomePatchesUpdateDriver() = PreviewHost(ColorMode.Fallback) { HomeSample(patchesOnly = true) }

@Composable
fun HomeRestoreDriver() = PreviewHost(ColorMode.Fallback) { HomeSample(restoreSheet = true) }

@Composable
fun SigningKeyDriver() = PreviewHost(ColorMode.Fallback) { SigningKeySample() }

@Composable
fun SigningKeyEmptyDriver() = PreviewHost(ColorMode.Fallback) { SigningKeySample(keyExists = false) }

@Composable
fun SigningKeyExportDriver() = PreviewHost(ColorMode.Fallback) {
    SigningKeySample(form = SigningKeyForm.Export, formError = R.string.signing_key_error_short)
}

@Composable
fun SigningKeyImportConfirmDriver() = PreviewHost(ColorMode.Fallback) {
    SigningKeySample(form = SigningKeyForm.Import, confirmation = true)
}
