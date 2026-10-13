package io.openshm.patcher.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CompanionCommandsTest {
    private val pkg = "io.example.shmbridge.watch"

    @Test
    fun commandsAreBuiltFromValidatedValues() {
        assertEquals("content query --uri content://$pkg.link", CompanionCommands.linkInfoCommand(pkg))
        assertEquals("cmd companiondevice list 0", CompanionCommands.LIST_COMMAND)
        assertEquals("cmd companiondevice associate 0 $pkg B8:34:46:0D:88:F4", CompanionCommands.associateCommand(pkg, "b8:34:46:0d:88:f4"))
        assertEquals("cmd companiondevice disassociate 0 $pkg B8:34:46:0D:88:F4", CompanionCommands.disassociateCommand(pkg, "B8:34:46:0D:88:F4"))
    }

    @Test
    fun injectionAndBadValuesAreRejected() {
        for (bad in listOf("io.example.x; reboot", "noDots", "io.example.x link", "")) {
            assertThrows(IllegalArgumentException::class.java) { CompanionCommands.linkInfoCommand(bad) }
            assertThrows(IllegalArgumentException::class.java) { CompanionCommands.associateCommand(bad, "B8:34:46:0D:88:F4") }
        }
        for (bad in listOf("", "B8:34:46:0D:88", "B8:34:46:0D:88:F4; reboot", "B8:34:46:0D:88:F4 x", "GG:34:46:0D:88:F4", "00:00:00:00:00:00", "02:00:00:00:00:00", "B834460D88F4")) {
            assertThrows(IllegalArgumentException::class.java) { CompanionCommands.associateCommand(pkg, bad) }
            assertThrows(IllegalArgumentException::class.java) { CompanionCommands.disassociateCommand(pkg, bad) }
        }
    }

    @Test
    fun phoneMacIsReadFromTheRowAndNormalized() {
        assertEquals(
            "B8:34:46:0D:88:F4",
            CompanionCommands.parsePhoneMac("Row: 0 phone_mac=B8:34:46:0D:88:F4, companion=0, status=linked, session open\n"),
        )
        assertEquals("B8:34:46:0D:88:F4", CompanionCommands.parsePhoneMac("Row: 0 status=a, b, phone_mac=b8:34:46:0d:88:f4"))
    }

    @Test
    fun noMacWhenEmptyUnusableOrTheProviderIsMissing() {
        assertNull(CompanionCommands.parsePhoneMac("Row: 0 phone_mac=, companion=0, status=waiting"))
        assertNull(CompanionCommands.parsePhoneMac("Row: 0 phone_mac=00:00:00:00:00:00, companion=0"))
        assertNull(CompanionCommands.parsePhoneMac("Row: 0 phone_mac=02:00:00:00:00:00, companion=0"))
        assertNull(CompanionCommands.parsePhoneMac("Row: 0 phone_mac=zz; reboot, companion=0"))
        assertNull(CompanionCommands.parsePhoneMac("Error while accessing provider:$pkg.link"))
        assertNull(CompanionCommands.parsePhoneMac("No result found.\n"))
        assertNull(CompanionCommands.parsePhoneMac(""))
    }

    @Test
    fun theTableFormatIsParsedAndHeadersAreIgnored() {
        val out = """
            Max ID: 1
            Association ID | Package Name | Mac Address
            1 | io.openshm.cdmtest.shmbridge.watch | b8:34:46:0d:88:f4
        """.trimIndent()
        assertEquals(
            listOf(CompanionAssociation("io.openshm.cdmtest.shmbridge.watch", "B8:34:46:0D:88:F4")),
            CompanionCommands.parseAssociations(out),
        )
    }

    @Test
    fun theOlderPlainFormatIsParsedAndGarbageIsIgnored() {
        val out = "io.a.b AA:BB:CC:DD:EE:FF\n\nsome warning here\nio.c.d 00:00:00:00:00:00\nio.e.f 11:22:33:44:55:66\nMax ID: 3\n"
        assertEquals(
            listOf(CompanionAssociation("io.a.b", "AA:BB:CC:DD:EE:FF"), CompanionAssociation("io.e.f", "11:22:33:44:55:66")),
            CompanionCommands.parseAssociations(out),
        )
        assertEquals(emptyList<CompanionAssociation>(), CompanionCommands.parseAssociations(""))
    }
}
