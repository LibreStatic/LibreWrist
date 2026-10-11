package io.openshm.patcher.preview

import androidx.compose.runtime.Composable
import io.openshm.patcher.R
import io.openshm.patcher.ui.screens.signingkey.SigningKeyForm
import androidx.compose.ui.tooling.preview.PreviewParameter

/* One preview per screen; each expands to light/dark x font scale 1.0/2.0 x dynamic/fallback. */

@ScreenPreviews
@Composable
private fun WelcomePreview(@PreviewParameter(ColorModeProvider::class) mode: ColorMode) =
    PreviewHost(mode) { WelcomeSample() }

@ScreenPreviews
@Composable
private fun WelcomeChangesPreview(@PreviewParameter(ColorModeProvider::class) mode: ColorMode) =
    PreviewHost(mode) { WelcomeSample(sheet = true) }

@ScreenPreviews
@Composable
private fun ProfilePreview(@PreviewParameter(ColorModeProvider::class) mode: ColorMode) =
    PreviewHost(mode) { ProfileSample() }

@ScreenPreviews
@Composable
private fun ProfileDetectedPreview(@PreviewParameter(ColorModeProvider::class) mode: ColorMode) =
    PreviewHost(mode) { ProfileSample(ProfileVariant.Detected) }

@ScreenPreviews
@Composable
private fun ProfileHintPreview(@PreviewParameter(ColorModeProvider::class) mode: ColorMode) =
    PreviewHost(mode) { ProfileSample(ProfileVariant.DependsHint) }

@ScreenPreviews
@Composable
private fun WatchPairCorrectionPreview(@PreviewParameter(ColorModeProvider::class) mode: ColorMode) =
    PreviewHost(mode) { WatchPairSample(PreviewData.watchPairCorrection()) }

@ScreenPreviews
@Composable
private fun WearablePairingPreview(@PreviewParameter(ColorModeProvider::class) mode: ColorMode) =
    PreviewHost(mode) { WearablePairingSample() }

@ScreenPreviews
@Composable
private fun WearableTroubleshootPreview(@PreviewParameter(ColorModeProvider::class) mode: ColorMode) =
    PreviewHost(mode) { WearableTroubleshootSample() }

@ScreenPreviews
@Composable
private fun WatchPairPreview(@PreviewParameter(ColorModeProvider::class) mode: ColorMode) =
    PreviewHost(mode) { WatchPairSample() }

@ScreenPreviews
@Composable
private fun WatchPairReadyPreview(@PreviewParameter(ColorModeProvider::class) mode: ColorMode) =
    PreviewHost(mode) { WatchPairSample(PreviewData.watchPairReady()) }

@ScreenPreviews
@Composable
private fun AcquirePreview(@PreviewParameter(ColorModeProvider::class) mode: ColorMode) =
    PreviewHost(mode) { AcquireSample() }

@ScreenPreviews
@Composable
private fun GuidedBrowserPreview(@PreviewParameter(ColorModeProvider::class) mode: ColorMode) =
    PreviewHost(mode) { GuidedBrowserSample() }

@ScreenPreviews
@Composable
private fun GuidedBrowserWrongVersionPreview(@PreviewParameter(ColorModeProvider::class) mode: ColorMode) =
    PreviewHost(mode) { GuidedBrowserSample(step = 1, wrongVersion = true) }

@ScreenPreviews
@Composable
private fun GuidedBrowserStep3Preview(@PreviewParameter(ColorModeProvider::class) mode: ColorMode) =
    PreviewHost(mode) { GuidedBrowserSample(step = 3) }

@ScreenPreviews
@Composable
private fun CustomizePreview(@PreviewParameter(ColorModeProvider::class) mode: ColorMode) =
    PreviewHost(mode) { CustomizeSample() }

@ScreenPreviews
@Composable
private fun BuildPreview(@PreviewParameter(ColorModeProvider::class) mode: ColorMode) =
    PreviewHost(mode) { BuildSample() }

@ScreenPreviews
@Composable
private fun BuildAlmostDonePreview(@PreviewParameter(ColorModeProvider::class) mode: ColorMode) =
    PreviewHost(mode) { BuildSample(almostDone = true) }

@ScreenPreviews
@Composable
private fun BuildCancelledPreview(@PreviewParameter(ColorModeProvider::class) mode: ColorMode) =
    PreviewHost(mode) { BuildSample(cancelled = true) }

@ScreenPreviews
@Composable
private fun BuildDonePreview(@PreviewParameter(ColorModeProvider::class) mode: ColorMode) =
    PreviewHost(mode) { BuildDoneSample() }

@ScreenPreviews
@Composable
private fun InstallPhonePreview(@PreviewParameter(ColorModeProvider::class) mode: ColorMode) =
    PreviewHost(mode) { InstallPhoneSample() }

@ScreenPreviews
@Composable
private fun InstallPhoneGrantsPreview(@PreviewParameter(ColorModeProvider::class) mode: ColorMode) =
    PreviewHost(mode) { InstallPhoneSample(grants = true) }

@ScreenPreviews
@Composable
private fun WatchInstallPreview(@PreviewParameter(ColorModeProvider::class) mode: ColorMode) =
    PreviewHost(mode) { WatchInstallSample() }

@ScreenPreviews
@Composable
private fun DevModePreview(@PreviewParameter(ColorModeProvider::class) mode: ColorMode) =
    PreviewHost(mode) { DevModeSample() }

@ScreenPreviews
@Composable
private fun DisableStockPreview(@PreviewParameter(ColorModeProvider::class) mode: ColorMode) =
    PreviewHost(mode) { DisableStockSample() }

@ScreenPreviews
@Composable
private fun SelfTestPreview(@PreviewParameter(ColorModeProvider::class) mode: ColorMode) =
    PreviewHost(mode) { SelfTestSample() }

@ScreenPreviews
@Composable
private fun SelfTestFailuresPreview(@PreviewParameter(ColorModeProvider::class) mode: ColorMode) =
    PreviewHost(mode) { SelfTestSample(SelfTestVariant.Failures) }

@ScreenPreviews
@Composable
private fun SelfTestWatchOffPreview(@PreviewParameter(ColorModeProvider::class) mode: ColorMode) =
    PreviewHost(mode) { SelfTestSample(SelfTestVariant.WatchOff) }

@ScreenPreviews
@Composable
private fun AllSetPreview(@PreviewParameter(ColorModeProvider::class) mode: ColorMode) =
    PreviewHost(mode) { AllSetSample() }

@ScreenPreviews
@Composable
private fun HomePreview(@PreviewParameter(ColorModeProvider::class) mode: ColorMode) =
    PreviewHost(mode) { HomeSample() }

@ScreenPreviews
@Composable
private fun HomePatchesUpdatePreview(@PreviewParameter(ColorModeProvider::class) mode: ColorMode) =
    PreviewHost(mode) { HomeSample(patchesOnly = true) }

@ScreenPreviews
@Composable
private fun HomeRestorePreview(@PreviewParameter(ColorModeProvider::class) mode: ColorMode) =
    PreviewHost(mode) { HomeSample(restoreSheet = true) }

@ScreenPreviews
@Composable
private fun BuildDoneBackupPreview(@PreviewParameter(ColorModeProvider::class) mode: ColorMode) =
    PreviewHost(mode) { BuildDoneSample(backupDue = true) }

@ScreenPreviews
@Composable
private fun SigningKeyPreview(@PreviewParameter(ColorModeProvider::class) mode: ColorMode) =
    PreviewHost(mode) { SigningKeySample() }

@ScreenPreviews
@Composable
private fun SigningKeyEmptyPreview(@PreviewParameter(ColorModeProvider::class) mode: ColorMode) =
    PreviewHost(mode) { SigningKeySample(keyExists = false) }

@ScreenPreviews
@Composable
private fun SigningKeyExportPreview(@PreviewParameter(ColorModeProvider::class) mode: ColorMode) =
    PreviewHost(mode) { SigningKeySample(form = SigningKeyForm.Export, formError = R.string.signing_key_error_short) }

@ScreenPreviews
@Composable
private fun SigningKeyImportConfirmPreview(@PreviewParameter(ColorModeProvider::class) mode: ColorMode) =
    PreviewHost(mode) { SigningKeySample(form = SigningKeyForm.Import, confirmation = true) }
