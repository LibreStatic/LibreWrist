package io.openshm.patcher.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/** One line of an instruction text: a plain paragraph, or a list item with its marker ("1." or a bullet). */
internal sealed interface InstructionBlock {
    data class Paragraph(val text: AnnotatedString) : InstructionBlock
    data class Item(val marker: String, val text: AnnotatedString) : InstructionBlock
}

private const val BOLD_MARK = "**"
private const val BULLET = "•"
private val numbered = Regex("""^(\d+)\.\s+(.*)$""")
private val bulleted = Regex("""^[•\-]\s+(.*)$""")

/**
 * Turns the plain markup of the string resources into styled text. `**label**` is bold (the labels
 * the user reads on the device screen); an unmatched or empty `**` stays as typed.
 */
internal fun parseInstructionInline(line: String, bold: SpanStyle = SpanStyle(fontWeight = FontWeight.Bold)): AnnotatedString =
    buildAnnotatedString {
        var at = 0
        while (at < line.length) {
            val open = line.indexOf(BOLD_MARK, at)
            val close = if (open < 0) -1 else line.indexOf(BOLD_MARK, open + BOLD_MARK.length)
            // No pair left, or an empty pair (`****`): the rest is literal.
            if (open < 0 || close < 0 || close == open + BOLD_MARK.length) {
                append(line.substring(at))
                break
            }
            append(line.substring(at, open))
            withStyle(bold) { append(line.substring(open + BOLD_MARK.length, close)) }
            at = close + BOLD_MARK.length
        }
    }

/**
 * Splits [text] by lines: "1. ", "2. " ... start a numbered item and "• " or "- " a bullet; any other
 * non-blank line is a paragraph. Blank lines are dropped (the layout spaces the blocks itself).
 */
internal fun parseInstructionBlocks(text: String, bold: SpanStyle = SpanStyle(fontWeight = FontWeight.Bold)): List<InstructionBlock> =
    text.lines().mapNotNull { raw ->
        val line = raw.trim()
        if (line.isEmpty()) return@mapNotNull null
        numbered.matchEntire(line)?.let { m ->
            return@mapNotNull InstructionBlock.Item("${m.groupValues[1]}.", parseInstructionInline(m.groupValues[2], bold))
        }
        bulleted.matchEntire(line)?.let { m ->
            return@mapNotNull InstructionBlock.Item(BULLET, parseInstructionInline(m.groupValues[1], bold))
        }
        InstructionBlock.Paragraph(parseInstructionInline(line, bold))
    }

/**
 * Instruction text from a plain string resource: `**bold**` for on-screen labels, "1. " numbered and
 * "• " / "- " bulleted lines with a hanging indent (the marker sits in its own cell, so wrapped lines
 * line up under the text, and the row mirrors by itself in right-to-left languages).
 */
@Composable
fun InstructionText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    val blocks = remember(text) { parseInstructionBlocks(text) }
    val single = blocks.singleOrNull()
    if (single is InstructionBlock.Paragraph) {
        Text(single.text, modifier, color = color, style = style)
        return
    }
    val density = LocalDensity.current
    val markerWidth = remember(style.fontSize, density) {
        with(density) { (if (style.fontSize.isSpecified) style.fontSize else 14.sp).toDp() * 1.6f }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (block in blocks) {
            when (block) {
                is InstructionBlock.Paragraph -> Text(block.text, color = color, style = style)
                is InstructionBlock.Item -> Row(
                    Modifier.semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(block.marker, Modifier.width(markerWidth), color = color, style = style)
                    Text(block.text, Modifier.weight(1f), color = color, style = style)
                }
            }
        }
    }
}
