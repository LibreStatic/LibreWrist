package io.openshm.patcher.preview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.openshm.patcher.device.LaunchRequest
import io.openshm.patcher.device.PhoneSettings
import io.openshm.patcher.device.SettingsTarget
import io.openshm.patcher.device.guide.rememberSettingsLauncher
import io.openshm.patcher.ui.screens.phoneadb.PhoneAdbViewModel
import io.openshm.patcher.ui.screens.wearable.playServicesSettings
import io.openshm.patcher.ui.theme.PatcherTheme

/**
 * Debug-only: starts each guided Settings hand-off through the real launcher, so Tic can be tried on
 * an emulator without walking the setup up to the screen that needs it.
 * `adb shell am start -n io.openshm.patcher/.preview.GuideLabActivity --es request wireless` starts one directly;
 * `pairing` runs the real Wireless debugging pairing of the setup step, so the code Tic reads is used.
 */
class GuideLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The permission pages only list apps that declare the permission; `--es pkg` points them at one that does.
        val pkg = intent.getStringExtra("pkg") ?: packageName
        val requests: List<Pair<String, LaunchRequest>> = listOf(
            "wireless" to PhoneSettings.wirelessDebugging(),
            "overlay" to PhoneSettings.intentFor(SettingsTarget.Overlay, pkg, null),
            "battery" to PhoneSettings.intentFor(SettingsTarget.Battery, pkg, null),
            "unknown" to PhoneSettings.unknownSources(this),
            "gms" to playServicesSettings(),
        )
        val direct = intent.getStringExtra("request").takeIf { savedInstanceState == null }
        setContent {
            PatcherTheme {
                Surface(Modifier.fillMaxSize()) {
                    val launch = rememberSettingsLauncher()
                    val adb: PhoneAdbViewModel = viewModel()
                    val adbState by adb.state.collectAsState()
                    LaunchedEffect(adb) { adb.launches.collect { launch(it) } }
                    LaunchedEffect(Unit) {
                        if (direct == "pairing") adb.begin()
                        requests.firstOrNull { it.first == direct }?.let { launch(it.second) }
                    }
                    Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        requests.forEach { (name, request) -> Button(onClick = { launch(request) }) { Text(name) } }
                        Button(onClick = adb::begin) { Text("pairing") }
                        Text(adbState.toString())
                    }
                }
            }
        }
    }
}
