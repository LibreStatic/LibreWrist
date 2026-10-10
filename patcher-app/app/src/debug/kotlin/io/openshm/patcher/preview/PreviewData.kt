package io.openshm.patcher.preview

import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.Device
import io.openshm.patcher.data.SessionState
import io.openshm.patcher.data.WatchProfile
import io.openshm.patcher.ui.components.CheckStatus
import io.openshm.patcher.ui.screens.acquire.AcquireCard
import io.openshm.patcher.ui.screens.acquire.AcquireProgress
import io.openshm.patcher.ui.screens.acquire.AcquireUiState
import io.openshm.patcher.ui.screens.acquire.GuidedBrowserUiState
import io.openshm.patcher.ui.screens.build.BuildDoneUiState
import io.openshm.patcher.ui.screens.build.BuildStage
import io.openshm.patcher.ui.screens.build.BuildUiState
import io.openshm.patcher.ui.screens.build.BuiltOutput
import io.openshm.patcher.ui.screens.build.finish
import io.openshm.patcher.ui.screens.build.startStage
import io.openshm.patcher.ui.screens.customize.CustomizeUiState
import io.openshm.patcher.ui.screens.customize.customizeStateFor
import io.openshm.patcher.ui.screens.devmode.DevModeUiState
import io.openshm.patcher.ui.screens.disable.DisableStockUiState
import io.openshm.patcher.ui.screens.disable.StockAppChoice
import io.openshm.patcher.ui.screens.home.HomeStatus
import io.openshm.patcher.ui.screens.home.HomeUiState
import io.openshm.patcher.ui.screens.home.InstalledBuild
import io.openshm.patcher.ui.screens.home.UpdateOffer
import io.openshm.patcher.ui.screens.install.GrantRow
import io.openshm.patcher.data.GrantKind
import io.openshm.patcher.ui.screens.install.InstallItem
import io.openshm.patcher.ui.screens.install.SelfAdbPhase
import io.openshm.patcher.ui.screens.install.InstallUiState
import io.openshm.patcher.ui.screens.profile.ProfileUiState
import io.openshm.patcher.ui.screens.selftest.AllSetUiState
import io.openshm.patcher.ui.screens.selftest.HumanCheck
import io.openshm.patcher.ui.screens.selftest.SelfTestUiState
import io.openshm.patcher.ui.screens.selftest.selfTestSections
import io.openshm.patcher.ui.screens.watchpair.WatchPairUiState
import io.openshm.patcher.ui.screens.wearable.WearablePairingUiState
import io.openshm.patcher.ui.screens.wearable.initialWearableChecks
import io.openshm.patcher.ui.screens.signingkey.ImportConfirmation
import io.openshm.patcher.ui.screens.signingkey.SigningKeyForm
import io.openshm.patcher.ui.screens.signingkey.SigningKeyUiState
import io.openshm.patcher.ui.screens.welcome.WelcomeUiState
import io.openshm.patcher.ui.screens.welcome.buildChanges
import io.openshm.patcher.R

/**
 * Fake UiState for previews and the Compose Driver. Everything structural (inputs, patches,
 * outputs, grants, stock apps) comes from the bundled catalogue; only the dynamic parts (progress,
 * sizes, the sample prefix and the sample watch address) are invented, and only in this debug source set.
 */
internal object PreviewData {
    /** The same shape the app generates: `io.openshm.u<6 hex>`. */
    const val PREFIX = "io.openshm.u3fa91c"

    /** A documentation-range address (RFC 5737), never a real device. */
    const val WATCH_ADDRESS = "192.0.2.17:37115"

    private const val MB = 1024L * 1024L

    private val session = SessionState(WatchProfile.WearOs5, PREFIX)

    fun welcome(sheet: Boolean = false) =
        WelcomeUiState(changesSheetVisible = sheet, changes = buildChanges(PREFIX, WatchProfile.WearOs5))

    fun profile() = ProfileUiState()

    fun wearable(status: CheckStatus = CheckStatus.Passed) = WearablePairingUiState(initialWearableChecks(status))

    fun watchPairEmpty() = WatchPairUiState()

    fun watchPairReady() = WatchPairUiState(
        foundAddress = WATCH_ADDRESS,
        code = "123456",
        prerequisitesDone = listOf(true, true, true, false),
    )

    fun acquire() = AcquireUiState(
        listOf(
            AcquireCard(AppCatalogue.current.input("shm-phone"), AcquireProgress.Verified(91 * MB)),
            AcquireCard(AppCatalogue.current.input("shm-watch"), AcquireProgress.Downloading(58 * MB, 91 * MB)),
            AcquireCard(AppCatalogue.current.input("alarm-watch")),
        ),
    )

    fun acquireAllVerified() = AcquireUiState(
        AppCatalogue.current.inputs.map { AcquireCard(it, AcquireProgress.Verified(40 * MB)) },
    )

    fun guidedBrowser() = GuidedBrowserUiState(
        host = "www.apkmirror.com",
        appLabel = AppCatalogue.current.input("shm-watch").label,
        coachStep = 2,
        coachTotal = 3,
        coachTitle = "Pick the variant",
        coachBody = "Choose the APK, not the bundle, and the nodpi build for your Android version.",
    )

    fun customize(): CustomizeUiState = customizeStateFor(session)

    fun build(stage: BuildStage = BuildStage.Apply) =
        BuildUiState(patchCount = AppCatalogue.current.patternCount(WatchProfile.WearOs5.inputIds)).startStage(stage)

    fun buildFinished() =
        BuildUiState(patchCount = AppCatalogue.current.patternCount(WatchProfile.WearOs5.inputIds)).finish()

    fun buildDone(backupDue: Boolean = false) = BuildDoneUiState(
        backupDue = backupDue,
        outputs = AppCatalogue.current.outputs.map {
            BuiltOutput(it.id, it.label, it.device, it.packageName(PREFIX), sizeBytes = (30 + it.id.length * 3) * MB)
        },
        patchCount = AppCatalogue.current.patternCount(WatchProfile.WearOs5.inputIds),
    )

    private fun installItems(device: Device, grantStatus: (Int) -> CheckStatus, itemStatus: (Int) -> CheckStatus) =
        AppCatalogue.current.outputs.filter { it.device == device }.mapIndexed { index, out ->
            InstallItem(
                outputId = out.id,
                label = out.label,
                packageName = out.packageName(PREFIX),
                status = itemStatus(index),
                grants = AppCatalogue.current.grants[out.id].orEmpty().map {
                    GrantRow(it, grantStatus(index), manual = device == Device.Phone && it.kind in PHONE_USER_KINDS)
                },
                onboarding = out.userOnboarding,
            )
        }

    private val PHONE_USER_KINDS = setOf(GrantKind.Permission, GrantKind.AppOp)

    /** Health Monitor installed, the bridge being sent. */
    fun installPhone() = InstallUiState(
        installItems(Device.Phone, { CheckStatus.Pending }) { i ->
            if (i == 0) CheckStatus.Passed else CheckStatus.Running
        },
        running = true,
    )

    /** Everything installed; some access granted, the rest waiting for the user, and the Wireless debugging fallback at the code step. */
    fun installPhoneGrants() = InstallUiState(
        installItems(Device.Phone, { CheckStatus.Pending }) { CheckStatus.Passed }.map { item ->
            item.copy(grants = item.grants.mapIndexed { n, row -> if (n < 3) row.copy(status = CheckStatus.Passed) else row })
        },
        selfAdb = SelfAdbPhase.WaitingForCode,
    )

    fun installWatch() = InstallUiState(
        installItems(Device.Watch, { CheckStatus.Pending }) { i ->
            when (i) {
                0 -> CheckStatus.Passed
                1 -> CheckStatus.Running
                else -> CheckStatus.Pending
            }
        },
        running = true,
    )

    fun devMode() = DevModeUiState()

    fun disableStock() = DisableStockUiState(
        AppCatalogue.current.stockAppsToDisable.filter { it.device == Device.Watch }.map { StockAppChoice(it, selected = true) },
    )

    fun selfTest(): SelfTestUiState {
        val sections = selfTestSections(CheckStatus.Passed).mapIndexed { si, section ->
            if (si != 0) section
            else section.copy(
                rows = section.rows.mapIndexed { ri, row -> if (ri == 1) row.copy(status = CheckStatus.Failed) else row },
            )
        }
        return SelfTestUiState(sections, HumanCheck(1, 2, R.string.selftest_human_buzz))
    }

    fun allSet() = AllSetUiState(passed = 8, total = 8)

    fun home(update: Boolean = true, restoreSheet: Boolean = false, menu: Boolean = false) = HomeUiState(
        status = HomeStatus(CheckStatus.Passed, daysAgo = 2),
        update = if (update) UpdateOffer(AppCatalogue.current.input("shm-watch").label, "1.5.1.022", "1.5.2.004") else null,
        builds = AppCatalogue.current.outputs.map { InstalledBuild(it.id, it.label, it.packageName(PREFIX), it.device) },
        menuOpen = menu,
        restoreSheetVisible = restoreSheet,
    )

    private const val SAMPLE_SHA = "dc277d5d75f361b48da0bff95496463c464ad303419d8f19da38a990d8239d99"
    private const val OTHER_SHA = "3b9a0c41e2d57f86a1c4d09be7f2538a64d1e0c97b35f82a46de10b9c78f5a02"

    fun signingKey(
        keyExists: Boolean = true,
        form: SigningKeyForm = SigningKeyForm.None,
        formError: Int? = null,
        backedUp: Boolean = false,
        confirmation: Boolean = false,
    ) = SigningKeyUiState(
        loading = false,
        keyExists = keyExists,
        certificateSha256 = if (keyExists) SAMPLE_SHA else null,
        backedUp = backedUp,
        form = form,
        formError = formError,
        confirmation = if (confirmation) ImportConfirmation(SAMPLE_SHA, OTHER_SHA) else null,
    )
}
