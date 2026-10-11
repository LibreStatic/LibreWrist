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
import io.openshm.patcher.device.DetectedWatch
import io.openshm.patcher.ui.screens.profile.PairedCheck
import io.openshm.patcher.ui.screens.profile.ProfileUiState
import io.openshm.patcher.ui.screens.profile.WatchModelHint
import io.openshm.patcher.ui.screens.watchpair.WatchProfileCorrection
import io.openshm.patcher.ui.screens.selftest.AllSetUiState
import io.openshm.patcher.ui.screens.selftest.SelfTestFix
import io.openshm.patcher.ui.screens.selftest.SelfTestRow
import io.openshm.patcher.ui.screens.selftest.SelfTestSection
import io.openshm.patcher.ui.screens.selftest.SelfTestText
import io.openshm.patcher.ui.screens.selftest.SelfTestUiState
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

    fun profileCertainHint() = ProfileUiState(
        hint = WatchModelHint.Certain("Galaxy Watch7 (A1B2)"),
        pairedCheck = PairedCheck.Done,
    )

    fun profileDependsHint() = ProfileUiState(
        selected = WatchProfile.WearOs3Or4,
        hint = WatchModelHint.Depends("Galaxy Watch6 (A1B2)"),
        pairedCheck = PairedCheck.Done,
    )

    fun profileDetected() = ProfileUiState(
        selected = WatchProfile.WearOs3Or4,
        detected = DetectedWatch(33, "13"),
    )

    fun wearable(status: CheckStatus = CheckStatus.Passed) = WearablePairingUiState(initialWearableChecks(status))

    fun watchPairEmpty() = WatchPairUiState()

    fun watchPairCorrection() = WatchPairUiState(
        foundAddress = WATCH_ADDRESS,
        prerequisitesDone = listOf(true, true, true, true),
        correction = WatchProfileCorrection(WatchProfile.WearOs3Or4, "13"),
    )

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

    fun guidedBrowser(step: Int = 2, wrongVersion: Boolean = false): GuidedBrowserUiState {
        val (title, body) = when (step) {
            1 -> "Open the right release" to "Choose the version named on the card, not a newer or older one."
            2 -> "Pick the variant" to "Choose the APK, not the bundle, and the nodpi build for your Android version."
            else -> "Download" to "Tap Download APK. The patcher picks the file up when it finishes."
        }
        return GuidedBrowserUiState(
            host = "www.apkmirror.com",
            appLabel = AppCatalogue.current.input("shm-watch").label,
            coachStep = step,
            coachTotal = 3,
            coachTitle = title,
            coachBody = if (wrongVersion) "That is another version. Go back and open 1.5.1.022." else body,
        )
    }

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

    private fun t(res: Int, vararg args: String) = SelfTestText(res, args.toList())

    private fun app(id: String) = AppCatalogue.current.output(id).label

    private val humanQuestions = listOf(
        Triple("human-platform", R.string.selftest_watch_platform, R.string.selftest_platform_question),
        Triple("human-watch-connected", R.string.selftest_gms_title, R.string.selftest_gms_question),
        Triple("human-shm", R.string.selftest_shm_title, R.string.selftest_shm_question),
        Triple("human-ecg", R.string.selftest_ecg_title, R.string.selftest_ecg_question),
    )

    private fun human(answered: Int, failedAt: Int? = null) = SelfTestSection(
        R.string.selftest_section_human,
        humanQuestions.mapIndexed { i, (id, title, question) ->
            val status = when {
                i == failedAt -> CheckStatus.Failed
                i < answered -> CheckStatus.Passed
                else -> CheckStatus.Pending
            }
            SelfTestRow(
                id, t(title), status,
                detail = if (i == failedAt) t(R.string.selftest_detail_answered_no) else null,
                fix = if (i == failedAt) SelfTestFix.DevMode else null,
                question = question,
            )
        },
    )

    private fun checkedRow(id: String, title: SelfTestText, detail: SelfTestText, status: CheckStatus = CheckStatus.Passed, fix: SelfTestFix? = null) =
        SelfTestRow(id, title, status, detail, fix)

    /** Every automated check passed and the questions are being answered. */
    fun selfTest(): SelfTestUiState = SelfTestUiState(
        listOf(
            SelfTestSection(
                R.string.selftest_section_phone,
                listOf(
                    checkedRow("phone-pkg-shm-phone", t(R.string.selftest_package_title, app("shm-phone")), t(R.string.selftest_detail_package_ok, "1.5.2.004")),
                    checkedRow("phone-pkg-bridge-phone", t(R.string.selftest_package_title, app("bridge-phone")), t(R.string.selftest_detail_package_ok_no_version)),
                    checkedRow("phone-grants-bridge-phone", t(R.string.selftest_grants_title, app("bridge-phone")), t(R.string.selftest_detail_grants_ok)),
                ),
            ),
            SelfTestSection(
                R.string.selftest_section_watch,
                listOf(
                    checkedRow("watch-pkg-shm-watch", t(R.string.selftest_package_title, app("shm-watch")), t(R.string.selftest_detail_watch_ok)),
                    checkedRow("watch-pkg-bridge-watch", t(R.string.selftest_package_title, app("bridge-watch")), t(R.string.selftest_detail_watch_ok)),
                    checkedRow("watch-grants-bridge-watch", t(R.string.selftest_grants_title, app("bridge-watch")), t(R.string.selftest_detail_grants_ok)),
                    checkedRow("watch-stock", t(R.string.selftest_stock_title, "Samsung Health Monitor"), t(R.string.selftest_detail_stock_off)),
                ),
            ),
            SelfTestSection(
                R.string.selftest_section_link,
                listOf(checkedRow("link-bridge", t(R.string.selftest_bridge_link_title), t(R.string.selftest_detail_bridge_ok))),
            ),
            human(answered = 1),
        ),
    )

    /** A run with failures that have a fix, a warning and a link that is down. */
    fun selfTestFailures(): SelfTestUiState = SelfTestUiState(
        listOf(
            SelfTestSection(
                R.string.selftest_section_phone,
                listOf(
                    checkedRow(
                        "phone-pkg-shm-phone", t(R.string.selftest_package_title, app("shm-phone")),
                        t(R.string.selftest_detail_hash_mismatch), CheckStatus.Failed, SelfTestFix.InstallPhone,
                    ),
                    checkedRow("phone-pkg-bridge-phone", t(R.string.selftest_package_title, app("bridge-phone")), t(R.string.selftest_detail_package_ok, "0.3.0")),
                    checkedRow(
                        "phone-grants-bridge-phone", t(R.string.selftest_grants_title, app("bridge-phone")),
                        t(R.string.selftest_detail_grants_missing, "BridgeNotificationListener, BLUETOOTH_CONNECT"), CheckStatus.Failed, SelfTestFix.InstallPhone,
                    ),
                ),
            ),
            SelfTestSection(
                R.string.selftest_section_watch,
                listOf(
                    checkedRow("watch-pkg-shm-watch", t(R.string.selftest_package_title, app("shm-watch")), t(R.string.selftest_detail_watch_ok)),
                    checkedRow(
                        "watch-grants-shm-watch", t(R.string.selftest_grants_title, app("shm-watch")),
                        t(R.string.selftest_detail_grants_optional, "doze_always_on"), CheckStatus.Warning, SelfTestFix.InstallWatch,
                    ),
                    checkedRow(
                        "watch-stock", t(R.string.selftest_stock_title, "Samsung Health Monitor"),
                        t(R.string.selftest_detail_stock_still_on), CheckStatus.Warning, SelfTestFix.DisableStock,
                    ),
                ),
            ),
            SelfTestSection(
                R.string.selftest_section_link,
                listOf(
                    checkedRow(
                        "link-bridge", t(R.string.selftest_bridge_link_title),
                        t(R.string.selftest_detail_bridge_link_down), CheckStatus.Failed,
                    ),
                ),
            ),
            human(answered = 1, failedAt = 1),
        ),
    )

    /** The watch cannot be reached: every watch row is skipped and points to pairing; one row is still running. */
    fun selfTestWatchOff(): SelfTestUiState {
        val skipped = { id: String, title: SelfTestText ->
            checkedRow(id, title, t(R.string.selftest_detail_watch_skipped), CheckStatus.Warning, SelfTestFix.WatchPair)
        }
        return SelfTestUiState(
            listOf(
                SelfTestSection(
                    R.string.selftest_section_phone,
                    listOf(
                        checkedRow("phone-pkg-shm-phone", t(R.string.selftest_package_title, app("shm-phone")), t(R.string.selftest_detail_package_ok, "1.5.2.004")),
                        SelfTestRow("phone-grants-bridge-phone", t(R.string.selftest_grants_title, app("bridge-phone")), CheckStatus.Running),
                    ),
                ),
                SelfTestSection(
                    R.string.selftest_section_watch,
                    listOf(
                        skipped("watch-pkg-shm-watch", t(R.string.selftest_package_title, app("shm-watch"))),
                        skipped("watch-pkg-bridge-watch", t(R.string.selftest_package_title, app("bridge-watch"))),
                        skipped("watch-stock", t(R.string.selftest_stock_title, "Samsung Health Monitor")),
                    ),
                ),
                human(answered = 0),
            ),
        )
    }

    fun allSet() = AllSetUiState(passed = 8, total = 8)

    fun home(update: Boolean = true, restoreSheet: Boolean = false, menu: Boolean = false, patchesOnly: Boolean = false) = HomeUiState(
        status = HomeStatus(CheckStatus.Passed, daysAgo = 2),
        update = when {
            !update -> null
            patchesOnly -> UpdateOffer(AppCatalogue.current.input("shm-watch").label, "1.5.1.022", "1.5.1.022", patchesOnly = true)
            else -> UpdateOffer(AppCatalogue.current.input("shm-watch").label, "1.5.1.022", "1.5.2.004")
        },
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
