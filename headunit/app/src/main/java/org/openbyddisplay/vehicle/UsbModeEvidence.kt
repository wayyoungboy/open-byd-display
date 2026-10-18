package org.openbyddisplay.vehicle

import org.openbyddisplay.ambient.AmbientAdb as LocalAdbShell

/** Shared, device-independent validation for the shell helper and its caller. */
internal object UsbModeEvidence {
    const val LOCAL_ADDRESS = "192.168.77.2"
    const val PREFIX = 30
    const val CLUSTER_MAC = "02:00:00:00:07:02"
    const val EXIT_MARKER = "__OPENBYDDISPLAY_USB_EXIT__="
    // This head unit can drop the reverse command during USB re-enumeration.
    const val UDISK_SETTLE_MS = 12_000L
    const val CAST_INTERFACE_WAIT_MS = 45_000L
    const val CONFIG_READBACK_WAIT_MS = 15_000L
    const val REQUEST_TIMEOUT_MS = 90_000L

    fun mayPollResult(start: LocalAdbShell.ExecResult): Boolean =
        if (start.ok) start.output.lineSequence().any { it.trim() == "STARTED" }
        else start.commandMayBeRunning

    data class Address(val ip: String, val prefix: Int)
    data class Interface(val name: String, val driver: String, val mac: String,
                         val up: Boolean, val addresses: List<Address>) {
        val configured: Boolean get() = up && Address(LOCAL_ADDRESS, PREFIX) in addresses
    }
    /** Losing a configured address does not prove that the old NCM interface disappeared. */
    fun oldInterfaceGone(before: Interface?, observed: List<Interface>, sysfsNames: Set<String>?): Boolean =
        before != null && sysfsNames != null && before.name !in sysfsNames && observed.none { it.name == before.name }

    fun selectInterface(interfaces: List<Interface>): Interface? {
        val candidates = interfaces.filter {
            it.name.matches(Regex("(?:usb|eth)[0-9]+")) && it.driver == "cdc_ncm" &&
                (it.mac.equals(CLUSTER_MAC, ignoreCase = true) || Address(LOCAL_ADDRESS, PREFIX) in it.addresses)
        }
        check(candidates.size <= 1) { "多个仪表 NCM 网卡，拒绝猜测" }
        return candidates.singleOrNull()?.also { candidate ->
            check(candidate.addresses.none { it.ip != LOCAL_ADDRESS && !it.ip.startsWith("169.254.") }) {
                "${candidate.name} 已有其他 IPv4 地址，未覆盖配置"
            }
        }
    }

    /** Netd returning does not mean a fresh kernel interface snapshot is ready yet. */
    fun awaitConfiguredInterface(
        expectedName: String,
        read: () -> Interface?,
        elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000L },
        pause: (Long) -> Unit = { Thread.sleep(it) },
        timeoutMs: Long = CONFIG_READBACK_WAIT_MS
    ): Interface {
        require(timeoutMs > 0)
        val started = elapsedMillis()
        var consecutive = 0
        var last: Interface?
        while (true) {
            val observed = read()
            last = observed
            check(observed == null || observed.name == expectedName) {
                "NCM 网卡由 $expectedName 变为 ${observed?.name}，未确认原接口配置"
            }
            consecutive = if (observed?.configured == true) consecutive + 1 else 0
            if (consecutive >= 2 && observed != null) return observed
            val remaining = timeoutMs - (elapsedMillis() - started)
            if (remaining <= 0) break
            pause(minOf(250L, remaining))
        }
        val detail = last?.let { "${it.name} up=${it.up} ipv4=${it.addresses}" } ?: "目标网卡未出现"
        error("NCM 网卡地址或启用状态在 ${timeoutMs / 1000} 秒内未稳定读回：$detail")
    }

    fun validateRequest(request: String) {
        require(request.matches(Regex("[a-f0-9]{32}"))) { "Invalid USB request id" }
    }

    /** Null means running. Completed output must match this request, command and shell UID. */
    fun parse(output: String, request: String, cmd: String): BydInstrumentCmd.Result? {
        validateRequest(request)
        val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val exit = lines.lastOrNull()?.takeIf { it.startsWith(EXIT_MARKER) }
            ?.removePrefix(EXIT_MARKER)?.toIntOrNull() ?: return null
        val channel = "adb-shell/BYDAutoManager"
        fun failed(detail: String, completed: Boolean = false) = BydInstrumentCmd.Result(false, channel, cmd, detail, completionKnown = completed)
        if (exit != 0) return failed("shell helper exit=$exit；${lines.dropLast(1).takeLast(4).joinToString(" | ")}",
            lines.count { it.matches(Regex("FAIL request=$request cmd=$cmd uid=-?\\d+ phase=BEFORE_WRITE error=.+")) } == 1 &&
                lines.none { it.startsWith("SDK_RETURN ") || it.startsWith("RESULT ") })
        val result = lines.singleOrNull { it.startsWith("RESULT ") }
            ?: return failed("shell helper 没有唯一的完整结果")
        val match = Regex("RESULT request=([a-f0-9]{32}) cmd=(SCMD_USBMODE[12]) uid=(\\d+) rc=(-?\\d+) state=([A-Z_]+)(?: iface=([a-z0-9]+))?")
            .matchEntire(result) ?: return failed("shell helper 结果格式不完整")
        val (actualRequest, actualCmd, uid, rc, state) = match.destructured
        if (actualRequest != request || actualCmd != cmd || uid != "2000" || rc != "0") {
            return failed("shell helper 请求、命令、UID 或返回值不匹配：$result")
        }
        val status = when {
            cmd == BydInstrumentCmd.SCMD_USBMODE_CAST && state == "NETWORK_CONFIGURED" &&
                match.groupValues[6].matches(Regex("(?:usb|eth)[0-9]+")) -> BydInstrumentCmd.Status.NETWORK_CONFIGURED
            cmd == BydInstrumentCmd.SCMD_USBMODE_UDISK && state == "NCM_DISCONNECTED" -> BydInstrumentCmd.Status.NCM_DISCONNECTED
            cmd == BydInstrumentCmd.SCMD_USBMODE_UDISK && state == "COMMAND_ACCEPTED" -> BydInstrumentCmd.Status.COMMAND_ACCEPTED
            else -> return failed("shell helper 状态与目标模式不匹配：$result")
        }
        val detail = when (status) {
            BydInstrumentCmd.Status.NETWORK_CONFIGURED -> "${match.groupValues[6]} 已启用，地址 $LOCAL_ADDRESS/$PREFIX；仪表服务连通性见网络检测"
            BydInstrumentCmd.Status.NCM_DISCONNECTED -> "命令返回 0，原 NCM 网卡已断开；U盘模式请在仪表确认"
            else -> "命令返回 0；尚未确认 NCM 网卡断开或U盘模式"
        }
        return BydInstrumentCmd.Result(true, channel, cmd, detail, status,
            completionKnown = status != BydInstrumentCmd.Status.COMMAND_ACCEPTED)
    }
}
