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
import io.openshm.patcher.ui.screens.install.InstallItem
import io.openshm.patcher.ui.screens.install.InstallUiState
import io.openshm.patcher.ui.screens.profile.ProfileUiState
import io.openshm.patcher.ui.screens.selftest.AllSetUiState
import io.openshm.patcher.ui.screens.selftest.HumanCheck
import io.openshm.patcher.ui.screens.selftest.SelfTestUiState
import io.openshm.patcher.ui.screens.selftest.selfTestSections
import io.openshm.patcher.ui.screens.watchpair.WatchPairUiState
import io.openshm.patcher.ui.screens.wearable.WearablePairingUiState
import io.openshm.patcher.ui.screens.wearable.initialWearableChecks
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

    fun buildDone() = BuildDoneUiState(
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
                grants = AppCatalogue.current.grants[out.id].orEmpty().map { GrantRow(it, grantStatus(index)) },
            )
        }

    fun installPhone() = InstallUiState(
        installItems(Device.Phone, { i -> if (i == 0) CheckStatus.Passed else CheckStatus.Pending }) { i ->
            if (i == 0) CheckStatus.Passed else CheckStatus.Running
        },
        running = true,
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
}
