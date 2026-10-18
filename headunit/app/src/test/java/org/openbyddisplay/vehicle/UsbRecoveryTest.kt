package org.openbyddisplay.vehicle

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class UsbRecoveryTest {
    @Test fun liveInterfaceWithClearedAddressDoesNotProveNcmDisconnected() {
        val original = UsbModeEvidence.Interface("usb0", "cdc_ncm", "aa:bb:cc:dd:ee:ff", true,
            listOf(UsbModeEvidence.Address(UsbModeEvidence.LOCAL_ADDRESS, 30)))
        val cleared = original.copy(addresses = emptyList())
        assertNull(UsbModeEvidence.selectInterface(listOf(cleared)))
        assertFalse(UsbModeEvidence.oldInterfaceGone(original, listOf(cleared), setOf("usb0")))
        assertFalse(UsbModeEvidence.oldInterfaceGone(original, emptyList(), setOf("usb0")))
        assertFalse(UsbModeEvidence.oldInterfaceGone(original, emptyList(), null))
        assertTrue(UsbModeEvidence.oldInterfaceGone(original, emptyList(), emptySet()))
        assertFalse(UsbModeEvidence.oldInterfaceGone(null, emptyList(), emptySet()))
    }
    @Test fun aWriteThenEnumerationFailureRemainsUnknownEvenWithACompleteExit() {
        val id = "a".repeat(32)
        val cmd = BydInstrumentCmd.SCMD_USBMODE_CAST
        for (phase in listOf("AFTER_WRITE", "BEFORE_WRITE")) {
            val out = "SDK_RETURN cmd=$cmd uid=2000 rc=0\nFAIL request=$id cmd=$cmd uid=2000 phase=$phase error=timeout\n${UsbModeEvidence.EXIT_MARKER}1\n"
            val result = UsbModeEvidence.parse(out, id, cmd)!!
            assertFalse(result.ok)
            assertFalse(result.completionKnown)
        }
        val before = "FAIL request=$id cmd=$cmd uid=2000 phase=BEFORE_WRITE error=missing service\n${UsbModeEvidence.EXIT_MARKER}1\n"
        assertTrue(UsbModeEvidence.parse(before, id, cmd)!!.completionKnown)
    }
    @Test fun acceptanceWithoutModeEvidenceNeverClearsTheRecoveryRecord() {
        val id = "a".repeat(32)
        val cmd = BydInstrumentCmd.SCMD_USBMODE_UDISK
        val result = UsbModeEvidence.parse("RESULT request=$id cmd=$cmd uid=2000 rc=0 state=COMMAND_ACCEPTED\n${UsbModeEvidence.EXIT_MARKER}0", id, cmd)!!
        assertTrue(result.ok)
        assertFalse(result.completionKnown)
    }
    @Test fun processLockRemainsHeldAcrossGcAndReleasesOnExceptionalExit() {
        val path = Files.createTempFile("openbyd-lock", ".lock").toFile()
        try {
            try { VehicleProcessLock.acquire(path).use {
                repeat(3) {
                    System.gc()
                    try { VehicleProcessLock.acquire(path).close(); fail("second controller acquired lock") }
                    catch (_: java.nio.channels.OverlappingFileLockException) { }
                }
                error("simulated work failure")
            } } catch (_: IllegalStateException) { }
            VehicleProcessLock.acquire(path).close()
        } finally { path.delete() }
    }
}
