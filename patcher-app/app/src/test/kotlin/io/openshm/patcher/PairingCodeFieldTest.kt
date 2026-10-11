package io.openshm.patcher

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.openshm.patcher.ui.components.PairingCodeField
import io.openshm.patcher.ui.components.contrastRatio
import io.openshm.patcher.ui.screens.install.SelfAdbPhase
import io.openshm.patcher.ui.screens.install.SelfAdbProgress
import io.openshm.patcher.ui.theme.PatcherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
class PairingCodeFieldTest {
    @get:Rule
    val rule = createComposeRule()

    private fun fieldText(): String =
        rule.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun showSelfAdb(phase: () -> SelfAdbPhase, submitted: MutableList<String>) {
        rule.setContent {
            PatcherTheme { SelfAdbProgress(phase(), onSubmitCode = { submitted += it }, onRetry = {}, onCancel = {}) }
        }
    }

    @Test
    fun typingSixDigitsOneByOneSubmitsExactlyOnce() {
        val submitted = mutableListOf<String>()
        showSelfAdb({ SelfAdbPhase.WaitingForCode }, submitted)
        "123456".forEach { rule.onNode(hasSetTextAction()).performTextInput(it.toString()) }
        rule.waitForIdle()
        assertEquals(listOf("123456"), submitted)
    }

    @Test
    fun pastingSixDigitsSubmitsExactlyOnce() {
        val submitted = mutableListOf<String>()
        showSelfAdb({ SelfAdbPhase.WaitingForCode }, submitted)
        rule.onNode(hasSetTextAction()).performTextReplacement("123456")
        rule.waitForIdle()
        assertEquals(listOf("123456"), submitted)
    }

    @Test
    fun fewerThanSixDigitsDoNotSubmit() {
        val submitted = mutableListOf<String>()
        showSelfAdb({ SelfAdbPhase.WaitingForCode }, submitted)
        rule.onNode(hasSetTextAction()).performTextReplacement("12345")
        rule.waitForIdle()
        assertEquals(emptyList<String>(), submitted)
    }

    @Test
    fun aWrongCodeClearsTheFieldForANewTry() {
        val submitted = mutableListOf<String>()
        var phase by mutableStateOf(SelfAdbPhase.WaitingForCode)
        showSelfAdb({ phase }, submitted)
        rule.onNode(hasSetTextAction()).performTextReplacement("123456")
        rule.waitForIdle()
        phase = SelfAdbPhase.Connecting
        rule.waitForIdle()
        phase = SelfAdbPhase.WrongCode
        rule.waitForIdle()
        assertEquals("", fieldText())
        // The next complete code goes out as a new submission.
        rule.onNode(hasSetTextAction()).performTextReplacement("654321")
        rule.waitForIdle()
        assertEquals(listOf("123456", "654321"), submitted)
    }

    @Test
    fun theFieldKeepsItsFocusWhenTheShakeKeyIncreases() {
        var shakeKey by mutableIntStateOf(0)
        rule.setContent {
            PatcherTheme { PairingCodeField(code = "", onCodeChange = {}, label = "Code", shakeKey = shakeKey) }
        }
        rule.onNode(hasSetTextAction()).performClick()
        rule.onNode(hasSetTextAction()).assertIsFocused()
        shakeKey = 1
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun contrastRatioMatchesTheWcagReferenceValues() {
        assertEquals(21f, contrastRatio(Color.Black, Color.White), 0.01f)
        assertEquals(21f, contrastRatio(Color.White, Color.Black), 0.01f)
        assertEquals(1f, contrastRatio(Color.Red, Color.Red), 0.001f)
        // #777777 on white is the textbook 4.48:1.
        assertEquals(4.48f, contrastRatio(Color(0xFF777777), Color.White), 0.02f)
    }
}
