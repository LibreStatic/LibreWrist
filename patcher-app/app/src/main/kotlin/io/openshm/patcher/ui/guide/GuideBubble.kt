package io.openshm.patcher.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.openshm.patcher.R
import io.openshm.patcher.device.guide.Actor
import io.openshm.patcher.device.guide.GuideBubble
import io.openshm.patcher.device.guide.GuideExample

internal const val CODE_LENGTH = 6
private val ChipAmber = Color(0xFFFFB300)

/** Bubble and Android-Settings-like colors of the design, for the light and dark scheme. */
internal class GuideColors(dark: Boolean) {
    val bubble = if (dark) Color(0xFF262838) else Color.White
    val bubbleText = if (dark) Color(0xFFECEBF5) else Color(0xFF1A1B23)
    val emphasis = if (dark) Color(0xFFB9C3FF) else Color(0xFF2F46C9)
    val ripple = if (dark) Color(0xFFE0E2E8) else Color(0xFF181C20)
    val controlling = if (dark) Color(0xFFF2B8B5) else Color(0xFFB3261E)
    val onControlling = if (dark) Color(0xFF601410) else Color.White
}

/** [format] with every argument wrapped in sentinels, so the arguments can be set in bold afterwards. */
@Composable
private fun bubbleText(bubble: GuideBubble, colors: GuideColors): AnnotatedString {
    val context = LocalContext.current
    val marked = bubble.args.map { "\u0001$it\u0002" }
    val raw = context.getString(bubble.text, *marked.toTypedArray())
    return buildAnnotatedString {
        var bold = false
        var from = 0
        fun flush(to: Int) {
            if (to <= from) return
            if (bold) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = colors.emphasis)) { append(raw.substring(from, to)) }
            else append(raw.substring(from, to))
        }
        raw.forEachIndexed { i, c ->
            if (c == '\u0001' || c == '\u0002') {
                flush(i)
                from = i + 1
                bold = c == '\u0001'
            }
        }
        flush(raw.length)
    }
}

/**
 * The speech bubble: 2.5 dp bezel border, rounded 20, with a tail toward Tic. [onShown] and the
 * scale/fade are applied by the caller; the tail is drawn over the body so the join is seamless.
 */
@Composable
internal fun TicBubble(
    bubble: GuideBubble,
    tail: BubbleTail,
    colors: GuideColors,
    onCodeEntered: (String) -> Unit,
    onCodeDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier.drawWithContent {
            drawContent()
            drawTail(tail, colors.bubble)
        },
    ) {
        Column(
            Modifier
                .shadow(4.dp, shape, clip = false)
                .background(colors.bubble, shape)
                .border(2.5.dp, PetBezel, shape)
                .padding(start = 14.dp, end = 14.dp, top = 11.dp, bottom = 12.dp),
        ) {
            bubble.actor?.let { Chip(it) }
            Text(
                bubbleText(bubble, colors),
                style = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, color = colors.bubbleText),
            )
            bubble.example?.let { ExampleRow(it) }
            if (bubble.codeEntry) {
                CodeBoxes(onCodeEntered, onCodeDismiss)
                // The code window takes the screen's touches: this (or Back) always lets the user out.
                TextButton(onCodeDismiss, Modifier.align(Alignment.End)) { Text(stringResource(R.string.guide_ui_code_not_now)) }
            }
        }
    }
}

@Composable
private fun Chip(actor: Actor) {
    val tic = actor == Actor.Tic
    Text(
        stringResource(if (tic) R.string.guide_ui_chip_tic else R.string.guide_ui_chip_user),
        modifier = Modifier
            .padding(bottom = 6.dp)
            .background(if (tic) PetScreen else ChipAmber, RoundedCornerShape(99.dp))
            .padding(horizontal = 9.dp, vertical = 2.dp),
        style = TextStyle(
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = .23.sp,
            color = if (tic) Color.White else PetBezel,
        ),
    )
}

/** A mini Settings row to look for, when Tic can't find the real one. */
@Composable
private fun ExampleRow(example: GuideExample) {
    val outline = MaterialTheme.colorScheme.outline
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .padding(top = 9.dp)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, shape)
            .drawBehind {
                val w = 1.5.dp.toPx()
                drawRoundRect(
                    outline, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(12.dp.toPx()),
                    style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(example.title, style = TextStyle(fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface))
        example.summary?.let {
            Text(it, style = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant))
        }
    }
}

/** Six boxes over a hidden numeric field; focused on arrival so the keyboard opens. */
@Composable
private fun CodeBoxes(onCodeEntered: (String) -> Unit, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val description = stringResource(R.string.guide_ui_code_description)
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    BasicTextField(
        value = code,
        onValueChange = { v ->
            val digits = v.filter(Char::isDigit).take(CODE_LENGTH)
            if (digits != code) {
                code = digits
                if (digits.length == CODE_LENGTH) onCodeEntered(digits)
            }
        },
        modifier = Modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .focusRequester(focus)
            .onPreviewKeyEvent { e ->
                val back = e.key == Key.Back && e.type == KeyEventType.KeyUp
                if (back) onDismiss()
                back
            }
            .semantics { contentDescription = description },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        singleLine = true,
        textStyle = TextStyle(color = Color.Transparent),
        cursorBrush = SolidColor(Color.Transparent),
        decorationBox = { inner ->
            Box {
                Box(Modifier.size(1.dp).alpha(0f)) { inner() }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(CODE_LENGTH) { i ->
                        CodeBox(code.getOrNull(i)?.toString().orEmpty(), current = i == code.length, Modifier.weight(1f))
                    }
                }
            }
        },
    )
}

@Composable
private fun CodeBox(digit: String, current: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .height(38.dp)
            .drawBehind {
                if (current) {
                    // The focus ring: 2 dp outside the box, at 30% of the pet-screen blue.
                    val r = 2.dp.toPx()
                    drawRoundRect(
                        PetScreen.copy(alpha = .3f), Offset(-r / 2, -r / 2), Size(size.width + r, size.height + r),
                        CornerRadius(10.dp.toPx() + r / 2), style = Stroke(r),
                    )
                }
            }
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(2.dp, if (current) PetScreen else MaterialTheme.colorScheme.outline, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            digit,
            textAlign = TextAlign.Center,
            style = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface),
        )
    }
}

/** Transform origin of the pop-in, as fractions of the bubble, matching where the tail points. */
internal fun BubbleTail.origin(): Pair<Float, Float> = when (this) {
    BubbleTail.BottomRight -> .85f to 1f
    BubbleTail.BottomLeft -> .15f to 1f
    BubbleTail.TopRight -> .85f to 0f
    BubbleTail.TopLeft -> .15f to 0f
    BubbleTail.SideRight -> 1f to .3f
    BubbleTail.SideLeft -> 0f to .3f
    BubbleTail.Keyboard -> 1f to .8f
}

/** A 16 dp square turned 45 degrees (or more), with the border on two of its sides only. */
private fun DrawScope.drawTail(tail: BubbleTail, fill: Color) {
    val d = density
    val b = 2.5f * d
    val q = 16f * d
    val w = size.width
    val h = size.height
    val (x, y, deg) = when (tail) {
        BubbleTail.BottomRight -> Triple(w - b - 34 * d - q, h - b + 9.5f * d - q, 45f)
        BubbleTail.BottomLeft -> Triple(b + 34 * d, h - b + 9.5f * d - q, 45f)
        BubbleTail.TopRight -> Triple(w - b - 34 * d - q, b - 9.5f * d, -135f)
        BubbleTail.TopLeft -> Triple(b + 34 * d, b - 9.5f * d, -135f)
        BubbleTail.SideRight -> Triple(w - b + 10 * d - q, b + 30 * d, -45f)
        BubbleTail.SideLeft -> Triple(b - 10 * d, b + 30 * d, 135f)
        BubbleTail.Keyboard -> Triple(w - b + 10 * d - q, h - b - 34 * d - q, -45f)
    }
    rotate(deg, Offset(x + q / 2, y + q / 2)) {
        drawRect(fill, Offset(x, y), Size(q, q))
        drawRect(PetBezel, Offset(x + q - b, y), Size(b, q))
        drawRect(PetBezel, Offset(x, y + q - b), Size(q, b))
    }
}
