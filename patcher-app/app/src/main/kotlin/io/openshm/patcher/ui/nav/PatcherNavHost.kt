package io.openshm.patcher.ui.nav

import io.openshm.patcher.device.guide.rememberSettingsLauncher
import android.Manifest
import io.openshm.patcher.data.KEY_BACKUP_FOLDER
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat
import io.openshm.patcher.data.PermissionAsks
import android.content.pm.PackageManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.openshm.patcher.ui.screens.install.InstallEvent
import io.openshm.patcher.ui.screens.wearable.TroubleshootEvent
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import io.openshm.patcher.ui.theme.LocalReducedMotion
import androidx.core.content.ContextCompat
import io.openshm.patcher.build.BuildStorage
import io.openshm.patcher.device.AdbPresence
import io.openshm.patcher.device.DeviceLogs
import io.openshm.patcher.device.WatchAnnouncements
import io.openshm.patcher.ui.screens.devicelogs.DeviceLogsEvent
import io.openshm.patcher.ui.screens.devicelogs.DeviceLogsScreen
import io.openshm.patcher.ui.screens.devicelogs.DeviceLogsViewModel
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import io.openshm.patcher.ui.components.AutoAdvanceController
import io.openshm.patcher.ui.components.LocalAutoAdvance
import io.openshm.patcher.ui.components.observeUserInput
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.openshm.patcher.R
import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.Automation
import io.openshm.patcher.data.InputStore
import io.openshm.patcher.data.RebuildScope
import io.openshm.patcher.data.SetupSession
import io.openshm.patcher.ui.screens.phoneadb.PhoneAdbScreen
import io.openshm.patcher.ui.screens.phoneadb.PhoneAdbViewModel
import io.openshm.patcher.data.requestedRebuildScope
import io.openshm.patcher.ui.screens.settings.SettingsScreen
import io.openshm.patcher.ui.screens.settings.SettingsViewModel
import io.openshm.patcher.ui.update.AppUpdateViewModel
import androidx.activity.ComponentActivity
import io.openshm.patcher.ui.screens.signingkey.SigningKeyRequest
import io.openshm.patcher.ui.screens.signingkey.SigningKeyScreen
import io.openshm.patcher.ui.screens.signingkey.SigningKeyViewModel
import io.openshm.patcher.ui.screens.acquire.AcquireEvent
import io.openshm.patcher.ui.screens.acquire.AcquireScreen
import io.openshm.patcher.ui.screens.acquire.AcquireViewModel
import io.openshm.patcher.ui.screens.acquire.GuidedBrowserHost
import io.openshm.patcher.data.CredentialManagerPasswords
import io.openshm.patcher.ui.screens.build.BuildDoneScreen
import io.openshm.patcher.ui.screens.build.KeyBackupViewModel
import io.openshm.patcher.ui.screens.build.BuildEvent
import io.openshm.patcher.ui.screens.build.BuildScreen
import io.openshm.patcher.ui.screens.build.LocalAppIconLoader
import io.openshm.patcher.ui.screens.build.BuildViewModel
import io.openshm.patcher.ui.screens.build.builtOutputs
import io.openshm.patcher.ui.screens.customize.CustomizeScreen
import io.openshm.patcher.ui.screens.customize.CustomizeViewModel
import io.openshm.patcher.ui.screens.devmode.DevModeScreen
import io.openshm.patcher.ui.screens.devmode.DevModeViewModel
import io.openshm.patcher.ui.screens.disable.DisableStockScreen
import io.openshm.patcher.ui.screens.disable.DisableStockViewModel
import io.openshm.patcher.ui.screens.home.HomeEvent
import io.openshm.patcher.ui.screens.home.HomeScreen
import io.openshm.patcher.ui.screens.home.HomeViewModel
import io.openshm.patcher.ui.screens.install.InstallPhoneScreen
import io.openshm.patcher.ui.screens.install.InstallPhoneViewModel
import io.openshm.patcher.ui.screens.install.InstallWatchViewModel
import io.openshm.patcher.ui.screens.install.WatchInstallScreen
import io.openshm.patcher.ui.screens.profile.ProfileEvent
import io.openshm.patcher.ui.screens.profile.ProfileScreen
import io.openshm.patcher.ui.screens.profile.ProfileViewModel
import io.openshm.patcher.ui.screens.selftest.AllSetScreen
import io.openshm.patcher.ui.screens.selftest.AllSetUiState
import io.openshm.patcher.ui.screens.selftest.SelfTestEvent
import io.openshm.patcher.ui.screens.selftest.SelfTestFix
import io.openshm.patcher.ui.screens.selftest.SelfTestScreen
import io.openshm.patcher.ui.screens.selftest.SelfTestViewModel
import io.openshm.patcher.ui.screens.watchpair.WatchPairScreen
import io.openshm.patcher.ui.screens.watchpair.WatchPairViewModel
import io.openshm.patcher.ui.screens.wearable.WearablePairingEvent
import io.openshm.patcher.ui.screens.wearable.WearablePairingScreen
import io.openshm.patcher.ui.screens.wearable.WearablePairingViewModel
import io.openshm.patcher.ui.screens.wearable.WearableTroubleshootScreen
import io.openshm.patcher.ui.screens.wearable.WearableTroubleshootViewModel
import io.openshm.patcher.ui.screens.revert.RevertScope
import io.openshm.patcher.ui.screens.revert.RevertScreen
import io.openshm.patcher.ui.screens.revert.RevertViewModel
import io.openshm.patcher.ui.screens.welcome.WelcomeEvent
import io.openshm.patcher.ui.screens.welcome.WelcomeScreen
import io.openshm.patcher.ui.screens.welcome.WelcomeViewModel
import io.openshm.patcher.ui.screens.welcome.SkipStepsOverlay
import io.openshm.patcher.ui.screens.welcome.SkipDialog
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import io.openshm.patcher.ui.components.ContentColumn
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavGraphBuilder
import androidx.compose.ui.Alignment
import io.openshm.patcher.ui.components.UnattendedRunOverlay
import io.openshm.patcher.ui.components.rememberUnattendedPhase
import io.openshm.patcher.ui.components.unattendedSaturation

/**
 * A destination for a screen without [io.openshm.patcher.ui.components.StepScreenScaffold]: its
 * content is capped and centered ([ContentColumn]). Scaffold screens cap themselves.
 */
private fun NavGraphBuilder.columnComposable(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit,
) = composable(route, arguments) { entry -> ContentColumn { content(entry) } }

@Composable
fun PatcherNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    fun go(target: Route, leaving: Route? = null) = navController.navigate(target.path) {
        // A step that is walked once leaves no entry: Back from the next one skips it. The phone's own ADB
        // pairing, and the build: a finished build forwards at once, so Back onto it would bounce forward again.
        if (leaving == Route.PhoneAdb || leaving == Route.Build) popUpTo(leaving.path) { inclusive = true }
        // Home is the end of the guided setup: it replaces the whole stack. Welcome may already be gone
        // (a returning user lands on Home from it, and a rebuild starts on Home), so pop the whole graph.
        if (target == Route.Home) popUpTo(navController.graph.id) { inclusive = true }
    }
    // Routes passed over silently: the announced skips plus, during a per-app rebuild, what it does not need.
    fun passedOver(): Set<Route> {
        val session = SetupSession.state.value
        val scope = RebuildScope.scope?.minus(session.restoredOutputs)
        val catalogue = AppCatalogue.current
        val devMode = catalogue.platform.healthPlatform.requiredBy
        return StepSkips.routes + rebuildSkippedRoutes(catalogue.outputs, scope, devMode, catalogue.stockAppsToDisable) +
            automationSkippedRoutes(Automation.state.value.unattended) +
            selectionSkippedRoutes(session.outputIds, devMode, catalogue.stockAppsToDisable)
    }
    // A run of skipped steps right after the current one, announced before it is passed over. The
    // welcome screen announces its own run (together with the watch check), so it never lands here.
    // Unattended mode passes over them without announcing: they only list steps that are already done.
    var announcing by remember { mutableStateOf<Pair<Route, List<SkippedStep>>?>(null) }
    fun next(from: Route) {
        val run = if (from == Route.Welcome || from == Route.PhoneAdb) emptyList() else skippedRunAfter(from, StepSkips.current)
        if (run.isNotEmpty() && !Automation.state.value.unattended) {
            announcing = from to run
            return
        }
        go(SetupFlow.next(from, passedOver()) ?: return, leaving = from)
    }
    val back: () -> Unit = { navController.popBackStack() }
    // The device logs notification opens the app on its screen.
    val pendingRoute by PendingRoute.route.collectAsStateWithLifecycle()
    LaunchedEffect(pendingRoute) {
        val target = pendingRoute ?: return@LaunchedEffect
        PendingRoute.consume()
        navController.navigate(target.path) { launchSingleTop = true }
    }

    val pages = PageTransitions(
        reducedMotion = LocalReducedMotion.current,
        rtl = LocalLayoutDirection.current == LayoutDirection.Rtl,
    )
    // Screens cap and center their own column; the skip overlay covers the whole window.
    val autoAdvance = remember { AutoAdvanceController() }
    val currentEntry by navController.currentBackStackEntryAsState()
    // Before the destinations compose, so a new step starts unpaused. The entry id also changes when
    // the same route is entered again.
    autoAdvance.onRouteChanged(currentEntry?.id)
    val unattendedPhase = rememberUnattendedPhase(currentEntry?.destination?.route)
    CompositionLocalProvider(LocalAutoAdvance provides autoAdvance) {
    Box(Modifier.fillMaxSize().observeUserInput(autoAdvance), contentAlignment = Alignment.TopCenter) {
        NavHost(
            navController,
            startDestination = Route.Welcome.path,
            modifier = modifier.unattendedSaturation(unattendedPhase),
            enterTransition = { pages.enter(forward = true) },
            exitTransition = { pages.exit(forward = true) },
            popEnterTransition = { pages.enter(forward = false) },
            popExitTransition = { pages.exit(forward = false) },
        ) {
            composable(Route.Welcome.path) {
                val vm: WelcomeViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                LaunchedEffect(vm) { vm.go.collect { next(Route.Welcome) } }
                // Listen for the watch's Wi-Fi announcement while shown, so the steps it settles can be passed over.
                val welcomeContext = LocalContext.current
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    WatchAnnouncements.start(welcomeContext, "welcome")
                    vm.refreshLogsLink()
                }
                LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
                    WatchAnnouncements.stop("welcome")
                }
                WelcomeScreen(
                    state, vm::onEvent,
                    onGetStarted = { vm.onEvent(WelcomeEvent.GetStarted) },
                    onSettings = { navController.navigate(Route.Settings.path) },
                    onDeviceLogs = { navController.navigate(Route.DeviceLogs.path) },
                    onRevert = { navController.navigate(Route.Revert.path) },
                )
            }
            composable(Route.PhoneAdb.path) {
                val vm: PhoneAdbViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                val context = LocalContext.current
                val launchSettings = rememberSettingsLauncher()
                LaunchedEffect(vm) {
                    vm.launches.collect { launchSettings(it) }
                }
                val whenNotificationsAllowed = rememberNotificationGate()
                // The step starts by itself on entering (a paired phone connects with no code).
                LaunchedEffect(vm) { whenNotificationsAllowed { vm.begin() } }
                LaunchedEffect(state.done) { if (state.done) next(Route.PhoneAdb) }
                PhoneAdbScreen(
                    state,
                    onConnect = { whenNotificationsAllowed { vm.begin() } },
                    onSubmitCode = vm::submitCode,
                    onCancel = vm::cancel,
                    onOpenWirelessDebugging = vm::openWirelessDebugging,
                    onBack = back,
                )
            }
            composable(Route.Profile.path) {
                val vm: ProfileViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                val context = LocalContext.current
                val activity = LocalActivity.current
                val asks = remember { PermissionAsks(context) }
                val bluetooth = Manifest.permission.BLUETOOTH_CONNECT
                fun granted() = ContextCompat.checkSelfPermission(context, bluetooth) == PackageManager.PERMISSION_GRANTED
                val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), vm::onBluetoothPermission)
                fun request() {
                    asks.markAsked(bluetooth)
                    permission.launch(bluetooth)
                }
                // Denied for good (the system shows no dialog any more): only the app settings can undo it.
                fun allow() {
                    val permanentlyDenied = asks.hasAsked(bluetooth) && activity != null &&
                        !ActivityCompat.shouldShowRequestPermissionRationale(activity, bluetooth)
                    if (!permanentlyDenied) return request()
                    val settings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    try {
                        context.startActivity(settings)
                    } catch (e: ActivityNotFoundException) {
                        request()
                    }
                }
                // Silent check on entering and when coming back from the permission dialog or the settings.
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    if (granted()) vm.onBluetoothPermission(true)
                    WatchAnnouncements.start(context, "profile")
                }
                LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
                    WatchAnnouncements.stop("profile")
                    vm.cancelAdvance()
                }
                // The watch told its version while this screen was open: move on once it was readable.
                LaunchedEffect(vm) { vm.advance.collect { next(Route.Profile) } }
                // Asked on its own once ever, and only while the watch has not already told its version.
                LaunchedEffect(vm) {
                    if (!granted() && !asks.hasAsked(bluetooth) && vm.state.value.detected == null) request()
                }
                ProfileScreen(
                    state,
                    onEvent = { event ->
                        when (event) {
                            ProfileEvent.CheckPairedWatches, ProfileEvent.AllowBluetooth ->
                                if (granted()) vm.onBluetoothPermission(true) else allow()
                            else -> vm.onEvent(event)
                        }
                    },
                    onContinue = { next(Route.Profile) }, onBack = back)
            }
            composable(Route.WearablePairing.path) {
                val vm: WearablePairingViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.recheck() }
                // Coming back from Galaxy Wearable (or the permission dialog): look again.
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.recheck() }
                WearablePairingScreen(
                    state,
                    onEvent = { event ->
                        when (event) {
                            WearablePairingEvent.AllowBluetooth -> permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                            WearablePairingEvent.Troubleshoot -> navController.navigate(Route.WearableTroubleshoot.path)
                            else -> vm.onEvent(event)
                        }
                    },
                    onContinue = { next(Route.WearablePairing) },
                    onBack = back,
                )
            }
            composable(Route.WearableTroubleshoot.path) {
                val vm: WearableTroubleshootViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                val context = LocalContext.current
                val launchSettings = rememberSettingsLauncher()
                LaunchedEffect(vm) {
                    vm.launches.collect { launchSettings(it) }
                }
                val whenNotificationsAllowed = rememberNotificationGate()
                WearableTroubleshootScreen(
                    state,
                    onEvent = { event ->
                        when (event) {
                            TroubleshootEvent.PairAdb, TroubleshootEvent.ClearConfirmed -> whenNotificationsAllowed { vm.onEvent(event) }
                            else -> vm.onEvent(event)
                        }
                    },
                    onBack = back,
                )
            }
            composable(Route.WatchPair.path) {
                val vm: WatchPairViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                LaunchedEffect(state.paired) {
                    if (state.paired) {
                        vm.onNavigated()
                        // Opened from the self-test to fix a missing link: go back there instead of walking on.
                        if (navController.previousBackStackEntry?.destination?.route == Route.SelfTest.path) navController.popBackStack()
                        else next(Route.WatchPair)
                    }
                }
                WatchPairScreen(state, vm::onEvent, onBack = back)
            }
            composable(Route.Acquire.path) {
                val vm: AcquireViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), vm::onFilePicked)
                LaunchedEffect(vm) {
                    vm.pickRequests.collect { picker.launch(arrayOf(APK_MIME, "application/octet-stream")) }
                }
                AcquireScreen(
                    state,
                    onEvent = { event ->
                        if (event is AcquireEvent.OpenMirror) navController.navigate(Route.guidedBrowser(event.inputId))
                        else vm.onEvent(event)
                    },
                    onContinue = { next(Route.Acquire) },
                    onBack = back,
                )
            }
            columnComposable(
                Route.GuidedBrowser.path,
                arguments = listOf(navArgument(Route.INPUT_ID_ARG) { type = NavType.StringType }),
            ) { entry -> GuidedBrowserDestination(entry, navController) }
            composable(Route.Customize.path) {
                val vm: CustomizeViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                CustomizeScreen(state, vm::onEvent, onBuild = { next(Route.Customize) }, onBack = back)
            }
            composable(Route.Build.path) {
                val vm: BuildViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                val finished by vm.finished.collectAsStateWithLifecycle()
                val context = LocalContext.current
                var asked by rememberSaveable { mutableStateOf(false) }
                // The build runs without the notification, so the answer only decides whether progress shows there.
                val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.start() }
                LaunchedEffect(Unit) {
                    val needsAsk = Build.VERSION.SDK_INT >= 33 && !asked &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    asked = true
                    if (needsAsk) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.start()
                }
                LaunchedEffect(finished) { if (finished) next(Route.Build) }
                CompositionLocalProvider(LocalAppIconLoader provides vm.icons) {
                    BuildScreen(
                        state, vm::onEvent,
                        onBack = {
                            // Leaving a cancelled build forgets it, so coming back starts a new one.
                            if (state.cancelled) vm.onEvent(BuildEvent.Dismiss)
                            back()
                        },
                    )
                }
            }
            composable(Route.BuildDone.path) {
                val context = LocalContext.current
                val base = remember { builtOutputs(BuildStorage(context)) }
                // The automatic key backup needs the Activity for the password manager prompt.
                val backupVm: KeyBackupViewModel = viewModel()
                val scope = rememberCoroutineScope()
                val backup by backupVm.status.collectAsStateWithLifecycle()
                val activity = LocalActivity.current
                LaunchedEffect(backupVm) { activity?.let { backupVm.run(it) } }
                BuildDoneScreen(
                    base.copy(backup = backup),
                    onInstall = { next(Route.BuildDone) },
                    onBack = back,
                    onBackUpKey = { navController.navigate(Route.SigningKey.path) },
                    onSaveWithPassword = backupVm::saveWithPassword,
                    onSaveBackup = { activity?.let { a -> scope.launch { backupVm.run(a, force = true) } } },
                )
            }
            composable(Route.InstallPhone.path) {
                val vm: InstallPhoneViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                val activity = LocalContext.current
                val launchSettings = rememberSettingsLauncher()
                LaunchedEffect(vm) {
                    vm.launches.collect { launchSettings(it) }
                }
                // Settings are changed outside the app: read them again when the screen is back.
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onEvent(InstallEvent.Refresh) }
                val whenNotificationsAllowed = rememberNotificationGate()
                InstallPhoneScreen(
                    state,
                    onEvent = { event ->
                        if (event == InstallEvent.GrantWithAdb) whenNotificationsAllowed { vm.onEvent(event) } else vm.onEvent(event)
                    },
                    onContinue = { next(Route.InstallPhone) },
                    onBack = back,
                )
            }
            composable(Route.WatchInstall.path) {
                val vm: InstallWatchViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                WatchInstallScreen(state, vm::onEvent, onContinue = { next(Route.WatchInstall) }, onBack = back)
            }
            composable(Route.DevMode.path) {
                val vm: DevModeViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                LifecycleStartEffect(vm) {
                    vm.startWatching()
                    onStopOrDispose { vm.stopWatching() }
                }
                DevModeScreen(
                    state,
                    vm::onEvent,
                    onContinue = {
                        vm.onContinue()
                        next(Route.DevMode)
                    },
                    onBack = back,
                )
            }
            composable(Route.DisableStock.path) {
                val vm: DisableStockViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                LaunchedEffect(state.done) {
                    if (state.done) {
                        vm.onNavigated()
                        next(Route.DisableStock)
                    }
                }
                DisableStockScreen(
                    state,
                    onEvent = vm::onEvent,
                    onSkip = { next(Route.DisableStock) },
                    onBack = back,
                )
            }
            composable(Route.SelfTest.path) {
                val vm: SelfTestViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                val context = LocalContext.current
                // After a fix the checks run again when this screen is back in front.
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResumed() }
                SelfTestScreen(
                    state,
                    onEvent = { event ->
                        when (event) {
                            is SelfTestEvent.Fix -> vm.fixFor(event.rowId)?.let { navController.navigate(it.route().path) }
                            SelfTestEvent.Share -> shareReport(context, vm.reportText())
                            else -> vm.onEvent(event)
                        }
                    },
                    onContinue = { next(Route.SelfTest) },
                    onBack = back,
                )
            }
            columnComposable(Route.AllSet.path) { entry ->
                val selfTest = remember(entry) { navController.getBackStackEntry(Route.SelfTest.path) }
                val vm: SelfTestViewModel = viewModel(selfTest)
                val state by vm.state.collectAsStateWithLifecycle()
                val context = LocalContext.current
                // The device logs link shows once a device was paired; the logs screen connects and says what is missing.
                AllSetScreen(
                    AllSetUiState(state.passedCount, state.totalCount, logsAvailable = AdbPresence.hasEverPaired(context)),
                    onDone = { next(Route.AllSet) },
                    onShare = { shareReport(context, vm.reportText()) },
                    onDeviceLogs = { navController.navigate(Route.DeviceLogs.path) },
                    onRevert = { navController.navigate(Route.Revert.path) },
                )
            }
            columnComposable(
                Route.REVERT_PATTERN,
                arguments = listOf(
                    navArgument(Route.REVERT_SCOPE_ARG) { type = NavType.StringType; defaultValue = RevertScope.All.encoded },
                    navArgument(Route.REVERT_CONFIRMED_ARG) { type = NavType.StringType; defaultValue = "false" },
                ),
            ) {
                val vm: RevertViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                val context = LocalContext.current
                // The system's removal confirmations must be started from the activity.
                val launchSettings = rememberSettingsLauncher()
                LaunchedEffect(vm) {
                    vm.launches.collect { launchSettings(it) }
                }
                RevertScreen(
                    state,
                    onEvent = vm::onEvent,
                    onBack = back,
                    // A scoped run goes back to Home, unless it forgot the whole setup. Otherwise back to
                    // Welcome; from Home (which replaced the stack) it is opened fresh, as Home's builds
                    // may be gone now.
                    onClose = {
                        if (state.scope != RevertScope.All && !state.setupForgotten) {
                            navController.popBackStack(Route.Home.path, false)
                        } else if (!navController.popBackStack(Route.Welcome.path, false)) {
                            navController.navigate(Route.Welcome.path) { popUpTo(navController.graph.id) { inclusive = true } }
                        }
                    },
                )
            }
            columnComposable(Route.SigningKey.path) {
                val vm: SigningKeyViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                val createBackup = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument(PKCS12_MIME), vm::onBackupFileCreated,
                )
                val createLocal = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument(PKCS12_MIME), vm::onLocalBackupFileCreated,
                )
                val pickKey = rememberLauncherForActivityResult(OpenKeyDocument(), vm::onKeyFilePicked)
                LaunchedEffect(vm) {
                    vm.requests.collect { request ->
                        when (request) {
                            SigningKeyRequest.CreateBackupFile -> createBackup.launch(BACKUP_FILE_NAME)
                            SigningKeyRequest.CreateLocalBackupFile -> createLocal.launch(vm.state.value.localBackup ?: BACKUP_FILE_NAME)
                            SigningKeyRequest.PickKeyFile -> pickKey.launch(arrayOf(PKCS12_MIME, "application/octet-stream"))
                        }
                    }
                }
                val activity = LocalActivity.current
                SigningKeyScreen(
                    state, vm::onEvent, onBack = back,
                    onPickSavedPassword = { activity?.let { CredentialManagerPasswords(it).pick() } },
                    onRevealLocalPassword = vm::localPassword,
                    onSaveWithPassword = vm::saveWithPassword,
                )
            }
            columnComposable(Route.Settings.path) {
                val vm: SettingsViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                // The update dialog and its checks live in the activity-scoped view model.
                val appUpdate: AppUpdateViewModel = viewModel(LocalActivity.current as ComponentActivity)
                val checking by appUpdate.checking.collectAsStateWithLifecycle()
                val notice by appUpdate.notice.collectAsStateWithLifecycle()
                SettingsScreen(
                    state = state.copy(checking = checking),
                    notice = notice,
                    onNoticeShown = appUpdate::noticeShown,
                    onAutoCheck = vm::setAutoCheck,
                    onAdvanceSeconds = vm::setAdvanceSeconds,
                    onCheckNow = appUpdate::checkNow,
                    onSigningKey = { navController.navigate(Route.SigningKey.path) },
                    onBack = back,
                )
            }
            columnComposable(Route.DeviceLogs.path) {
                val vm: DeviceLogsViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                val context = LocalContext.current
                val createDocument = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument(TEXT_MIME), vm::export,
                )
                val launchSettings = rememberSettingsLauncher()
                LaunchedEffect(vm) {
                    vm.launches.collect { launchSettings(it) }
                }
                // The pairing code is typed into a notification.
                val whenNotificationsAllowed = rememberNotificationGate()
                DeviceLogsScreen(
                    state,
                    onEvent = { event ->
                        when (event) {
                            DeviceLogsEvent.Export -> createDocument.launch(DeviceLogs.exportFileName())
                            DeviceLogsEvent.OpenWatchPair -> {
                                vm.onEvent(event)
                                navController.navigate(Route.WatchPair.path)
                            }
                            DeviceLogsEvent.PairPhone -> whenNotificationsAllowed { vm.onEvent(event) }
                            else -> vm.onEvent(event)
                        }
                    },
                    onBack = back,
                )
            }
            columnComposable(Route.Home.path) {
                val vm: HomeViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                val context = LocalContext.current
                // The self-test may have finished since Home was last shown.
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
                HomeScreen(
                    state,
                    onEvent = { event ->
                        // The sheet's target is cleared by the reducer: read it first.
                        val restoring = state.restoreTarget
                        vm.onEvent(event)
                        if (event is HomeEvent.ConfirmRestore && restoring != null) {
                            navController.navigate(Route.revert(RevertScope.Output(restoring.id), confirmed = true))
                        }
                        if (event is HomeEvent.RestoreWatch) navController.navigate(Route.revert(RevertScope.Watch))
                        if (event is HomeEvent.NewBuild) navController.navigate(Route.Profile.path)
                        if (event is HomeEvent.RunSelfTest || event is HomeEvent.TestAgain) navController.navigate(Route.SelfTest.path)
                        if (event is HomeEvent.OpenSettings) navController.navigate(Route.Settings.path)
                        if (event is HomeEvent.Uninstall) navController.navigate(Route.Revert.path)
                        // Inputs whose verified files still match are already Verified on Acquire.
                        if (event is HomeEvent.StartUpdate) navController.navigate(Route.Acquire.path)
                        if (event is HomeEvent.Rebuild) {
                            rebuildStartRoute(event.buildId, InputStore(context))?.let { navController.navigate(it.path) }
                        }
                    },
                )
            }
        }
        SkipStepsOverlay(
            announcing?.let { SkipDialog.Skipping(it.second) },
            onConfirm = {
                val from = announcing?.first
                announcing = null
                if (from != null) SetupFlow.next(from, passedOver())?.let { go(it, leaving = from) }
            },
            onCancel = { announcing = null },
            onManual = {
                // Walk the announced steps after all; later skips still apply.
                val (from, run) = announcing ?: return@SkipStepsOverlay
                announcing = null
                val manual = run.mapTo(mutableSetOf()) { it.route }
                if (Route.Profile in manual) StepSkips.manual = true
                StepSkips.current = StepSkips.current.filter { it.route !in manual }
                SetupFlow.next(from, automationSkippedRoutes(Automation.state.value.unattended))?.let { go(it, leaving = from) }
            },
        )
        UnattendedRunOverlay(unattendedPhase)
    }
    }
}

@Composable
private fun GuidedBrowserDestination(entry: NavBackStackEntry, navController: NavHostController) {
    val inputId = requireNotNull(entry.arguments?.getString(Route.INPUT_ID_ARG))
    val input = AppCatalogue.current.input(inputId)
    val acquireEntry = remember(entry) { navController.getBackStackEntry(Route.Acquire.path) }
    val acquire: AcquireViewModel = viewModel(acquireEntry)
    GuidedBrowserHost(
        input = input,
        onDownload = { request ->
            acquire.downloadFrom(inputId, request)
            navController.popBackStack()
        },
        onUseFile = {
            navController.popBackStack()
            acquire.onEvent(AcquireEvent.UseFile(inputId))
        },
        onClose = { navController.popBackStack() },
    )
}

/**
 * Starts the per-app rebuild of [outputId] and returns where it begins: Acquire when its input is not
 * verified, else Customize (its patches), or Build for a bridge, which has nothing to customize. The
 * scope is every session output built from the same input, restored ones included: their builds must
 * match the patches too, and the install steps leave them out again.
 */
private fun rebuildStartRoute(outputId: String, inputs: InputStore): Route? {
    val catalogue = AppCatalogue.current
    val session = SetupSession.state.value
    if (catalogue.outputs.none { it.id == outputId }) return null
    RebuildScope.begin(requestedRebuildScope(catalogue.outputs, outputId, session.outputIds))
    val inputId = catalogue.output(outputId).inputId ?: return Route.Build
    return if (inputs.verifiedSize(catalogue.input(inputId)) == null) Route.Acquire else Route.Customize
}

/** The screen a self-test fix button opens. */
internal fun SelfTestFix.route(): Route = when (this) {
    SelfTestFix.InstallPhone -> Route.InstallPhone
    SelfTestFix.InstallWatch -> Route.WatchInstall
    SelfTestFix.WatchPair -> Route.WatchPair
    SelfTestFix.Build -> Route.Customize
    SelfTestFix.DisableStock -> Route.DisableStock
    SelfTestFix.DevMode -> Route.DevMode
    SelfTestFix.WearablePairing -> Route.WearablePairing
    SelfTestFix.WatchGrants -> Route.WatchInstall // applied in place by the self-test; not navigated to
}

/** Offers the self-test report (plain text) to any app that takes it. */
private fun shareReport(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.selftest_share_subject))
        .putExtra(Intent.EXTRA_TEXT, text)
    try {
        context.startActivity(Intent.createChooser(send, context.getString(R.string.action_share_report)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        // No app takes text: nothing to do.
    }
}

private const val TEXT_MIME = "text/plain"
private const val PKCS12_MIME = "application/x-pkcs12"
private const val BACKUP_FILE_NAME = "openshm-signing-key.p12"
private const val APK_MIME = "application/vnd.android.package-archive"

/** The system document picker opened in Documents/OpenSHM, where the automatic backups are written. */
private class OpenKeyDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(
            android.provider.DocumentsContract.EXTRA_INITIAL_URI,
            android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$KEY_BACKUP_FOLDER"),
        )
}

/**
 * Runs an action once notifications may be posted, asking first on Android 13+: the self-ADB pairing
 * code is typed into a notification. A refusal still runs it, so the attempt fails with its own message.
 */
@Composable
private fun rememberNotificationGate(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.invoke()
        pending = null
    }
    return { action ->
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) {
            action()
        } else {
            pending = action
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
