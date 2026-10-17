package io.openshm.patcher.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.openshm.patcher.R
import io.openshm.patcher.device.guide.Actor
import io.openshm.patcher.device.guide.ControlStrip
import io.openshm.patcher.device.guide.GuideBubble
import io.openshm.patcher.device.guide.GuideExample
import io.openshm.patcher.device.guide.GuideRect
import io.openshm.patcher.device.guide.GuideUi
import io.openshm.patcher.device.guide.PetMood
import io.openshm.patcher.device.guide.PetPlacement
import io.openshm.patcher.ui.guide.GuideOverlay
import io.openshm.patcher.ui.guide.GuideOverlayContent

/*
 * Drivers for the guide overlay (Tic), over a fake Settings list. The overlay gets fixed GuideUi
 * states, so each render is one frozen moment (PreviewHost turns motion off).
 */

private val SettingsRows = listOf(
    "Network & internet" to "Mobile, Wi-Fi, hotspot",
    "Connected devices" to "Bluetooth, pairing",
    "Apps" to "Assistant, recent apps, default apps",
    "Notifications" to "Notification history, conversations",
    "Battery" to "100%",
    "Storage" to "42% used - 74 GB free",
    "Sound & vibration" to "Volume, haptics, Do Not Disturb",
    "Display" to "Dark theme, font size, brightness",
    "Wallpaper & style" to "Colors, themed icons, app grid",
    "Accessibility" to "Display, interaction, audio",
    "Developer options" to "Wireless debugging",
)

private const val TOP_BAR_DP = 88
private const val ROW_DP = 72

/** A fake Settings screen; [content] draws the overlay, given the pixel rect of row [row] (screen pixels). */
@Composable
private fun GuideStage(row: Int?, content: @Composable (GuideRect?) -> Unit) = PreviewHost(ColorMode.Fallback) {
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        val density = LocalDensity.current
        Column {
            Box(Modifier.height(TOP_BAR_DP.dp).fillMaxWidth().padding(start = 24.dp), contentAlignment = Alignment.BottomStart) {
                Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 12.dp))
            }
            SettingsRows.forEach { (title, summary) ->
                Column(
                    Modifier.height(ROW_DP.dp).fillMaxWidth().padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val rect = row?.let {
            with(density) {
                val top = (TOP_BAR_DP + ROW_DP * it).dp.roundToPx()
                GuideRect(0, top, constraints.maxWidth, top + ROW_DP.dp.roundToPx())
            }
        }
        content(rect)
    }
}

/** The user's step: amber box on the row, Tic in a corner pointing at it. */
@Composable
fun GuidePetPointingDriver() = GuideStage(row = 9) { target ->
    GuideOverlay(
        GuideUi(
            visible = true, mood = PetMood.Point, target = target, highlight = true, placement = PetPlacement.Corner,
            bubble = GuideBubble(R.string.guide_preview_point, listOf("Accessibility"), Actor.User),
        ),
        onPetTap = {}, onCodeEntered = {},
    )
}

/** Tic's own step: it stands next to the row, its arm reaches a glove resting on it. */
@Composable
fun GuidePetTappingDriver() = GuideStage(row = 3) { target ->
    GuideOverlay(
        GuideUi(
            visible = true, mood = PetMood.Idle, target = target, reach = true, pressSeq = 1,
            placement = PetPlacement.NearTarget, strip = ControlStrip.Controlling,
            bubble = GuideBubble(R.string.guide_preview_tap, listOf("Notifications"), Actor.Tic),
        ),
        onPetTap = {}, onCodeEntered = {},
    )
}

/** Tic could not find the row: sad, with a mini row to look for. */
@Composable
fun GuidePetSadDriver() = GuideStage(row = null) {
    GuideOverlay(
        GuideUi(
            visible = true, mood = PetMood.Sad,
            bubble = GuideBubble(
                R.string.guide_preview_sad, listOf("Pair with code"),
                example = GuideExample("Pair device with pairing code", "Pair new devices using a six-digit code"),
            ),
        ),
        onPetTap = {}, onCodeEntered = {},
    )
}

/** Paused: Tic asleep bottom-left, the strip tells how to wake it. */
@Composable
fun GuidePetSleepingDriver() = GuideStage(row = null) {
    GuideOverlay(
        GuideUi(visible = true, mood = PetMood.Sleep, placement = PetPlacement.Sleeping, strip = ControlStrip.Paused),
        onPetTap = {}, onCodeEntered = {},
    )
}

/** Code entry: Tic stands on a fake keyboard, the bubble holds the six boxes. */
@Composable
fun GuidePetCodeEntryDriver() = GuideStage(row = null) {
    val keyboard = 300.dp
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(keyboard)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Text("Numeric keyboard", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        GuideOverlay(
            GuideUi(
                visible = true, mood = PetMood.Talk, placement = PetPlacement.AboveKeyboard,
                bubble = GuideBubble(R.string.guide_preview_code, codeEntry = true),
            ),
            onPetTap = {}, onCodeEntered = {},
            imeHeightPx = with(LocalDensity.current) { keyboard.roundToPx() },
        )
    }
}

/** The farewell frozen after Tic left: only the smoke is left. */
@Composable
fun GuidePetFarewellDriver() = GuideStage(row = null) {
    GuideOverlayContent(
        GuideUi(visible = true, mood = PetMood.Happy, farewell = true, bubble = GuideBubble(R.string.guide_preview_done)),
        farewellMs = 5350, onPetTap = {}, onCodeEntered = {},
    )
}

/** The farewell frozen mid-bow. */
@Composable
fun GuidePetBowDriver() = GuideStage(row = null) {
    GuideOverlayContent(
        GuideUi(visible = true, mood = PetMood.Happy, farewell = true, bubble = GuideBubble(R.string.guide_preview_done)),
        farewellMs = 4200, onPetTap = {}, onCodeEntered = {},
    )
}
