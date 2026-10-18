package org.openbyddisplay.vehicle

import android.annotation.SuppressLint
import android.content.Context
import android.os.IBinder
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import kotlin.system.exitProcess

/** Own APK entry point executed by authorized local ADB. Uses system services only. */
object UsbModeShellHelper {
    @JvmStatic
    fun main(args: Array<String>) { exitProcess(runRequest(args)) }

    private fun runRequest(args: Array<String>): Int {
        var lease: VehicleProcessLock? = null
        var writeAttempted = false
        var uid = -1
        val cmd = args.getOrNull(0).orEmpty()
        val request = args.getOrNull(1).orEmpty()
        try {
            require(args.size == 2 && cmd in setOf(BydInstrumentCmd.SCMD_USBMODE_CAST, BydInstrumentCmd.SCMD_USBMODE_UDISK, "CHECK_USB_IDLE")) { "Invalid USB command" }
            UsbModeEvidence.validateRequest(request)
            uid = android.os.Process.myUid()
            check(uid == 2000) { "需要已授权的 ADB shell uid=2000，实际 uid=$uid" }
            lease = VehicleProcessLock.acquire(File("/data/local/tmp/openbyddisplay-usb.lock"))
            if (cmd == "CHECK_USB_IDLE") {
                println("QUIESCENT request=$request uid=2000")
                return 0
            }
            if (android.os.Looper.getMainLooper() == null) android.os.Looper.prepareMainLooper()
            val activityThread = Class.forName("android.app.ActivityThread")
            val thread = activityThread.getMethod("systemMain").invoke(null)
            val context = activityThread.getMethod("getSystemContext").invoke(thread) as Context
            val before = clusterInterface()
            var sentAt = System.nanoTime()
            // Already in NCM: repair/configure that interface without bouncing USB again.
            if (cmd != BydInstrumentCmd.SCMD_USBMODE_CAST || before == null) {
                // DiLink's vendor service name is absent from the standard Android SDK constants.
                @SuppressLint("WrongConstant")
                val auto = context.getSystemService("auto") ?: error("系统 auto 服务不可用")
                val feature = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds")
                    .getField("INSTRUMENT_TARGET_NEXT_PATHNAME_INFO_SET").getInt(null)
                check(feature != 0) { "仪表 USB 命令字段不可用" }
                writeAttempted = true // Set before a call which may mutate and then throw.
                val rc = auto.javaClass.getMethod("setBuffer", Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, ByteArray::class.java)
                    .invoke(auto, 1007, feature, cmd.toByteArray(Charsets.UTF_16LE))
                println("SDK_RETURN cmd=$cmd uid=$uid rc=$rc；设备效果尚未确认")
                System.out.flush()
                check(rc is Int && rc == 0) { "BYDAutoManager.setBuffer 返回 $rc" }
                sentAt = System.nanoTime()
            }
            if (cmd == BydInstrumentCmd.SCMD_USBMODE_CAST) {
                val deadline = System.nanoTime() + UsbModeEvidence.CAST_INTERFACE_WAIT_MS * 1_000_000L
                var iface = clusterInterface()
                while (iface == null && System.nanoTime() < deadline) {
                    Thread.sleep(250)
                    iface = clusterInterface()
                }
                val found = iface ?: error("命令返回 0，但 ${UsbModeEvidence.CAST_INTERFACE_WAIT_MS / 1000} 秒内未发现仪表 cdc_ncm 网卡")
                if (!found.configured) writeAttempted = true
                configureInterface(found)
                UsbModeEvidence.awaitConfiguredInterface(found.name, ::clusterInterface)
                println("RESULT request=$request cmd=$cmd uid=$uid rc=0 state=NETWORK_CONFIGURED iface=${found.name}")
            } else {
                val deadline = System.nanoTime() + 10_000_000_000L
                var disconnected = oldInterfaceGone(before)
                while (before != null && !disconnected && System.nanoTime() < deadline) {
                    Thread.sleep(250)
                    disconnected = oldInterfaceGone(before)
                }
                // Disconnecting the NCM interface is earlier than the instrument
                // finishing the mode transition. Hold the request through settling
                // so an immediate cast request is not silently ignored by firmware.
                val waitNanos = UsbModeEvidence.UDISK_SETTLE_MS * 1_000_000L - (System.nanoTime() - sentAt)
                if (waitNanos > 0) Thread.sleep((waitNanos + 999_999L) / 1_000_000L)
                disconnected = oldInterfaceGone(before)
                val state = if (disconnected) "NCM_DISCONNECTED" else "COMMAND_ACCEPTED"
                println("RESULT request=$request cmd=$cmd uid=$uid rc=0 state=$state")
            }
            return 0
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            println("FAIL request=$request cmd=$cmd uid=$uid phase=${if (writeAttempted) "AFTER_WRITE" else "BEFORE_WRITE"} error=${cause.javaClass.simpleName}: ${cause.message}")
            return 1
        } finally { lease?.close() }
    }

    private fun clusterInterface(): UsbModeEvidence.Interface? = UsbModeEvidence.selectInterface(observedInterfaces())

    private fun oldInterfaceGone(before: UsbModeEvidence.Interface?) = UsbModeEvidence.oldInterfaceGone(before,
        observedInterfaces(), File("/sys/class/net").list()?.toSet())

    private fun observedInterfaces(): List<UsbModeEvidence.Interface> {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: error("无法读取网卡")
        return Collections.list(interfaces).filter {
            it.name.matches(Regex("(?:usb|eth)[0-9]+"))
        }.map { ni ->
            val driver = try { File("/sys/class/net/${ni.name}/device/driver").canonicalFile.name }
                catch (_: Exception) { "" }
            UsbModeEvidence.Interface(ni.name, driver,
                ni.hardwareAddress?.joinToString(":") { "%02x".format(it.toInt() and 0xff) }.orEmpty(),
                ni.isUp, ni.interfaceAddresses.mapNotNull {
                    val ip = it.address as? Inet4Address ?: return@mapNotNull null
                    UsbModeEvidence.Address(ip.hostAddress.orEmpty(), it.networkPrefixLength.toInt())
                })
        }
    }

    private fun configureInterface(iface: UsbModeEvidence.Interface) {
        if (iface.configured) return
        val service = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "network_management") ?: error("系统网络管理服务不可用")
        val manager = Class.forName("android.os.INetworkManagementService\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, service)
        val management = Class.forName("android.os.INetworkManagementService")
        val configuration = Class.forName("android.net.InterfaceConfiguration")
        val linkAddress = Class.forName("android.net.LinkAddress")
        val config = management.getMethod("getInterfaceConfig", String::class.java)
            .invoke(manager, iface.name) ?: error("未取得 ${iface.name} 配置")
        configuration.getMethod("setLinkAddress", linkAddress).invoke(config,
            linkAddress.getConstructor(String::class.java).newInstance("${UsbModeEvidence.LOCAL_ADDRESS}/${UsbModeEvidence.PREFIX}"))
        configuration.getMethod("setInterfaceUp").invoke(config)
        management.getMethod("setInterfaceConfig", String::class.java, configuration).invoke(manager, iface.name, config)
    }
}
