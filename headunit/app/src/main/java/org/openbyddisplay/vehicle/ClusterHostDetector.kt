package org.openbyddisplay.vehicle

import android.util.Log
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * Observe instrument NCM peer (default 192.168.77.1); TCP reachability is not protocol identity:
 * 1) Find usbN/ethN with cdc_ncm driver or 192.168.77.2/30
 * 2) Derive /30 peer from local IPv4 (x.x.x.y/30 → peer = base+1 or base+2)
 * 3) Fallback: probe candidate hosts on TCP 47783/47785
 */
object ClusterHostDetector {
    private const val TAG = "OpenBYDHostDetect"
    const val DEFAULT_LOCAL = "192.168.77.2"
    const val DEFAULT_PEER = "192.168.77.1"
    private val PROBE_PORTS = intArrayOf(47782, 47785)

    data class Result(
        val host: String,
        val local: String?,
        val iface: String?,
        val method: String,
        val reachable: Boolean
    )

    fun detect(timeoutMs: Int = 400): Result {
        // Preferred: NCM iface → /30 peer
        findNcmIface()?.let { ni ->
            val local = ipv4On(ni)
            val peer = peerFromPrefix30(local) ?: DEFAULT_PEER
            val ok = probe(peer, timeoutMs, local?.hostAddress)
            if (ok) {
                return Result(peer, local?.hostAddress, ni.name, "ncm-/30", true)
            }
            // Still return peer even if probe fails (NCM may be up, overlay restarting)
            return Result(peer, local?.hostAddress, ni.name, "ncm-/30-unacked", false)
        }

        // Any iface with 192.168.77.2
        for (ni in ifaces()) {
            for (ia in ni.interfaceAddresses) {
                val a = ia.address
                if (a is Inet4Address && a.hostAddress == DEFAULT_LOCAL) {
                    val peer = if (ia.networkPrefixLength.toInt() == 30) {
                        peerFromPrefix30(a) ?: DEFAULT_PEER
                    } else DEFAULT_PEER
                    val ok = probe(peer, timeoutMs, DEFAULT_LOCAL)
                    return Result(peer, DEFAULT_LOCAL, ni.name, "addr-77.2", ok)
                }
            }
        }

        // Scan private /30-ish candidates from all private IPv4s
        for (ni in ifaces()) {
            for (ia in ni.interfaceAddresses) {
                val a = ia.address as? Inet4Address ?: continue
                if (!a.isSiteLocalAddress || ia.networkPrefixLength.toInt() != 30) continue
                val peer = peerFromPrefix30(a) ?: continue
                if (probe(peer, timeoutMs, a.hostAddress)) {
                    return Result(peer, a.hostAddress, ni.name, "scan-/30", true)
                }
            }
        }

        // Last resort: classic default
        val ok = probe(DEFAULT_PEER, timeoutMs, DEFAULT_LOCAL)
        return Result(DEFAULT_PEER, null, null, "default", ok)
    }

    fun probe(host: String, timeoutMs: Int = 400, bindLocal: String? = null): Boolean {
        for (port in PROBE_PORTS) {
            try {
                Socket().use { s ->
                    s.tcpNoDelay = true
                    s.soTimeout = timeoutMs
                    if (bindLocal != null) {
                        // Do not silently probe through another network after bind failure.
                        s.bind(InetSocketAddress(InetAddressCompat(bindLocal), 0))
                    }
                    s.connect(InetSocketAddress(host, port), timeoutMs)
                    return true
                }
            } catch (_: Exception) {
            }
        }
        return false
    }

    private fun InetAddressCompat(host: String) = java.net.InetAddress.getByName(host)

    private fun findNcmIface(): NetworkInterface? {
        val sys = File("/sys/class/net")
        val names = sys.listFiles()?.map { it.name } ?: emptyList()
        var hit: NetworkInterface? = null
        for (name in names) {
            if (!name.matches(Regex("""(?:usb|eth)\d+"""))) continue
            val driver = try {
                File(sys, "$name/device/driver").canonicalPath
            } catch (_: Exception) {
                ""
            }
            val isNcm = driver.endsWith("/cdc_ncm") || driver.contains("cdc_ncm")
            val ni = try { NetworkInterface.getByName(name) } catch (_: Exception) { null } ?: continue
            // Do not require isUp: cdc_ncm may exist while operstate=DOWN / no IPv4 yet.
            val has772 = ni.interfaceAddresses.any {
                (it.address as? Inet4Address)?.hostAddress == DEFAULT_LOCAL &&
                    it.networkPrefixLength.toInt() == 30
            }
            // Cluster MAC hint 02:00:00:00:07:02
            val mac = try { ni.hardwareAddress } catch (_: Exception) { null }
            val macHint = mac != null && mac.size == 6 &&
                mac[0] == 2.toByte() && mac[1] == 0.toByte() && mac[2] == 0.toByte() &&
                mac[3] == 0.toByte() && mac[4] == 7.toByte() && mac[5] == 2.toByte()
            if (isNcm || has772 || macHint) {
                if (hit != null && hit.name != ni.name) {
                    Log.w(TAG, "multiple NCM candidates ${hit.name} vs ${ni.name}")
                }
                hit = ni
                if (has772 || isNcm || macHint) return ni
            }
        }
        return hit
    }

    private fun ipv4On(ni: NetworkInterface): Inet4Address? {
        for (ia in ni.interfaceAddresses) {
            val a = ia.address
            if (a is Inet4Address && !a.isLinkLocalAddress) return a
        }
        return null
    }

    /** For a.b.c.d/30, peer is the other host address in the 4-address block. */
    fun peerFromPrefix30(local: Inet4Address?): String? {
        if (local == null) return null
        val b = local.address ?: return null
        if (b.size != 4) return null
        val ip = ((b[0].toInt() and 0xff) shl 24) or
            ((b[1].toInt() and 0xff) shl 16) or
            ((b[2].toInt() and 0xff) shl 8) or
            (b[3].toInt() and 0xff)
        val base = ip and 0xFFFFFFFC.toInt()
        val hostPart = ip and 3
        // /30: base+0 network, +1 hostA, +2 hostB, +3 broadcast
        val peer = when (hostPart) {
            1 -> base + 2
            2 -> base + 1
            else -> return null
        }
        return "${(peer ushr 24) and 0xff}.${(peer ushr 16) and 0xff}.${(peer ushr 8) and 0xff}.${peer and 0xff}"
    }

    private fun ifaces(): List<NetworkInterface> {
        val out = mutableListOf<NetworkInterface>()
        val en = NetworkInterface.getNetworkInterfaces() ?: return out
        while (en.hasMoreElements()) {
            val ni = en.nextElement()
            if (!ni.isLoopback) out.add(ni)
        }
        return out
    }
}
