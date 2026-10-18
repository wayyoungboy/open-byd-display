package org.openbyddisplay.vehicle

import org.openbyddisplay.ambient.AmbientAdb as LocalAdbShell

import org.junit.Assert.*
import org.junit.Test

class UsbModeDetectorTest {
    private fun observe(iface: String? = null, local: String? = null,
                        tcp: Boolean = false, driver: String = "driver=?") =
        UsbModeDetector.fromObservation(
            ClusterHostDetector.Result("192.168.77.1", local, iface, "fixture", tcp), driver)

    @Test fun missingNetworkDoesNotConfirmUdisk() {
        val result = observe()
        assertEquals(UsbModeDetector.Mode.UNKNOWN, result.mode)
        assertFalse(result.peerReachable)
    }

    @Test fun genericTcpPeerDoesNotConfirmNcmOrInstrumentIdentity() {
        val result = observe("wlan0", "192.168.1.2", tcp = true)
        assertEquals(UsbModeDetector.Mode.UNKNOWN, result.mode)
        assertTrue(result.detail.contains("TCP 不验证仪表身份"))
    }

    @Test fun familiarAddressWithoutDriverIsOnlyAHint() {
        assertEquals(UsbModeDetector.Mode.UNKNOWN, observe("eth0", "192.168.77.2").mode)
    }

    @Test fun confirmedDriverWithoutTcpIsNetworkEvidenceOnly() {
        val result = observe("usb0", "192.168.77.2", driver = "driver=cdc_ncm")
        assertEquals(UsbModeDetector.Mode.CAST_NCM, result.mode)
        assertFalse(result.peerReachable)
        assertTrue(result.label.contains("端口未通"))
    }

    @Test fun confirmedDriverWithTcpStillDoesNotConfirmModeTransition() {
        val result = observe("usb0", "192.168.77.2", true, "driver=cdc_ncm")
        assertEquals(UsbModeDetector.Mode.CAST_NCM, result.mode)
        assertTrue(result.detail.contains("切换效果需另行核实"))
        assertFalse(result.label.contains("已生效"))
    }

    @Test fun cdcNcmWithoutAddressIsStillCastLink() {
        val result = observe("usb0", local = null, driver = "driver=cdc_ncm")
        assertEquals(UsbModeDetector.Mode.CAST_NCM, result.mode)
        assertTrue(result.label.contains("NCM"))
    }

    @Test fun shellDriverReadRestoresEvidenceHiddenFromTheApp() {
        val driver = UsbModeDetector.driverHintFromShell("/sys/bus/usb/drivers/cdc_ncm\r\n")
        assertEquals("driver=cdc_ncm", driver)
        assertEquals(UsbModeDetector.Mode.CAST_NCM,
            observe("usb0", "192.168.77.2", tcp = true, driver = driver!!).mode)
    }

    @Test fun shellErrorsAndOtherDriversCannotConfirmNcm() {
        for (output in listOf("", "Permission denied", "cdc_ncm", "/sys/bus/usb/drivers/rndis_host",
            "/sys/bus/usb/drivers/cdc_ncm\nERROR", "/sys/bus/usb/drivers/not_cdc_ncm"))
            assertNull(UsbModeDetector.driverHintFromShell(output))
    }
}
