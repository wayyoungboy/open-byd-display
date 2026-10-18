package org.openbyddisplay.vehicle

import org.openbyddisplay.ambient.AmbientAdb as LocalAdbShell

import org.junit.Assert.*
import org.junit.Test

class UsbModeEvidenceTest {
    private val request = "a".repeat(32)
    private val cast = BydInstrumentCmd.SCMD_USBMODE_CAST
    private val udisk = BydInstrumentCmd.SCMD_USBMODE_UDISK
    private fun iface(name: String = "usb0", driver: String = "cdc_ncm", mac: String = UsbModeEvidence.CLUSTER_MAC,
                      up: Boolean = false, addresses: List<UsbModeEvidence.Address> = emptyList()) =
        UsbModeEvidence.Interface(name, driver, mac, up, addresses)
    private fun result(cmd: String = cast, state: String = "NETWORK_CONFIGURED", id: String = request,
                       uid: Int = 2000, rc: Int = 0, exit: Int = 0) =
        "RESULT request=$id cmd=$cmd uid=$uid rc=$rc state=$state iface=usb0\n${UsbModeEvidence.EXIT_MARKER}$exit\n"

    @Test fun newlyEnumeratedDownInterfaceIsSelectedButNotReady() {
        val observed = UsbModeEvidence.selectInterface(listOf(iface()))!!
        assertFalse(observed.configured)
    }
    @Test fun configuredRequiresUpAndExactAddressPrefix() {
        val address = UsbModeEvidence.Address("192.168.77.2", 30)
        assertTrue(iface(up = true, addresses = listOf(address)).configured)
        assertFalse(iface(addresses = listOf(address)).configured)
        assertFalse(iface(up = true, addresses = listOf(address.copy(prefix = 24))).configured)
    }
    @Test fun matchingMacWithoutNcmDriverDoesNotAuthorizeConfiguration() {
        assertNull(UsbModeEvidence.selectInterface(listOf(iface(driver = "rndis_host"))))
    }
    @Test fun unrelatedNcmAdapterIsIgnored() {
        assertNull(UsbModeEvidence.selectInterface(listOf(iface(mac = "aa:bb:cc:dd:ee:ff"))))
    }
    @Test(expected = IllegalStateException::class) fun refusesMultipleClusterCandidates() {
        UsbModeEvidence.selectInterface(listOf(iface(), iface(name = "eth0")))
    }
    @Test(expected = IllegalStateException::class) fun refusesToOverwriteAnotherStaticNetwork() {
        UsbModeEvidence.selectInterface(listOf(iface(addresses = listOf(UsbModeEvidence.Address("10.0.0.2", 24)))))
    }
    @Test fun completedConfigurationHasItsOwnStatus() {
        val observed = UsbModeEvidence.parse(result(), request, cast)!!
        assertTrue(observed.ok)
        assertEquals(BydInstrumentCmd.Status.NETWORK_CONFIGURED, observed.status)
        assertTrue(observed.detail.contains("192.168.77.2/30"))
    }
    @Test fun partialResultAndOldOkTextCannotCompleteARequest() {
        assertNull(UsbModeEvidence.parse(result().substringBefore(UsbModeEvidence.EXIT_MARKER), request, cast))
        assertNull(UsbModeEvidence.parse("OK cmd=$cast rc=0 uid=2000", request, cast))
    }
    @Test fun rejectsStaleRequestOppositeCommandAndWrongUid() {
        assertFalse(UsbModeEvidence.parse(result(id = "b".repeat(32)), request, cast)!!.ok)
        assertFalse(UsbModeEvidence.parse(result(cmd = udisk), request, cast)!!.ok)
        assertFalse(UsbModeEvidence.parse(result(uid = 10186), request, cast)!!.ok)
    }
    @Test fun successLookingResultCannotHideSdkOrProcessFailure() {
        assertFalse(UsbModeEvidence.parse(result(rc = -1), request, cast)!!.ok)
        assertFalse(UsbModeEvidence.parse(result(exit = 1), request, cast)!!.ok)
    }
    @Test fun castCannotSucceedWithOnlyCommandAcceptance() {
        assertFalse(UsbModeEvidence.parse(result(state = "COMMAND_ACCEPTED"), request, cast)!!.ok)
    }
    @Test fun udiskDistinguishesObservedDisconnectFromAcceptedCommand() {
        assertEquals(BydInstrumentCmd.Status.NCM_DISCONNECTED,
            UsbModeEvidence.parse(result(cmd = udisk, state = "NCM_DISCONNECTED"), request, udisk)!!.status)
        assertEquals(BydInstrumentCmd.Status.COMMAND_ACCEPTED,
            UsbModeEvidence.parse(result(cmd = udisk, state = "COMMAND_ACCEPTED"), request, udisk)!!.status)
    }
    @Test fun duplicateTerminalLinesAreRejected() {
        assertFalse(UsbModeEvidence.parse(result() + result(), request, cast)!!.ok)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsPathInjectionInRequestId() {
        LocalAdbShell.buildUsbModePollCommand("../../old.out; id")
    }
    @Test fun uncertainTransportAllowsPollingButNeverCountsAsCompletedSuccess() {
        val start = LocalAdbShell.ExecResult(false, "STARTED", "read timeout", commandMayBeRunning = true)
        assertTrue(UsbModeEvidence.mayPollResult(start))
        assertNull(UsbModeEvidence.parse(start.output, request, cast))
    }
    @Test fun knownLaunchFailureAndAuthFailureDoNotPoll() {
        assertFalse(UsbModeEvidence.mayPollResult(LocalAdbShell.ExecResult(false, "", "unauthorized")))
        assertFalse(UsbModeEvidence.mayPollResult(LocalAdbShell.ExecResult(true, "START_FAILED", "exit=0")))
        assertTrue(UsbModeEvidence.mayPollResult(LocalAdbShell.ExecResult(true, "STARTED", "exit=0")))
    }

    @Test fun waitsForDelayedKernelAddressAndUpReadback() {
        val ready = iface(up = true, addresses = listOf(UsbModeEvidence.Address("192.168.77.2", 30)))
        val snapshots = listOf(iface(), iface(up = true), ready, ready)
        var reads = 0
        var now = 0L
        val observed = UsbModeEvidence.awaitConfiguredInterface("usb0",
            { snapshots[(reads++).coerceAtMost(snapshots.lastIndex)] },
            { now }, { now += it })
        assertEquals(ready, observed)
        assertEquals(4, reads)
        assertEquals(750L, now)
    }

    @Test fun disappearingInterfaceResetsStableReadbackConfirmation() {
        val ready = iface(up = true, addresses = listOf(UsbModeEvidence.Address("192.168.77.2", 30)))
        val snapshots = listOf(ready, null, ready, ready)
        var reads = 0
        var now = 0L
        assertEquals(ready, UsbModeEvidence.awaitConfiguredInterface("usb0",
            { snapshots[(reads++).coerceAtMost(snapshots.lastIndex)] },
            { now }, { now += it }))
        assertEquals(4, reads)
    }

    @Test(expected = IllegalStateException::class) fun changedInterfaceCannotConfirmTheOldConfiguration() {
        UsbModeEvidence.awaitConfiguredInterface("usb0", {
            iface(name = "eth0", up = true, addresses = listOf(UsbModeEvidence.Address("192.168.77.2", 30)))
        })
    }

    @Test fun wrongPrefixStillFailsAfterTheReadbackDeadline() {
        var now = 0L
        try {
            UsbModeEvidence.awaitConfiguredInterface("usb0",
                { iface(up = true, addresses = listOf(UsbModeEvidence.Address("192.168.77.2", 24))) },
                { now }, { now += it }, timeoutMs = 1000)
            fail("Wrong prefix must not be accepted")
        } catch (expected: IllegalStateException) {
            assertEquals(1000L, now)
            assertTrue(expected.message!!.contains("未稳定读回"))
        }
    }
}
