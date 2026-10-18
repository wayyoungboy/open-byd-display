package org.openbyddisplay.vehicle

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkProbe {
    data class Iface(val name: String, val address: String)

    fun privateIpv4Interfaces(): List<Iface> {
        val out = mutableListOf<Iface>()
        val en = NetworkInterface.getNetworkInterfaces() ?: return out
        while (en.hasMoreElements()) {
            val ni = en.nextElement()
            if (!ni.isUp || ni.isLoopback) continue
            val addrs = ni.inetAddresses
            while (addrs.hasMoreElements()) {
                val a = addrs.nextElement()
                if (a is Inet4Address && isPrivate(a.hostAddress)) {
                    out.add(Iface(ni.name, a.hostAddress ?: continue))
                }
            }
        }
        return out
    }

    private fun isPrivate(ip: String?): Boolean {
        if (ip == null) return false
        return ip.startsWith("10.") ||
            ip.startsWith("192.168.") ||
            ip.matches(Regex("""172\.(1[6-9]|2\d|3[0-1])\..*"""))
    }

    fun formatReport(detected: ClusterHostDetector.Result? = null): String {
        val list = privateIpv4Interfaces()
        val detect = try { detected ?: ClusterHostDetector.detect() } catch (e: Exception) {
            return "探测失败: ${e.message}\n" + list.joinToString("\n") { "${it.name}: ${it.address}" }
        }
        val lines = mutableListOf<String>()
        lines += "仪表: ${detect.host} (${detect.method}${if (detect.reachable) " ✓" else " ×"})"
        if (detect.iface != null) lines += "NCM: ${detect.iface} local=${detect.local}"
        if (list.isEmpty()) {
            lines += "未发现私网地址。请确认 USB NCM 已连通。"
        } else {
            lines += list.joinToString("\n") { "${it.name}: ${it.address}" }
        }
        return lines.joinToString("\n")
    }
}
