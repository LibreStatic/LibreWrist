package io.openshm.patcher

import androidx.compose.ui.text.font.FontWeight
import io.openshm.patcher.ui.components.InstructionBlock
import io.openshm.patcher.ui.components.parseInstructionBlocks
import io.openshm.patcher.ui.components.parseInstructionInline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstructionTextParserTest {
    private fun boldSpans(text: String) =
        parseInstructionInline(text).let { a ->
            a.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }.map { a.text.substring(it.start, it.end) }
        }

    @Test fun boldLabelsBecomeSpansAndLoseTheMarks() {
        val a = parseInstructionInline("Open **Settings**, then **About watch**.")
        assertEquals("Open Settings, then About watch.", a.text)
        assertEquals(listOf("Settings", "About watch"), boldSpans("Open **Settings**, then **About watch**."))
    }

    @Test fun textWithoutMarkupIsUntouched() {
        val a = parseInstructionInline("Plain text.")
        assertEquals("Plain text.", a.text)
        assertTrue(a.spanStyles.isEmpty())
    }

    @Test fun unmatchedMarksStayLiteral() {
        assertEquals("Tap **Settings", parseInstructionInline("Tap **Settings").text)
        assertEquals("a b c ** d", parseInstructionInline("a **b** c ** d").text)
        assertEquals(listOf("b"), boldSpans("a **b** c ** d"))
        assertEquals("a **** b", parseInstructionInline("a **** b").text)
    }

    @Test fun numberedLinesBecomeItemsWithTheirNumber() {
        val blocks = parseInstructionBlocks("1. First **one**\n2. Second\n10. Tenth")
        assertEquals(listOf("1.", "2.", "10."), blocks.map { (it as InstructionBlock.Item).marker })
        assertEquals("First one", (blocks[0] as InstructionBlock.Item).text.text)
    }

    @Test fun bulletLinesBecomeItemsWithABullet() {
        val blocks = parseInstructionBlocks("• one\n- two")
        assertEquals(listOf("•", "•"), blocks.map { (it as InstructionBlock.Item).marker })
        assertEquals(listOf("one", "two"), blocks.map { (it as InstructionBlock.Item).text.text })
    }

    @Test fun otherLinesAreParagraphsAndBlankLinesAreDropped() {
        val blocks = parseInstructionBlocks("Intro\n\n1. Step\nA closing note\n")
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is InstructionBlock.Paragraph)
        assertTrue(blocks[1] is InstructionBlock.Item)
        assertTrue(blocks[2] is InstructionBlock.Paragraph)
    }

    @Test fun aNumberWithoutTheDotAndSpaceIsNotAListItem() {
        assertTrue(parseInstructionBlocks("5 to 7 times")[0] is InstructionBlock.Paragraph)
        assertTrue(parseInstructionBlocks("1.5 seconds")[0] is InstructionBlock.Paragraph)
        assertTrue(parseInstructionBlocks("-5 degrees")[0] is InstructionBlock.Paragraph)
    }

    @Test fun emptyInputDoesNotCrash() {
        assertEquals("", parseInstructionInline("").text)
        assertTrue(parseInstructionBlocks("").isEmpty())
        assertTrue(parseInstructionBlocks("\n \n").isEmpty())
    }
}
