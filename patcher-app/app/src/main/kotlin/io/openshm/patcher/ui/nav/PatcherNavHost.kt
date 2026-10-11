package io.openshm.patcher.ui.nav

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.openshm.patcher.ui.screens.install.InstallEvent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import io.openshm.patcher.ui.theme.LocalReducedMotion
import androidx.core.content.ContextCompat
import io.openshm.patcher.build.BuildStorage
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.openshm.patcher.R
import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.PrefsExportedCertRecord
import io.openshm.patcher.data.signingKeyBackupDue
import io.openshm.patcher.data.signingKeyStore
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.openshm.patcher.ui.screens.signingkey.SigningKeyRequest
import io.openshm.patcher.ui.screens.signingkey.SigningKeyScreen
import io.openshm.patcher.ui.screens.signingkey.SigningKeyViewModel
import io.openshm.patcher.ui.screens.acquire.AcquireEvent
import io.openshm.patcher.ui.screens.acquire.AcquireScreen
import io.openshm.patcher.ui.screens.acquire.AcquireViewModel
import io.openshm.patcher.ui.screens.acquire.GuidedBrowserHost
import io.openshm.patcher.ui.screens.build.BuildDoneScreen
import io.openshm.patcher.ui.screens.build.BuildScreen
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
import io.openshm.patcher.ui.screens.wearable.WearablePairingScreen
import io.openshm.patcher.ui.screens.wearable.WearablePairingViewModel
import io.openshm.patcher.ui.screens.welcome.WelcomeScreen
import io.openshm.patcher.ui.screens.welcome.WelcomeViewModel

@Composable
fun PatcherNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    fun next(from: Route) {
        val target = SetupFlow.next(from) ?: return
        navController.navigate(target.path) {
            // Home is the end of the guided setup: it replaces the whole stack.
            if (target == Route.Home) popUpTo(Route.Welcome.path) { inclusive = true }
        }
    }
    val back: () -> Unit = { navController.popBackStack() }

    val pages = PageTransitions(
        reducedMotion = LocalReducedMotion.current,
        rtl = LocalLayoutDirection.current == LayoutDirection.Rtl,
    )
    NavHost(
        navController,
        startDestination = Route.Welcome.path,
        modifier = modifier,
        enterTransition = { pages.enter(forward = true) },
        exitTransition = { pages.exit(forward = true) },
        popEnterTransition = { pages.enter(forward = false) },
        popExitTransition = { pages.exit(forward = false) },
    ) {
        composable(Route.Welcome.path) {
            val vm: WelcomeViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            WelcomeScreen(state, vm::onEvent, onGetStarted = { next(Route.Welcome) }, onSettings = { navController.navigate(Route.SigningKey.path) })
        }
        composable(Route.Profile.path) {
            val vm: ProfileViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            val context = LocalContext.current
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), vm::onBluetoothPermission)
            ProfileScreen(
                state,
                onEvent = { event ->
                    if (event is ProfileEvent.CheckPairedWatches) {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                            vm.onBluetoothPermission(true)
                        } else {
                            permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        }
                    } else {
                        vm.onEvent(event)
                    }
                },
                onContinue = { next(Route.Profile) }, onBack = back)
        }
        composable(Route.WearablePairing.path) {
            val vm: WearablePairingViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            WearablePairingScreen(state, vm::onEvent, onContinue = { next(Route.WearablePairing) }, onBack = back)
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
        composable(
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
            BuildScreen(state, vm::onEvent, onBack = back)
        }
        composable(Route.BuildDone.path) {
            val context = LocalContext.current
            val base = remember { builtOutputs(BuildStorage(context)) }
            // Decrypting the key is real work, so the nudge appears once it is known.
            val backupDue by produceState(false) {
                value = withContext(Dispatchers.IO) { signingKeyBackupDue(signingKeyStore(context), PrefsExportedCertRecord(context)) }
            }
            BuildDoneScreen(
                base.copy(backupDue = backupDue),
                onInstall = { next(Route.BuildDone) },
                onBack = back,
                onBackUpKey = { navController.navigate(Route.SigningKey.path) },
            )
        }
        composable(Route.InstallPhone.path) {
            val vm: InstallPhoneViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            val activity = LocalContext.current
            LaunchedEffect(vm) {
                vm.launches.collect { request ->
                    try {
                        activity.startActivity(request.intent)
                    } catch (e: ActivityNotFoundException) {
                        request.fallback?.let { runCatching { activity.startActivity(it) } }
                    }
                }
            }
            // Settings are changed outside the app: read them again when the screen is back.
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onEvent(InstallEvent.Refresh) }
            InstallPhoneScreen(state, vm::onEvent, onContinue = { next(Route.InstallPhone) }, onBack = back)
        }
        composable(Route.WatchInstall.path) {
            val vm: InstallWatchViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            WatchInstallScreen(state, vm::onEvent, onContinue = { next(Route.WatchInstall) }, onBack = back)
        }
        composable(Route.DevMode.path) {
            val vm: DevModeViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            DevModeScreen(state, vm::onEvent, onContinue = { next(Route.DevMode) }, onBack = back)
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
        composable(Route.AllSet.path) { entry ->
            val selfTest = remember(entry) { navController.getBackStackEntry(Route.SelfTest.path) }
            val vm: SelfTestViewModel = viewModel(selfTest)
            val state by vm.state.collectAsStateWithLifecycle()
            val context = LocalContext.current
            AllSetScreen(
                AllSetUiState(state.passedCount, state.totalCount),
                onDone = { next(Route.AllSet) },
                onShare = { shareReport(context, vm.reportText()) },
            )
        }
        composable(Route.SigningKey.path) {
            val vm: SigningKeyViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            val createBackup = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument(PKCS12_MIME), vm::onBackupFileCreated,
            )
            val pickKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), vm::onKeyFilePicked)
            LaunchedEffect(vm) {
                vm.requests.collect { request ->
                    when (request) {
                        SigningKeyRequest.CreateBackupFile -> createBackup.launch(BACKUP_FILE_NAME)
                        SigningKeyRequest.PickKeyFile -> pickKey.launch(arrayOf(PKCS12_MIME, "application/octet-stream"))
                    }
                }
            }
            SigningKeyScreen(state, vm::onEvent, onBack = back)
        }
        composable(Route.Home.path) {
            val vm: HomeViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            // The self-test may have finished since Home was last shown.
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
            HomeScreen(
                state,
                onEvent = { event ->
                    vm.onEvent(event)
                    if (event is HomeEvent.NewBuild) navController.navigate(Route.Profile.path)
                    if (event is HomeEvent.RunSelfTest || event is HomeEvent.TestAgain) navController.navigate(Route.SelfTest.path)
                    if (event is HomeEvent.OpenSigningKey) navController.navigate(Route.SigningKey.path)
                    // Inputs whose verified files still match are already Verified on Acquire.
                    if (event is HomeEvent.StartUpdate) navController.navigate(Route.Acquire.path)
                },
            )
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

/** The screen a self-test fix button opens. */
internal fun SelfTestFix.route(): Route = when (this) {
    SelfTestFix.InstallPhone -> Route.InstallPhone
    SelfTestFix.InstallWatch -> Route.WatchInstall
    SelfTestFix.WatchPair -> Route.WatchPair
    SelfTestFix.Build -> Route.Customize
    SelfTestFix.DisableStock -> Route.DisableStock
    SelfTestFix.DevMode -> Route.DevMode
    SelfTestFix.WearablePairing -> Route.WearablePairing
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

private const val PKCS12_MIME = "application/x-pkcs12"
private const val BACKUP_FILE_NAME = "openshm-signing-key.p12"
private const val APK_MIME = "application/vnd.android.package-archive"
