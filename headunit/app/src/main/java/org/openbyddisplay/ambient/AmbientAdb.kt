package org.openbyddisplay.ambient

import org.openbyddisplay.vehicle.UsbModeEvidence
import org.openbyddisplay.vehicle.BydInstrumentCmd
import android.content.Context
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher

/**
 * Classic local ADB client (127.0.0.1:5555). Does not start adbd or implement TLS pairing.
 *
 * Auth follows AdbLib / AOSP protocol.txt:
 *  TOKEN#1 → SIGNATURE (ADB SHA1 padding + RSA/ECB/NoPadding)
 *  TOKEN#2 → RSAPUBLICKEY  (triggers "允许 USB 调试" on device)
 *  Keep socket open until CNXN or timeout while user taps Allow.
 */
internal object AmbientAdb {

    private const val A_CNXN = 0x4e584e43
    private const val A_AUTH = 0x48545541
    private const val A_OPEN = 0x4e45504f
    private const val A_OKAY = 0x59414b4f
    private const val A_CLSE = 0x45534c43
    private const val A_WRTE = 0x45545257
    private const val ADB_AUTH_TOKEN = 1
    private const val ADB_AUTH_SIGNATURE = 2
    private const val ADB_AUTH_RSAPUBLICKEY = 3
    private const val VERSION = 0x01000000
    private const val MAX_DATA = 1024 * 1024
    private const val KEY_LENGTH_BITS = 2048
    private const val KEY_LENGTH_BYTES = KEY_LENGTH_BITS / 8
    private const val KEY_LENGTH_WORDS = KEY_LENGTH_BYTES / 4

    /** PKCS#1 type-1 + DigestInfo(SHA-1) prefix used by classic adbd (AdbCrypto). */
    private val SIGNATURE_PADDING: ByteArray = intArrayOf(
        0x00, 0x01, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x00,
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00,
        0x04, 0x14
    ).map { it.toByte() }.toByteArray()

    data class ExecResult(val ok: Boolean, val output: String, val detail: String,
                          val commandMayBeRunning: Boolean = false)

    enum class AuthState { AUTHORIZED, NEED_CONFIRM, PORT_CLOSED, ERROR }

    data class AuthResult(val state: AuthState, val detail: String)

    /**
     * Connect 127.0.0.1:5555, present RSA keypair.
     * On first run: sends public key and **keeps the socket open** up to [confirmWaitMs]
     * so the system "允许 USB 调试" dialog can complete.
     */
    fun probeAuth(
        context: Context,
        host: String = "127.0.0.1",
        port: Int = 5555,
        confirmWaitMs: Long = 90_000L,
        allowKeyPrompt: Boolean = true
    ): AuthResult {
        return try {
            require(host == "127.0.0.1" && port == 5555) { "仅连接本机传统 ADB" }
            val keys = loadOrCreateKeys(context)
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 2500)
                socket.tcpNoDelay = true
                socket.soTimeout = 8_000
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())
                writeMsg(output, A_CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray())

                var connected = false
                var sentSignature = false
                var publicKeySent = false
                var pubkeySentAt = 0L

                val hardDeadline = System.currentTimeMillis() + confirmWaitMs + 20_000
                while (!connected && System.currentTimeMillis() < hardDeadline) {
                    if (publicKeySent) {
                        socket.soTimeout = 5_000
                    }
                    val msg = try {
                        readMsg(input)
                    } catch (e: java.net.SocketTimeoutException) {
                        if (publicKeySent &&
                            System.currentTimeMillis() - pubkeySentAt < confirmWaitMs
                        ) {
                            continue
                        }
                        if (publicKeySent) {
                            return AuthResult(
                                AuthState.NEED_CONFIRM,
                                "已提交本机密钥并等待超时。请确认车机弹出「允许 USB 调试」并点允许后，再点一次授权"
                            )
                        }
                        return AuthResult(AuthState.ERROR, "ADB 超时: ${e.message}")
                    } ?: return AuthResult(AuthState.ERROR, "ADB 读包失败（端口 $host:$port）")

                    when (msg.command) {
                        A_CNXN -> connected = true
                        A_AUTH -> {
                            if (msg.arg0 == ADB_AUTH_TOKEN) {
                                if (!sentSignature) {
                                    // First challenge: try signature with our key.
                                    sentSignature = true
                                    val sig = signToken(keys.private as RSAPrivateKey, msg.payload)
                                    writeMsg(output, A_AUTH, ADB_AUTH_SIGNATURE, 0, sig)
                                } else if (!publicKeySent) {
                                    if (!allowKeyPrompt) return AuthResult(AuthState.NEED_CONFIRM,
                                        "本应用密钥未获授权；请手动点「授权本机 ADB」。未提交公钥。")
                                    // Signature rejected → send public key to show system dialog.
                                    publicKeySent = true
                                    pubkeySentAt = System.currentTimeMillis()
                                    val pub = adbPublicKeyBytes(
                                        keys.public as RSAPublicKey, "openbyddisplay@local"
                                    )
                                    writeMsg(output, A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, pub)
                                }
                            }
                        }
                    }
                }

                when {
                    connected -> AuthResult(
                        AuthState.AUTHORIZED,
                        "ADB 握手已通过 ($host:$port)；执行 UID、BYD 权限和灯光能力仍需单独验证"
                    )
                    publicKeySent -> AuthResult(
                        AuthState.NEED_CONFIRM,
                        "已提交本机密钥，请在车机弹窗点「允许」USB 调试（始终允许）后重试"
                    )
                    sentSignature -> AuthResult(
                        AuthState.NEED_CONFIRM,
                        "签名未通过且未弹出授权窗，请重试授权"
                    )
                    else -> AuthResult(AuthState.ERROR, "ADB 握手无响应")
                }
            }
        } catch (e: java.net.ConnectException) {
            AuthResult(AuthState.PORT_CLOSED, "$host:$port 连接被拒绝；需已有传统 TCP adbd。本应用不会开启服务，Android 无线调试配对不等于此端口可用: ${e.message}")
        } catch (e: java.net.SocketTimeoutException) {
            AuthResult(AuthState.ERROR, "ADB 超时: ${e.message}")
        } catch (e: Exception) {
            AuthResult(AuthState.ERROR, "ADB ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun ensureKeypair(context: Context): File {
        loadOrCreateKeys(context)
        return File(File(context.filesDir, "adb"), "adbkey.pub")
    }

    fun exec(context: Context, command: String, host: String = "127.0.0.1", port: Int = 5555): ExecResult {
        return try {
            require(host == "127.0.0.1" && port == 5555) { "仅连接本机传统 ADB" }
            execWithKeys(loadOrCreateKeys(context), command, host, port)
        } catch (e: Exception) {
            ExecResult(false, "", "ADB ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    internal fun execWithKeys(keys: KeyPair, command: String, host: String, port: Int,
                              socketTimeoutMs: Int = 8_000): ExecResult {
        val out = ByteArrayOutputStream()
        var dispatched = false
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 3000)
                socket.soTimeout = socketTimeoutMs
                socket.tcpNoDelay = true
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())
                writeMsg(output, A_CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray())
                var connected = false
                var sentSignature = false
                repeat(16) {
                    if (connected) return@repeat
                    val msg = readMsg(input) ?: return ExecResult(false, "", "ADB read null (auth?)")
                    when (msg.command) {
                        A_CNXN -> connected = true
                        A_AUTH -> {
                            if (msg.arg0 == ADB_AUTH_TOKEN) {
                                if (!sentSignature) {
                                    sentSignature = true
                                    val sig = signToken(keys.private as RSAPrivateKey, msg.payload)
                                    writeMsg(output, A_AUTH, ADB_AUTH_SIGNATURE, 0, sig)
                                } else {
                                    return ExecResult(false, "", "ADB 密钥未获授权；请显式点「授权本机 ADB」，本次未提交公钥")
                                }
                            }
                        }
                    }
                }
                if (!connected) {
                    return ExecResult(
                        false, "",
                        "ADB 未授权：请先点「授权本机 ADB」，在车机弹窗点允许"
                    )
                }
                val localId = nextLocalId()
                val dest = "shell:${wrapShellCommand(command)}\u0000".toByteArray()
                dispatched = true // An OPEN can reach adbd even if writing/flushing its bytes then fails.
                writeMsg(output, A_OPEN, localId, 0, dest)
                var closed = false
                val deadline = System.currentTimeMillis() + 12_000
                while (!closed && System.currentTimeMillis() < deadline) {
                    val msg = readMsg(input) ?: break
                    when (msg.command) {
                        A_WRTE -> {
                            if (msg.arg1 == localId) {
                                if (out.size() + msg.payload.size > 256 * 1024) {
                                    return ExecResult(false, "", "ADB 输出超过 256 KiB，已中止读取", true)
                                }
                                out.write(msg.payload)
                                writeMsg(output, A_OKAY, localId, msg.arg0, ByteArray(0))
                            }
                        }
                        A_CLSE -> {
                            if (msg.arg1 != localId) continue
                            closed = true
                            writeMsg(output, A_CLSE, localId, msg.arg0, ByteArray(0))
                        }
                    }
                }
                return parseShellResult(out.toString("UTF-8"), closed)
                    .copy(commandMayBeRunning = !closed)
            }
        } catch (e: Exception) {
            // Losing the transport after OPEN does not cancel a detached command.
            // Preserve evidence and let callers verify their own request result.
            parseShellResult(out.toString("UTF-8"), false).copy(
                detail = "ADB ${e.javaClass.simpleName}: ${e.message}",
                commandMayBeRunning = dispatched)
        }
    }

    internal fun wrapShellCommand(command: String): String =
        "printf '__OPENBYDDISPLAY_UID__='; id -u; ( $command ); " +
            "result=\$?; printf '\\n__OPENBYDDISPLAY_EXIT__=%s\\n' \"\$result\""

    internal fun parseShellResult(raw: String, closed: Boolean): ExecResult {
        val lines = raw.trimEnd().lines()
        val uid = lines.firstOrNull()?.takeIf { it.startsWith("__OPENBYDDISPLAY_UID__=") }
            ?.removePrefix("__OPENBYDDISPLAY_UID__=")?.toIntOrNull()
        val exit = lines.lastOrNull()?.takeIf { it.startsWith("__OPENBYDDISPLAY_EXIT__=") }
            ?.substringAfter('=')?.toIntOrNull()
        val output = lines.drop(if (uid != null) 1 else 0)
            .dropLast(if (exit != null) 1 else 0).joinToString("\n").trim()
        val ok = closed && uid == 2000 && exit == 0
        return ExecResult(ok, output,
            "adb-shell uid=${uid ?: "未知"} exit=${exit ?: "未知"} streamClosed=$closed；不代表设备效果")
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun usbModeOutputPath(request: String): String {
        UsbModeEvidence.validateRequest(request)
        return "/data/local/tmp/openbyddisplay-usb-$request.out"
    }

    fun buildUsbModeCommand(apkPath: String, scmd: String, request: String): String {
        require(scmd == BydInstrumentCmd.SCMD_USBMODE_CAST || scmd == BydInstrumentCmd.SCMD_USBMODE_UDISK) {
            "Unsupported USB command"
        }
        val out = shellQuote(usbModeOutputPath(request))
        val inner = "CLASSPATH=${shellQuote(apkPath)} app_process /system/bin " +
            "org.openbyddisplay.vehicle.UsbModeShellHelper $scmd $request > $out 2>&1; " +
            "result=\$?; printf '\\n${UsbModeEvidence.EXIT_MARKER}%s\\n' \"\$result\" >> $out"
        // Keep the ADB stream open until nohup has opened the result file. Closing it
        // immediately after '&' can kill a not-yet-detached child on the device shell.
        return "umask 077; nohup sh -c ${shellQuote(inner)} </dev/null >/dev/null 2>&1 & " +
            "attempt=0; while [ ! -f $out ] && [ \$attempt -lt 40 ]; do " +
            "sleep 0.05; attempt=\$((attempt + 1)); done; " +
            "if [ -f $out ]; then echo STARTED; else echo START_FAILED; exit 1; fi"
    }

    fun buildUsbModePollCommand(request: String): String {
        val out = shellQuote(usbModeOutputPath(request))
        return "if [ -f $out ]; then cat $out; else echo __OPENBYDDISPLAY_USB_PENDING__; fi"
    }

    fun buildUsbModeCleanupCommand(request: String): String = "rm -f ${shellQuote(usbModeOutputPath(request))}"

    // --- ADB framing ---

    internal data class Msg(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)

    private val localIdSeq = AtomicInteger(1)
    private fun nextLocalId() = localIdSeq.getAndIncrement()

    internal fun writeMsg(out: DataOutputStream, command: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        val buf = ByteBuffer.allocate(24 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(command)
        buf.putInt(arg0)
        buf.putInt(arg1)
        buf.putInt(payload.size)
        buf.putInt(payload.fold(0) { a, b -> a + (b.toInt() and 0xff) })
        buf.putInt(command xor 0xffffffff.toInt())
        buf.put(payload)
        out.write(buf.array())
        out.flush()
    }

    internal fun readMsg(input: DataInputStream): Msg? {
        val hdr = ByteArray(24)
        input.readFully(hdr)
        val buf = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        val command = buf.int
        val arg0 = buf.int
        val arg1 = buf.int
        val len = buf.int
        val checksum = buf.int
        val magic = buf.int
        require(magic == command.inv()) { "ADB invalid magic" }
        require(len in 0..MAX_DATA) { "ADB invalid payload length: $len" }
        val payload = ByteArray(len)
        if (len > 0) input.readFully(payload)
        require(checksum == payload.fold(0) { sum, byte -> sum + (byte.toInt() and 0xff) }) {
            "ADB invalid checksum (classic protocol)"
        }
        return Msg(command, arg0, arg1, payload)
    }

    /** AdbCrypto.signAdbTokenPayload: RSA/ECB/NoPadding(SIGNATURE_PADDING || token). */
    internal fun signToken(privateKey: RSAPrivateKey, token: ByteArray): ByteArray {
        require(token.size == 20) { "ADB RSA challenge must be 20 bytes" }
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        cipher.update(SIGNATURE_PADDING)
        return cipher.doFinal(token)
    }

    private fun adbPublicKeyBytes(pub: RSAPublicKey, user: String): ByteArray {
        val encoded = convertRsaPublicKeyToAdbFormat(pub)
        val line = Base64.encodeToString(encoded, Base64.NO_WRAP) + " " + user + "\u0000"
        return line.toByteArray(Charsets.UTF_8)
    }

    /** AdbCrypto.convertRsaPublicKeyToAdbFormat — exact 524-byte ANDROID RSAPublicKey. */
    private fun convertRsaPublicKeyToAdbFormat(pubkey: RSAPublicKey): ByteArray {
        val r32 = BigInteger.ZERO.setBit(32)
        var n = pubkey.modulus
        val r = BigInteger.ZERO.setBit(KEY_LENGTH_WORDS * 32)
        var rr = r.modPow(BigInteger.valueOf(2), n)
        val rem0 = n.remainder(r32)
        val n0inv = rem0.modInverse(r32)
        val myN = IntArray(KEY_LENGTH_WORDS)
        val myRr = IntArray(KEY_LENGTH_WORDS)
        for (i in 0 until KEY_LENGTH_WORDS) {
            var res = rr.divideAndRemainder(r32)
            rr = res[0]
            myRr[i] = res[1].toInt()
            res = n.divideAndRemainder(r32)
            n = res[0]
            myN[i] = res[1].toInt()
        }
        val bbuf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        bbuf.putInt(KEY_LENGTH_WORDS)
        bbuf.putInt(n0inv.negate().toInt())
        for (v in myN) bbuf.putInt(v)
        for (v in myRr) bbuf.putInt(v)
        bbuf.putInt(pubkey.publicExponent.toInt())
        return bbuf.array()
    }

    @Synchronized
    private fun loadOrCreateKeys(context: Context): KeyPair {
        val dir = File(context.filesDir, "adb")
        dir.mkdirs()
        val privFile = File(dir, "adbkey")
        val pubFile = File(dir, "adbkey.pub")
        if (privFile.exists() && pubFile.exists()) {
            val kf = KeyFactory.getInstance("RSA")
            val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes()))
            val pub = kf.generatePublic(
                X509EncodedKeySpec(Base64.decode(pubFile.readText().trim(), Base64.DEFAULT))
            )
            return KeyPair(pub, priv)
        }
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(KEY_LENGTH_BITS)
        val kp = kpg.generateKeyPair()
        privFile.writeBytes(kp.private.encoded)
        // Store raw X509 bytes (AdbCrypto style), not only Base64 — keep Base64 for readability.
        pubFile.writeText(Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP))
        return kp
    }
}
