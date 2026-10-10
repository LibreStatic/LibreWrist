package io.openshm.patcher.ui.nav

import androidx.activity.compose.rememberLauncherForActivityResult
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
import io.openshm.patcher.ui.screens.acquire.AcquireEvent
import io.openshm.patcher.ui.screens.acquire.AcquireScreen
import io.openshm.patcher.ui.screens.acquire.AcquireViewModel
import io.openshm.patcher.ui.screens.acquire.GuidedBrowserEvent
import io.openshm.patcher.ui.screens.acquire.GuidedBrowserScreen
import io.openshm.patcher.ui.screens.acquire.GuidedBrowserUiState
import io.openshm.patcher.ui.screens.build.BuildDoneScreen
import io.openshm.patcher.ui.screens.build.BuildScreen
import io.openshm.patcher.ui.screens.build.BuildViewModel
import io.openshm.patcher.ui.screens.build.builtOutputs
import io.openshm.patcher.ui.screens.customize.CustomizeScreen
import io.openshm.patcher.ui.screens.customize.CustomizeViewModel
import io.openshm.patcher.ui.screens.devmode.DevModeScreen
import io.openshm.patcher.ui.screens.devmode.DevModeViewModel
import io.openshm.patcher.ui.screens.disable.DisableStockEvent
import io.openshm.patcher.ui.screens.disable.DisableStockScreen
import io.openshm.patcher.ui.screens.disable.DisableStockViewModel
import io.openshm.patcher.ui.screens.home.HomeEvent
import io.openshm.patcher.ui.screens.home.HomeScreen
import io.openshm.patcher.ui.screens.home.HomeViewModel
import io.openshm.patcher.ui.screens.install.InstallPhoneScreen
import io.openshm.patcher.ui.screens.install.InstallPhoneViewModel
import io.openshm.patcher.ui.screens.install.InstallWatchViewModel
import io.openshm.patcher.ui.screens.install.WatchInstallScreen
import io.openshm.patcher.ui.screens.profile.ProfileScreen
import io.openshm.patcher.ui.screens.profile.ProfileViewModel
import io.openshm.patcher.ui.screens.selftest.AllSetScreen
import io.openshm.patcher.ui.screens.selftest.AllSetUiState
import io.openshm.patcher.ui.screens.selftest.SelfTestScreen
import io.openshm.patcher.ui.screens.selftest.SelfTestViewModel
import io.openshm.patcher.ui.screens.watchpair.WatchPairScreen
import io.openshm.patcher.ui.screens.watchpair.WatchPairViewModel
import io.openshm.patcher.ui.screens.wearable.WearablePairingScreen
import io.openshm.patcher.ui.screens.wearable.WearablePairingViewModel
import io.openshm.patcher.ui.screens.welcome.WelcomeScreen
import io.openshm.patcher.ui.screens.welcome.WelcomeViewModel

private const val BROWSER_HOST = "www.apkmirror.com"
private const val COACH_TOTAL = 3
private const val COACH_VARIANT_STEP = 2

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

    NavHost(navController, startDestination = Route.Welcome.path, modifier = modifier) {
        composable(Route.Welcome.path) {
            val vm: WelcomeViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            WelcomeScreen(state, vm::onEvent, onGetStarted = { next(Route.Welcome) }, onSettings = {})
        }
        composable(Route.Profile.path) {
            val vm: ProfileViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            ProfileScreen(state, vm::onEvent, onContinue = { next(Route.Profile) }, onBack = back)
        }
        composable(Route.WearablePairing.path) {
            val vm: WearablePairingViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            WearablePairingScreen(state, vm::onEvent, onContinue = { next(Route.WearablePairing) }, onBack = back)
        }
        composable(Route.WatchPair.path) {
            val vm: WatchPairViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.paired) { if (state.paired) next(Route.WatchPair) }
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
            LaunchedEffect(finished) { if (finished) next(Route.Build) }
            BuildScreen(state, vm::onEvent, onBack = back)
        }
        composable(Route.BuildDone.path) {
            BuildDoneScreen(remember { builtOutputs() }, onInstall = { next(Route.BuildDone) }, onBack = back)
        }
        composable(Route.InstallPhone.path) {
            val vm: InstallPhoneViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()
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
            DisableStockScreen(
                state,
                onEvent = { event ->
                    vm.onEvent(event)
                    if (event is DisableStockEvent.Disable) next(Route.DisableStock)
                },
                onSkip = { next(Route.DisableStock) },
                onBack = back,
            )
        }
        composable(Route.SelfTest.path) {
            val vm: SelfTestViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            SelfTestScreen(state, vm::onEvent, onContinue = { next(Route.SelfTest) }, onBack = back)
        }
        composable(Route.AllSet.path) {
            val vm: SelfTestViewModel = viewModel(navController.getBackStackEntry(Route.SelfTest.path))
            val state by vm.state.collectAsStateWithLifecycle()
            AllSetScreen(
                AllSetUiState(state.passedCount, state.totalCount),
                onDone = { next(Route.AllSet) },
                onShare = {},
            )
        }
        composable(Route.Home.path) {
            val vm: HomeViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            HomeScreen(
                state,
                onEvent = { event ->
                    vm.onEvent(event)
                    if (event is HomeEvent.NewBuild) navController.navigate(Route.Profile.path)
                    if (event is HomeEvent.RunSelfTest) navController.navigate(Route.SelfTest.path)
                },
            )
        }
    }
}

@Composable
private fun GuidedBrowserDestination(entry: NavBackStackEntry, navController: NavHostController) {
    val inputId = requireNotNull(entry.arguments?.getString(Route.INPUT_ID_ARG))
    val input = AppCatalogue.current.input(inputId)
    val acquire: AcquireViewModel = viewModel(navController.getBackStackEntry(Route.Acquire.path))
    // The coach step follows the WebView's page once the platform layer provides it; the shell shows
    // the variant-picking step, the one people get stuck on.
    val step = COACH_VARIANT_STEP
    GuidedBrowserScreen(
        state = GuidedBrowserUiState(
            host = BROWSER_HOST,
            appLabel = input.label,
            coachStep = step,
            coachTotal = COACH_TOTAL,
            coachTitle = stringResource(coachTitle(step)),
            coachBody = stringResource(coachBody(step)),
        ),
        onEvent = { event ->
            when (event) {
                GuidedBrowserEvent.Close -> navController.popBackStack()
                GuidedBrowserEvent.UseFile -> {
                    navController.popBackStack()
                    acquire.onEvent(AcquireEvent.UseFile(inputId))
                }
                GuidedBrowserEvent.BackToRightPage,
                GuidedBrowserEvent.More,
                GuidedBrowserEvent.OpenInBrowser,
                -> Unit
            }
        },
    )
}

private const val APK_MIME = "application/vnd.android.package-archive"

private fun coachTitle(step: Int) = when (step) {
    1 -> R.string.browser_coach_1_title
    2 -> R.string.browser_coach_2_title
    else -> R.string.browser_coach_3_title
}

private fun coachBody(step: Int) = when (step) {
    1 -> R.string.browser_coach_1_body
    2 -> R.string.browser_coach_2_body
    else -> R.string.browser_coach_3_body
}
