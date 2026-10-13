package io.openshm.patcher.preview

import io.openshm.patcher.ui.screens.install.AdbOutcome
import io.openshm.patcher.ui.screens.build.KeyBackupStatus
import io.openshm.patcher.ui.screens.build.ManualBackupDialog
import io.openshm.patcher.ui.screens.welcome.SkipDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.openshm.patcher.R
import io.openshm.patcher.ui.components.PhoneWatchIllustration
import io.openshm.patcher.ui.components.TimeBackdrop
import io.openshm.patcher.ui.components.WelcomeHero
import io.openshm.patcher.ui.screens.profile.GuideStep
import io.openshm.patcher.ui.screens.profile.WatchVersionGuide
import io.openshm.patcher.ui.screens.signingkey.SigningKeyForm
import io.openshm.patcher.ui.screens.install.SelfAdbPhase
import io.openshm.patcher.ui.screens.wearable.ClearOutcome
import io.openshm.patcher.ui.screens.wearable.TroubleshootUiState
import io.openshm.patcher.ui.screens.welcome.ChangesContent
import io.openshm.patcher.ui.screens.welcome.buildChanges
import io.openshm.patcher.data.WatchProfile
import io.openshm.patcher.device.LogDevice
import io.openshm.patcher.device.LogLinkStatus

/*
 * Public zero-argument composables for the Compose Driver (tools/compose-driver). They use the
 * fallback palette so renders do not depend on the wallpaper. Run:
 *   scripts/compose-driver.sh list
 *   scripts/compose-driver.sh start WelcomeDriver [--dark] [--font-scale 2.0]
 */

@Composable
fun WelcomeDriver() = PreviewHost(ColorMode.Fallback) { WelcomeSample() }

/** Welcome over the floating backdrop, as PatcherApp layers them (still, like every driver). */
/** Welcome with the watch reachable over ADB: the device logs link shows at the bottom. */
@Composable
fun WelcomeWatchDriver() = PreviewHost(ColorMode.Fallback) { WelcomeSample(watchReachable = true) }

/** AllSet with the watch reachable over ADB. */
@Composable
fun AllSetWatchDriver() = PreviewHost(ColorMode.Fallback) { AllSetSample(watchReachable = true) }

/** Device logs: the watch tab streaming ~40 lines, the phone tab disconnected. */
@Composable
fun DeviceLogsDriver() = PreviewHost(ColorMode.Fallback) { DeviceLogsSample() }

/** Device logs on the phone tab, disconnected because Wireless debugging is off. */
@Composable
fun DeviceLogsPhoneDriver() = PreviewHost(ColorMode.Fallback) { DeviceLogsSample(PreviewData.deviceLogs(tab = LogDevice.Phone)) }

/** Device logs on the watch tab while reconnecting. */
@Composable
fun DeviceLogsReconnectingDriver() = PreviewHost(ColorMode.Fallback) {
    DeviceLogsSample(PreviewData.deviceLogs(watchStatus = LogLinkStatus.Reconnecting))
}

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

/** The "what will change" content without the modal sheet, so it renders full length. */
@Composable
fun ChangesDriver() = PreviewHost(ColorMode.Fallback) {
    ChangesContent(buildChanges(PreviewData.PREFIX, WatchProfile.WearOs5), Modifier.padding(top = 16.dp))
}

@Composable
fun WelcomeSkipDriver() = PreviewHost(ColorMode.Fallback) { WelcomeSample(skip = SampleSkipping) }

@Composable
fun WelcomeCheckingDriver() = PreviewHost(ColorMode.Fallback) { WelcomeSample(skip = SkipDialog.Checking) }

@Composable
fun ProfileDriver() = PreviewHost(ColorMode.Fallback) { ProfileSample() }

@Composable
fun ProfileCertainHintDriver() = PreviewHost(ColorMode.Fallback) { ProfileSample(ProfileVariant.CertainHint) }

@Composable
fun ProfileDependsHintDriver() = PreviewHost(ColorMode.Fallback) { ProfileSample(ProfileVariant.DependsHint) }

@Composable
fun ProfileDetectedDriver() = PreviewHost(ColorMode.Fallback) { ProfileSample(ProfileVariant.Detected) }

@Composable
fun ProfileNetworkDetectedDriver() = PreviewHost(ColorMode.Fallback) { ProfileSample(ProfileVariant.NetworkDetected) }

@Composable
fun ProfileDeniedDriver() = PreviewHost(ColorMode.Fallback) { ProfileSample(ProfileVariant.Denied) }

@Composable
fun WatchPairCorrectionDriver() = PreviewHost(ColorMode.Fallback) { WatchPairSample(PreviewData.watchPairCorrection()) }

/** "Is your watch paired?": everything found; then each failing check; then the Bluetooth permission denied. */
@Composable
fun WearablePairingDriver() = PreviewHost(ColorMode.Fallback) { WearablePairingSample() }

@Composable
fun WearablePairingMissingDriver() = PreviewHost(ColorMode.Fallback) {
    WearablePairingSample(PreviewData.wearable(packages = emptySet(), bonded = emptyList()))
}

@Composable
fun WearablePairingNoWatchDriver() = PreviewHost(ColorMode.Fallback) { WearablePairingSample(PreviewData.wearable(bonded = emptyList())) }

@Composable
fun WearablePairingDeniedDriver() = PreviewHost(ColorMode.Fallback) {
    WearablePairingSample(PreviewData.wearable(bluetoothAllowed = false, bonded = emptyList()))
}

/** "Trouble pairing?": idle, waiting for the pairing code, the warning dialog, clearing, done, failed. */
@Composable
fun WearableTroubleshootDriver() = PreviewHost(ColorMode.Fallback) { WearableTroubleshootSample() }

@Composable
fun WearableTroubleshootCodeDriver() = PreviewHost(ColorMode.Fallback) {
    WearableTroubleshootSample(TroubleshootUiState(adb = SelfAdbPhase.WaitingForCode, pendingClear = true))
}

@Composable
fun WearableTroubleshootDialogDriver() = PreviewHost(ColorMode.Fallback) {
    WearableTroubleshootSample(TroubleshootUiState(confirming = true))
}

@Composable
fun WearableTroubleshootDoneDriver() = PreviewHost(ColorMode.Fallback) {
    WearableTroubleshootSample(TroubleshootUiState(outcome = ClearOutcome.Success))
}

@Composable
fun WearableTroubleshootFailedDriver() = PreviewHost(ColorMode.Fallback) {
    WearableTroubleshootSample(
        TroubleshootUiState(outcome = ClearOutcome.Failed, errorRes = R.string.troubleshoot_clear_failed, detail = "Failed"),
    )
}

/** Watch pairing: the inline guide at its first and last steps, found, manual, pairing and failed. */
@Composable
fun WatchPairDriver() = PreviewHost(ColorMode.Fallback) { WatchPairSample() }

@Composable
fun WatchPairGuideLastDriver() = PreviewHost(ColorMode.Fallback) { WatchPairSample(PreviewData.watchPairGuide(3)) }

@Composable
fun WatchPairGuideWifiDriver() = PreviewHost(ColorMode.Fallback) { WatchPairSample(PreviewData.watchPairGuide(1)) }

@Composable
fun WatchPairGuideWirelessDriver() = PreviewHost(ColorMode.Fallback) { WatchPairSample(PreviewData.watchPairGuide(2), subStep = 3) }

@Composable
fun WatchPairReadyDriver() = PreviewHost(ColorMode.Fallback) { WatchPairSample(PreviewData.watchPairReady()) }

@Composable
fun WatchPairManualDriver() = PreviewHost(ColorMode.Fallback) { WatchPairSample(PreviewData.watchPairManual()) }

@Composable
fun WatchPairManualInvalidDriver() = PreviewHost(ColorMode.Fallback) {
    WatchPairSample(PreviewData.watchPairManual(host = "192.168 .1", port = "70000", code = "12"))
}

@Composable
fun WatchPairPairingDriver() = PreviewHost(ColorMode.Fallback) { WatchPairSample(PreviewData.watchPairPairing()) }

@Composable
fun WatchPairReconnectingDriver() = PreviewHost(ColorMode.Fallback) { WatchPairSample(PreviewData.watchPairReconnecting()) }

@Composable
fun WatchPairReconnectHintDriver() = PreviewHost(ColorMode.Fallback) { WatchPairSample(PreviewData.watchPairReconnectHint()) }

@Composable
fun WatchPairReconnectedDriver() = PreviewHost(ColorMode.Fallback) { WatchPairSample(PreviewData.watchPairReconnected()) }

@Composable
fun WatchPairErrorDriver() = PreviewHost(ColorMode.Fallback) { WatchPairSample(PreviewData.watchPairError()) }

/** The phone and watch illustration on the tertiary container, at the two sizes the app uses. */
@Composable
fun PhoneWatchIllustrationDriver() = PreviewHost(ColorMode.Fallback) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
            PhoneWatchIllustration(Modifier.padding(16.dp), scale = 0.8f, linkColor = MaterialTheme.colorScheme.tertiary)
        }
        PhoneWatchIllustration()
    }
}

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

/** The longest ring caption in Spanish ("Autocomprobación"): render with `--qualifiers es`. */
@Composable
fun BuildSelfCheckDriver() = PreviewHost(ColorMode.Fallback) { BuildSample(selfCheck = true) }

@Composable
fun BuildAlmostDoneDriver() = PreviewHost(ColorMode.Fallback) { BuildSample(almostDone = true) }

@Composable
fun BuildCancelledDriver() = PreviewHost(ColorMode.Fallback) { BuildSample(cancelled = true) }

@Composable
fun BuildLogDriver() = PreviewHost(ColorMode.Fallback) { BuildSample(logSheet = true) }

@Composable
fun BuildDoneDriver() = PreviewHost(ColorMode.Fallback) { BuildDoneSample() }

@Composable
fun BuildDoneBackupDriver() = PreviewHost(ColorMode.Fallback) { BuildDoneSample(KeyBackupStatus.Failed) }

@Composable
fun BuildDoneBackupSavedDriver() = PreviewHost(ColorMode.Fallback) { BuildDoneSample(KeyBackupStatus.Saved) }

@Composable
fun BuildDoneBackupDeclinedDriver() = PreviewHost(ColorMode.Fallback) { BuildDoneSample(KeyBackupStatus.Declined) }

@Composable
fun BuildDoneBackupPrivateDriver() = PreviewHost(ColorMode.Fallback) { BuildDoneSample(KeyBackupStatus.PrivateOnly) }

@Composable
fun BuildDoneBackupNoManagerDriver() = PreviewHost(ColorMode.Fallback) { BuildDoneSample(KeyBackupStatus.PrivateNoPasswordManager) }

@Composable
fun ManualBackupDialogDriver() = PreviewHost(ColorMode.Fallback) { ManualBackupDialog({ _, _ -> true }, {}) }

@Composable
fun BuildDoneBackupSavingDriver() = PreviewHost(ColorMode.Fallback) { BuildDoneSample(KeyBackupStatus.Saving) }

@Composable
fun InstallPhoneDriver() = PreviewHost(ColorMode.Fallback) { InstallPhoneSample() }

@Composable
fun InstallPhoneGrantsDriver() = PreviewHost(ColorMode.Fallback) { InstallPhoneSample(grants = true) }

@Composable
fun InstallPhoneAdbReadyDriver() = PreviewHost(ColorMode.Fallback) { InstallPhoneSample(grants = true, adb = null) }

@Composable
fun InstallPhoneAdbSuccessDriver() = PreviewHost(ColorMode.Fallback) { InstallPhoneAdbOutcomeSample(AdbOutcome.Success) }

@Composable
fun InstallPhoneAdbFailureDriver() = PreviewHost(ColorMode.Fallback) {
    InstallPhoneAdbOutcomeSample(AdbOutcome.Failure("Applied over ADB but the system still reports these as missing: ANSWER_PHONE_CALLS, READ_CALL_LOG"))
}

@Composable
fun InstallPhoneAdbWaitingDriver() = PreviewHost(ColorMode.Fallback) { InstallPhoneSample(grants = true, adb = SelfAdbPhase.WaitingForService) }

@Composable
fun SelfAdbGuideDriver() = PreviewHost(ColorMode.Fallback) { SelfAdbGuideSample(subStep = 3) }

@Composable
fun SelfAdbGuideStartDriver() = PreviewHost(ColorMode.Fallback) { SelfAdbGuideSample(subStep = 0) }

@Composable
fun InstallPhoneConflictDriver() = PreviewHost(ColorMode.Fallback) { InstallPhoneConflictSample() }

@Composable
fun InstallPhoneConflictDialogDriver() = PreviewHost(ColorMode.Fallback) { InstallPhoneConflictSample(confirming = true) }

@Composable
fun WatchInstallDriver() = PreviewHost(ColorMode.Fallback) { WatchInstallSample() }

@Composable
fun WatchInstallUnreachableDriver() = PreviewHost(ColorMode.Fallback) { WatchInstallSample(unreachable = true) }

@Composable
fun DevModeDriver() = PreviewHost(ColorMode.Fallback) { DevModeSample() }

@Composable
fun DisableStockDriver() = PreviewHost(ColorMode.Fallback) { DisableStockSample() }

@Composable
fun DisableStockAlreadyOffDriver() = PreviewHost(ColorMode.Fallback) { DisableStockSample(alreadyOff = true) }

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

private val SampleAppUpdate = io.openshm.patcher.data.AppUpdate(
    build = 9,
    versionName = "0.1.0",
    apkUrl = "https://example.invalid/openshm-patcher-0.1.0-r9.apk",
    apkName = "openshm-patcher-0.1.0-r9.apk",
    sha256 = "ab".repeat(32),
    notes = "Fixes the watch SHM closing on open on the Galaxy Watch8.\nRecognizes the watch plugin of every Galaxy Watch family.",
    sizeBytes = 42L * 1024 * 1024,
)

@Composable
fun SettingsDriver() = PreviewHost(ColorMode.Fallback) {
    io.openshm.patcher.ui.screens.settings.SettingsScreen(
        io.openshm.patcher.ui.screens.settings.SettingsUiState(versionName = "0.1.0", versionCode = 7),
        notice = null, onNoticeShown = {}, onAutoCheck = {}, onCheckNow = {}, onSigningKey = {}, onBack = {},
    )
}

@Composable
fun SettingsNoRepoDriver() = PreviewHost(ColorMode.Fallback) {
    io.openshm.patcher.ui.screens.settings.SettingsScreen(
        io.openshm.patcher.ui.screens.settings.SettingsUiState(updatesAvailable = false, versionName = "0.1.0", versionCode = 6),
        notice = null, onNoticeShown = {}, onAutoCheck = {}, onCheckNow = {}, onSigningKey = {}, onBack = {},
    )
}

@Composable
fun AppUpdateDialogDriver() = PreviewHost(ColorMode.Fallback) {
    io.openshm.patcher.ui.update.AppUpdateDialog(
        io.openshm.patcher.ui.update.AppUpdateUiState.Available(SampleAppUpdate), {}, {}, {}, {},
    )
}

@Composable
fun AppUpdateDownloadingDriver() = PreviewHost(ColorMode.Fallback) {
    io.openshm.patcher.ui.update.AppUpdateDialog(
        io.openshm.patcher.ui.update.AppUpdateUiState.Downloading(SampleAppUpdate, 0.4f), {}, {}, {}, {},
    )
}
