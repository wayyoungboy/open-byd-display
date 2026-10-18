package org.openbyddisplay.vehicle

import org.junit.Assert.*
import org.junit.Test

class UiModeWriteEvidenceTest {
    @Test fun missingReadBackCannotConfirmDirectWrite() {
        assertFalse(UiModeWriteEvidence.readBackMatches(null, 1))
        assertFalse(UiModeWriteEvidence.readBackMatches(Int.MIN_VALUE, 1))
    }
    @Test fun exactReadBackConfirmsDesiredValue() {
        assertTrue(UiModeWriteEvidence.readBackMatches(2, 2))
        assertFalse(UiModeWriteEvidence.readBackMatches(1, 2))
    }
    @Test fun exactShellRowRequiresSuccessfulExecution() {
        assertTrue(UiModeWriteEvidence.adbConfirmed(true, "Updated 1 rows.\nRow: 0 value=1", null, 1))
        assertFalse(UiModeWriteEvidence.adbConfirmed(false, "Row: 0 value=1", null, 1))
    }
    @Test fun numericPrefixDoesNotMatch() {
        assertFalse(UiModeWriteEvidence.adbConfirmed(true, "Row: 0 value=10", null, 1))
    }
    @Test fun errorOrMissingRowsDoNotConfirm() {
        assertFalse(UiModeWriteEvidence.adbConfirmed(true, "Error: refused value=1", null, 1))
        assertFalse(UiModeWriteEvidence.adbConfirmed(true, "No result found.", null, 1))
    }
    @Test fun duplicateRowsAreAmbiguous() {
        assertFalse(UiModeWriteEvidence.adbConfirmed(true, "Row: 0 value=1\nRow: 1 value=1", null, 1))
    }
    @Test fun providerReadBackOverridesStaleShellOutput() {
        assertFalse(UiModeWriteEvidence.adbConfirmed(true, "Row: 0 value=1", 2, 1))
        assertTrue(UiModeWriteEvidence.adbConfirmed(false, "", 1, 1))
    }

    private fun runFakeProvider(updateOutput: String, updateExit: Int = 0): Pair<Int, String> {
        // Local shell function replaces content; no Android executable or device is used.
        val provider = """
            content() {
              case "${'$'}1" in
                update) printf '%s\n' '$updateOutput'; return $updateExit ;;
                insert) printf 'INSERTED\n' ;;
                query) printf 'Row: 0 value=1\n' ;;
              esac
            }
        """.trimIndent()
        val cmd = UiModeWriteEvidence.buildWriteCommand("support_change_system_uimode", 1)
        val process = ProcessBuilder("/bin/sh", "-c", "$provider\n$cmd").start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }
    @Test fun successfulUpdateDoesNotInsertDuplicateRow() {
        val (exit, output) = runFakeProvider("Updated 1 rows.")
        assertEquals(0, exit)
        assertFalse(output.contains("INSERTED"))
        assertTrue(UiModeWriteEvidence.adbConfirmed(true, output, null, 1))
    }
    @Test fun explicitlyMissingRowCanBeInserted() {
        val (exit, output) = runFakeProvider("Updated 0 rows.")
        assertEquals(0, exit)
        assertTrue(output.contains("INSERTED"))
    }
    @Test fun deniedUpdateStopsBeforeInsertOrQuery() {
        val (exit, output) = runFakeProvider("permission denied", 5)
        assertEquals(5, exit)
        assertFalse(output.contains("INSERTED"))
        assertFalse(output.contains("Row:"))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnknownProviderKeys() {
        UiModeWriteEvidence.buildWriteCommand("sys_screen_mode'; id", 1)
    }

    @Test fun nativeScreenModeCannotBeWrittenByUnlockWorkflow() {
        for (mode in 0..2) {
            try {
                UiModeWriteEvidence.buildWriteCommand("sys_screen_mode", mode)
                fail("Unlock must leave native mode $mode to the OEM")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun unlockCommandDoesNotWriteOrReadDayNightPolicy() {
        val command = UiModeWriteEvidence.buildWriteCommand("support_change_system_uimode", 1)
        assertTrue(command.contains("support_change_system_uimode"))
        assertFalse(command.contains("sys_screen_mode"))
        assertFalse(command.contains("sys_day_or_night"))
    }
}
