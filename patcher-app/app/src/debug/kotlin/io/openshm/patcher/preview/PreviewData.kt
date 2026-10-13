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
import io.openshm.patcher.ui.screens.build.KeyBackupStatus
import io.openshm.patcher.ui.screens.build.BuildStage
import io.openshm.patcher.ui.screens.build.BuildUiState
import io.openshm.patcher.ui.screens.build.CurrentApp
import io.openshm.patcher.ui.screens.build.BuiltOutput
import io.openshm.patcher.ui.screens.build.finish
import io.openshm.patcher.ui.screens.build.startStage
import io.openshm.patcher.ui.screens.customize.CustomizeUiState
import io.openshm.patcher.ui.screens.customize.customizeStateFor
import io.openshm.patcher.ui.screens.devmode.DevModeUiState
import io.openshm.patcher.ui.screens.disable.DisableStockUiState
import io.openshm.patcher.ui.screens.disable.StockOutcome
import io.openshm.patcher.ui.screens.disable.StockAppChoice
import io.openshm.patcher.ui.screens.home.HomeStatus
import io.openshm.patcher.ui.screens.home.HomeUiState
import io.openshm.patcher.ui.screens.home.InstalledBuild
import io.openshm.patcher.ui.screens.home.UpdateOffer
import io.openshm.patcher.ui.screens.install.GrantRow
import io.openshm.patcher.data.GrantKind
import io.openshm.patcher.ui.screens.install.InstallItem
import io.openshm.patcher.ui.screens.install.SelfAdbPhase
import io.openshm.patcher.ui.screens.install.SignatureConflict
import io.openshm.patcher.ui.screens.install.AdbOutcome
import io.openshm.patcher.ui.screens.install.InstallUiState
import io.openshm.patcher.device.DetectedWatch
import io.openshm.patcher.device.DeviceLogState
import io.openshm.patcher.device.DeviceLogsState
import io.openshm.patcher.device.DisconnectReason
import io.openshm.patcher.device.LogDevice
import io.openshm.patcher.device.LogLinkStatus
import io.openshm.patcher.ui.screens.devicelogs.DeviceLogsUiState
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
import io.openshm.patcher.ui.screens.wearable.BondedDevice
import io.openshm.patcher.ui.screens.wearable.GALAXY_WEARABLE_PACKAGE
import io.openshm.patcher.ui.screens.wearable.WATCH_PLUGIN_PACKAGES
import io.openshm.patcher.ui.screens.wearable.WearableFacts
import io.openshm.patcher.ui.screens.wearable.wearableState
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

    fun welcome(sheet: Boolean = false, watchReachable: Boolean = false) =
        WelcomeUiState(changesSheetVisible = sheet, changes = buildChanges(PREFIX, WatchProfile.WearOs5), watchReachable = watchReachable)

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

    fun profileNetworkDetected() = ProfileUiState(
        detected = DetectedWatch(36, null, DetectedWatch.Source.Network, "SM-R920"),
    )

    fun profileDenied() = ProfileUiState(pairedCheck = PairedCheck.Denied)

    fun wearable(
        packages: Set<String> = setOf(GALAXY_WEARABLE_PACKAGE, WATCH_PLUGIN_PACKAGES[1]),
        bluetoothAllowed: Boolean = true,
        bonded: List<BondedDevice> = listOf(BondedDevice("Galaxy Watch5 Pro (A1B2)", 0x0700)),
    ) = wearableState(WearableFacts(packages, bluetoothAllowed, bonded))

    fun watchPairEmpty() = WatchPairUiState()

    fun watchPairCorrection() = WatchPairUiState(
        foundAddress = WATCH_ADDRESS,
        guideOpen = false,
        correction = WatchProfileCorrection(WatchProfile.WearOs3Or4, "13"),
    )

    /** Found by discovery: the guide has closed by itself and the code entry shows. */
    fun watchPairReady() = WatchPairUiState(foundAddress = WATCH_ADDRESS, guideOpen = false, code = "123456")

    fun watchPairGuide(step: Int) = WatchPairUiState(guideStep = step)

    fun watchPairManual(host: String = "", port: String = "", code: String = "") =
        WatchPairUiState(guideOpen = false, manual = true, host = host, port = port, code = code)

    fun watchPairPairing() = WatchPairUiState(foundAddress = WATCH_ADDRESS, guideOpen = false, code = "123456", pairing = true)

    fun watchPairReconnecting() = WatchPairUiState(guideOpen = false, reconnecting = true)

    fun watchPairReconnectHint() = WatchPairUiState(guideOpen = false, rememberedNotFound = true)

    fun watchPairReconnected() = WatchPairUiState(paired = true, reconnected = true, reconnectedModel = "SM-R920")

    fun watchPairError() =
        WatchPairUiState(foundAddress = WATCH_ADDRESS, guideOpen = false, code = "123456", errorRes = R.string.watchpair_error_wrong_code)

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
            1 -> "Open the right release" to "Open version 1.5.1.022 of the app."
            2 -> "Pick the variant" to "In the variants table, pick the APK (not BUNDLE) marked nodpi for version 1.5.1.022."
            else -> "Download" to "Tap Download APK. The patcher picks the file up when it finishes."
        }
        return GuidedBrowserUiState(
            host = "www.apkmirror.com",
            appLabel = AppCatalogue.current.input("shm-watch").label,
            coachStep = step,
            coachTotal = 3,
            coachTitle = title,
            coachBody = if (wrongVersion) "That is another version. Go back and open 1.5.1.022." else body,
            target = "1.5.1.022 · APK, not bundle · nodpi · armeabi-v7a",
        )
    }

    fun customize(): CustomizeUiState = customizeStateFor(session)

    /** A running build at output [number] (1-based) of [total]; the log has a few live lines. */
    fun build(stage: BuildStage = BuildStage.Apply, number: Int = 2, total: Int = 5) =
        BuildUiState(
            patchCount = AppCatalogue.current.patternCount(WatchProfile.WearOs5.inputIds),
            running = true,
            overall = ((number - 1) + stage.ordinal / BuildStage.entries.size.toFloat()) / total,
            currentApp = AppCatalogue.current.outputs[(number - 1).coerceIn(0, AppCatalogue.current.outputs.lastIndex)].let {
                CurrentApp(it.id, it.label, it.packageName(PREFIX), it.device, number, total)
            },
            log = listOf(
                "00:41 == ${AppCatalogue.current.outputs[1].id} (2/5) ==",
                "00:41 ok   check-request (0 ms)",
                "00:42 ok   unpack (118 ms)",
                "00:43 ok   detect-family (240 ms)",
                "00:44   + shm-main-gates: 3 patched sites",
                "00:44   + shm-setup-network-gate: 1 patched site",
            ),
        ).startStage(stage)

    /** The same build after the user cancelled it. */
    fun buildCancelled() = build().copy(running = false, cancelled = true)

    fun buildFinished() =
        BuildUiState(patchCount = AppCatalogue.current.patternCount(WatchProfile.WearOs5.inputIds)).finish()

    fun buildDone(backup: KeyBackupStatus = KeyBackupStatus.Hidden) = BuildDoneUiState(
        backup = backup,
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
    fun installPhoneGrants(adb: SelfAdbPhase? = SelfAdbPhase.WaitingForCode) = InstallUiState(
        installItems(Device.Phone, { CheckStatus.Pending }) { CheckStatus.Passed }.map { item ->
            item.copy(grants = item.grants.mapIndexed { n, row -> if (n < 3) row.copy(status = CheckStatus.Passed) else row })
        },
        selfAdb = adb,
    )

    /** The Wireless debugging run ended with [outcome]: the dialog or overlay is up over the list. */
    fun installPhoneAdbOutcome(outcome: AdbOutcome) = installPhoneGrants(adb = null).copy(adbOutcome = outcome)

    /** Health Monitor refused because another key signed the installed copy; optionally with the confirmation open. */
    fun installPhoneConflict(confirming: Boolean = false): InstallUiState {
        val items = installItems(Device.Phone, { CheckStatus.Pending }) { CheckStatus.Pending }
            .mapIndexed { i, item -> if (i == 0) item.copy(status = CheckStatus.Failed) else item }
        return InstallUiState(
            items,
            errorRes = R.string.reinstall_error_phone,
            errorDetail = "INSTALL_FAILED_UPDATE_INCOMPATIBLE",
            conflict = SignatureConflict(items[0].outputId, items[0].packageName, Device.Phone),
            confirmingReinstall = confirming,
        )
    }

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

    fun installWatchUnreachable() = InstallUiState(
        installItems(Device.Watch, { CheckStatus.Pending }) { i -> if (i == 0) CheckStatus.Failed else CheckStatus.Pending },
        errorRes = R.string.watch_error_unreachable,
        errorDetail = "The watch's Wireless debugging service was not found",
    )

    fun devMode() = DevModeUiState()

    fun disableStock() = DisableStockUiState(
        AppCatalogue.current.stockAppsToDisable.filter { it.device == Device.Watch }.map { StockAppChoice(it, selected = true) },
    )

    /** The watch was checked and the stock app is off already. */
    fun disableStockAlreadyOff() = DisableStockUiState(
        AppCatalogue.current.stockAppsToDisable.filter { it.device == Device.Watch }
            .map { StockAppChoice(it, selected = false, outcome = StockOutcome.AlreadyOff) },
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

    fun allSet(watchReachable: Boolean = false) = AllSetUiState(passed = 8, total = 8, watchReachable = watchReachable)

    private val logSamples = listOf(
        "1234  1301 I ActivityManager: Start proc 4821:com.samsung.android.shealthmonitors/u0a123 for service",
        "4821  4821 D ShmBridge: bridge starting, protocol 3",
        "4821  4850 I HealthTracking: connected to the sensor service",
        "4821  4850 W HealthTracking: ECG sensor busy, retrying in 500 ms",
        "4821  4850 I HealthTracking: ECG tracker ready",
        "4821  4821 D AlarmBridge: alarm channel opened",
    )

    /** About 40 threadtime lines, enough to scroll. */
    fun deviceLogLines(count: Int = 40): List<String> =
        List(count) { "10-12 09:14:%02d.%03d  %s".format(it / 3, (it * 137) % 1000, logSamples[it % logSamples.size]) }

    fun deviceLogs(tab: LogDevice = LogDevice.Watch, watchStatus: LogLinkStatus = LogLinkStatus.Streaming) = DeviceLogsUiState(
        logs = DeviceLogsState(
            capturing = true,
            watch = DeviceLogState(
                LogDevice.Watch, watchStatus, lineCount = 1284,
                packages = listOf("com.samsung.android.shealthmonitors", "$PREFIX.shmbridge.watch", "com.samsung.android.wear.shealth"),
                tail = deviceLogLines(), deviceLabel = "SM-L320 · Android 15",
            ),
            phone = DeviceLogState(LogDevice.Phone, LogLinkStatus.Disconnected(DisconnectReason.WirelessDebuggingOff)),
        ),
        tab = tab,
    )

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
