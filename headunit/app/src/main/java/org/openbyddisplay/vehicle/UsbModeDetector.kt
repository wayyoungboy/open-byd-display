package org.openbyddisplay.vehicle

import org.openbyddisplay.ambient.AmbientAdb as LocalAdbShell

import android.content.Context
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/** Network observations only. No network evidence can positively confirm U-disk mode. */
object UsbModeDetector {

    enum class Mode { CAST_NCM, UDISK_OR_HOST, UNKNOWN }

    data class Snapshot(
        val mode: Mode,
        val label: String,
        val detail: String,
        val ncmIface: String?,
        val localIp: String?,
        val peerIp: String?,
        val peerReachable: Boolean
    )

    fun detect(context: Context? = null): Snapshot {
        val host = try {
            ClusterHostDetector.detect(500)
        } catch (e: Exception) {
            return Snapshot(
                Mode.UNKNOWN, "未知",
                "探测异常: ${e.message}", null, null, null, false
            )
        }
        var driver = ncmDriverHint(host.iface)
        val iface = host.iface
        // Android can hide sysfs driver links from apps while the authorized
        // shell can still read them. Do not substitute a successful TCP probe.
        if (!driver.contains("cdc_ncm") && context != null &&
            iface?.matches(Regex("(?:usb|eth)[0-9]+")) == true) {
            val read = LocalAdbShell.exec(context, "readlink -f /sys/class/net/$iface/device/driver")
            if (read.ok) driver = driverHintFromShell(read.output) ?: driver
        }
        return fromObservation(host, driver)
    }

    internal fun driverHintFromShell(output: String): String? {
        val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val path = lines.singleOrNull() ?: return null
        return if (path.matches(Regex("/sys/(?:[A-Za-z0-9_.:-]+/)+cdc_ncm"))) "driver=cdc_ncm" else null
    }

    data class HardwareNcm(val present: Boolean, val detail: String)

    /** Sysfs/iface evidence only — works even when usb0 is DOWN / has no IPv4. */
    fun detectHardwareNcm(): HardwareNcm {
        val sys = File("/sys/class/net")
        val names = sys.listFiles()?.map { it.name } ?: emptyList()
        for (name in names) {
            if (!name.matches(Regex("""(?:usb|eth)\d+"""))) continue
            val driverPath = File(sys, "$name/device/driver")
            val driver = try {
                if (driverPath.exists()) driverPath.canonicalFile.name else ""
            } catch (_: Exception) { "" }
            val isNcm = driver == "cdc_ncm" || driver.contains("cdc_ncm")
            val ni = try { NetworkInterface.getByName(name) } catch (_: Exception) { null }
            val mac = try { ni?.hardwareAddress } catch (_: Exception) { null }
            val macHint = mac != null && mac.size == 6 &&
                mac[0] == 2.toByte() && mac[1] == 0.toByte() && mac[2] == 0.toByte() &&
                mac[3] == 0.toByte() && mac[4] == 7.toByte() && mac[5] == 2.toByte()
            if (isNcm || macHint) {
                val up = try { ni?.isUp == true } catch (_: Exception) { false }
                val local = try {
                    ni?.interfaceAddresses?.firstOrNull {
                        val a = it.address
                        a is Inet4Address && !a.isLoopbackAddress
                    }?.address?.hostAddress
                } catch (_: Exception) { null }
                return HardwareNcm(true,
                    "iface=$name driver=${driver.ifEmpty { "?" }} up=$up local=${local ?: "无"} macHint=$macHint")
            }
        }
        return HardwareNcm(false, "no usb/eth cdc_ncm")
    }

    internal fun fromObservation(host: ClusterHostDetector.Result, driverHint: String): Snapshot {
        val confirmedNcm = driverHint == "driver=cdc_ncm" || driverHint.contains("cdc_ncm")
        val hw = if (!confirmedNcm && host.iface == null) detectHardwareNcm() else null
        val ncm = confirmedNcm || (hw?.present == true)
        val label = when {
            ncm && host.reachable -> "NCM 网络已发现（TCP 可达）"
            ncm && host.local != null -> "NCM 网络已发现（端口未通）"
            ncm -> "NCM 链路已发现（接口未配置 IP）"
            host.reachable -> "模式未知（TCP 可达）"
            else -> "模式未知（未确认 NCM）"
        }
        val iface = host.iface ?: hw?.detail?.substringAfter("iface=", "")?.substringBefore(" ")
            ?.takeIf { it.isNotBlank() }
        val driver = if (confirmedNcm) driverHint else (hw?.detail ?: driverHint)
        return Snapshot(
            if (ncm) Mode.CAST_NCM else Mode.UNKNOWN,
            label,
            "iface=${iface ?: "?"} local=${host.local ?: "?"} peer=${host.host} " +
                "method=${host.method} tcp=${host.reachable} $driver；" +
                "TCP 不验证仪表身份；网络缺失不能确认 U盘模式，切换效果需另行核实。",
            iface, host.local, host.host, host.reachable
        )
    }

    private fun ncmDriverHint(iface: String?): String {
        if (iface.isNullOrBlank()) {
            val hw = detectHardwareNcm()
            return if (hw.present) hw.detail else "no-iface"
        }
        val f = File("/sys/class/net/$iface/device/driver")
        return try {
            if (f.exists()) "driver=${f.canonicalFile.name}" else "driver=?"
        } catch (_: Exception) {
            "driver=?"
        }
    }

    fun listUsbRelatedIfaces(): String {
        val lines = mutableListOf<String>()
        val en = NetworkInterface.getNetworkInterfaces() ?: return "(no ifaces)"
        while (en.hasMoreElements()) {
            val ni = en.nextElement()
            if (!ni.isUp || ni.isLoopback) continue
            val addrs = ni.inetAddresses
            while (addrs.hasMoreElements()) {
                val a = addrs.nextElement()
                if (a is Inet4Address) {
                    lines += "${ni.name}: ${a.hostAddress}"
                }
            }
        }
        return if (lines.isEmpty()) "(none)" else lines.joinToString("\n")
    }
}
