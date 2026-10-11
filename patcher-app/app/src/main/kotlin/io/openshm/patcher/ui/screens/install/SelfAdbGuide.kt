package io.openshm.patcher.ui.screens.install

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.openshm.patcher.R
import io.openshm.patcher.ui.components.InstructionText
import io.openshm.patcher.ui.components.Item
import io.openshm.patcher.ui.components.ScreenList
import io.openshm.patcher.ui.components.TouchPoint
import io.openshm.patcher.ui.components.WatchScreen
import io.openshm.patcher.ui.components.WatchText
import io.openshm.patcher.ui.components.watchSlide
import io.openshm.patcher.ui.screens.watchpair.LocalFrozenGuideSubStep
import io.openshm.patcher.ui.screens.watchpair.SubStepChips
import io.openshm.patcher.ui.theme.LocalReducedMotion
import kotlinx.coroutines.delay

private val captions = listOf(
    R.string.selfadb_guide_sub_1,
    R.string.selfadb_guide_sub_2,
    R.string.selfadb_guide_sub_3,
    R.string.selfadb_guide_sub_4,
)

private const val SUB_STEP_MS = 2500L

/**
 * Looping animation of how the Wireless debugging fallback works, on an illustrated phone (same
 * look and motion as the watch guide): tap the button, turn on Wireless debugging, tap "Pair device
 * with pairing code", type the code in the notification. [frozenSubStep] holds one frame (previews).
 */
@Composable
fun SelfAdbGuide(modifier: Modifier = Modifier, frozenSubStep: Int? = LocalFrozenGuideSubStep.current) {
    val reducedMotion = LocalReducedMotion.current
    val colors = MaterialTheme.colorScheme
    var userSub by rememberSaveable { mutableIntStateOf(0) }
    val sub = (frozenSubStep ?: userSub).coerceIn(0, captions.size - 1)
    val animating = frozenSubStep == null && !reducedMotion
    LaunchedEffect(userSub, animating) {
        if (!animating) return@LaunchedEffect
        delay(SUB_STEP_MS)
        userSub = (userSub + 1) % captions.size
    }
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerHigh) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.selfadb_guide_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth().semantics { heading() },
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FakePhone(sub, reducedMotion)
                SubStepChips(sub, captions.size, onSelect = { userSub = it })
            }
            InstructionText(
                stringResource(R.string.watchpair_dev_line, sub + 1, stringResource(captions[sub])),
                style = MaterialTheme.typography.labelLarge,
                color = colors.primary,
                modifier = Modifier.fillMaxWidth(),
            )
            InstructionText(
                stringResource(R.string.selfadb_guide_already),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A phone: bezel, dark screen, and the frame of the current step sliding in. */
@Composable
private fun FakePhone(sub: Int, reducedMotion: Boolean) {
    Box(
        // Illustration only: the caption under it says the same for screen readers.
        Modifier.clearAndSetSemantics { }.size(width = 156.dp, height = 224.dp).clip(RoundedCornerShape(26.dp))
            .background(Color(0xFF2B2C31)).border(3.dp, Color(0xFF55565E), RoundedCornerShape(26.dp)).padding(6.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().size(width = 144.dp, height = 212.dp).clip(RoundedCornerShape(20.dp)).background(WatchScreen.background),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = sub,
                transitionSpec = { watchSlide(reducedMotion, forward = targetState > initialState || (targetState == 0 && initialState == captions.size - 1)) },
                label = "selfAdbGuideFrame",
            ) { shown ->
                Box(Modifier.size(width = 144.dp, height = 212.dp), contentAlignment = Alignment.Center) {
                    when (shown) {
                        0 -> ButtonFrame(reducedMotion)
                        1 -> ScreenList(
                            R.string.watch_ui_developer,
                            listOf(
                                Item(R.string.watch_ui_adb, toggle = false),
                                Item(R.string.watch_ui_wireless, toggle = false, tap = true),
                            ),
                            reducedMotion,
                        )
                        2 -> ScreenList(
                            R.string.watch_ui_wireless,
                            listOf(
                                Item(R.string.selfadb_guide_ui_pair_code, tap = true),
                                Item(R.string.selfadb_guide_ui_paired),
                            ),
                            reducedMotion,
                        )
                        else -> NotificationFrame(reducedMotion)
                    }
                }
            }
        }
    }
}

/** This app's install screen with the button to tap. */
@Composable
private fun ButtonFrame(reducedMotion: Boolean) {
    Column(Modifier.width(124.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        WatchText(stringResource(R.string.phone_adb_title), WatchScreen.dim, 9.sp, FontWeight.SemiBold)
        Box(contentAlignment = Alignment.CenterEnd) {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).border(1.dp, WatchScreen.accent, RoundedCornerShape(20.dp))
                    .background(WatchScreen.accent.copy(alpha = 0.22f)).padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { WatchText(stringResource(R.string.phone_adb_action), WatchScreen.accent, 9.sp, FontWeight.SemiBold) }
            TouchPoint(reducedMotion, Modifier.padding(end = 8.dp))
        }
    }
}

/** The heads-up notification of this app with its "Enter code" action, over the pairing dialog. */
@Composable
private fun NotificationFrame(reducedMotion: Boolean) {
    Column(Modifier.width(128.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(WatchScreen.tile).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            WatchText(stringResource(R.string.app_name), WatchScreen.accent, 8.sp, FontWeight.SemiBold)
            WatchText(stringResource(R.string.selfadb_enter_code_title), WatchScreen.text, 9.sp, FontWeight.SemiBold)
            Box(contentAlignment = Alignment.CenterEnd) {
                Row(
                    Modifier.clip(CircleShape).background(WatchScreen.accent.copy(alpha = 0.22f)).padding(horizontal = 10.dp, vertical = 3.dp),
                ) { WatchText(stringResource(R.string.selfadb_reply_action), WatchScreen.accent, 9.sp, FontWeight.SemiBold) }
                TouchPoint(reducedMotion, Modifier.padding(end = 2.dp))
            }
        }
    }
}
