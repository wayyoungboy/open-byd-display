package org.openbyddisplay.ambient

/** The same synchronous session loop is used on the real shell and by EOF/lease host tests. */
internal class AmbientControlSession(private val lamp: AmbientLampController? = null,
    private val native: NativeRhythmController? = null) {
    init { require((lamp == null) != (native == null)) }
    fun run(read: () -> String?, send: (String) -> Unit): AmbientRestoration {
        var failure: String? = null
        var overridden = false
        val result: AmbientRestoration
        try {
            val initial = lamp?.initial ?: native!!.initial.lamps
            send("READY ${initial.wire()}")
            var sequence = 0L
            while (true) {
                val line = read() ?: break
                if (line == "STOP") break
                val command = AmbientProtocol.parse(line, sequence,
                    if (lamp == null) AmbientBackendKind.NATIVE else AmbientBackendKind.SOFTWARE)
                sequence = command.sequence
                val actual = when (command) {
                    is AmbientCommand.Keep -> { native?.keep(); lamp?.keep() }
                    is AmbientCommand.Frame -> lamp!!.frame(command.frame)
                    is AmbientCommand.Spectrum -> { native!!.spectrum(command.bands); null }
                }
                send("ACK $sequence ${actual?.wire() ?: "NATIVE"}")
            }
        } catch (t: Exception) {
            overridden = t is AmbientManualOverride
            failure = (t.cause ?: t).message ?: t.javaClass.simpleName
        } finally {
            result = native?.finish() ?: lamp!!.finish()
        }
        val reply = when {
            overridden || result == AmbientRestoration.OVERRIDDEN -> "OVERRIDDEN"
            result == AmbientRestoration.UNCONFIRMED -> "ERROR UNCONFIRMED 恢复读回失败"
            failure != null -> "ERROR RESTORED ${failure.replace(Regex("[\\r\\n]"), " ").take(160)}"
            else -> "RESTORED"
        }
        runCatching { send(reply) } // A lost terminal reply does not make the app assume restoration.
        return result
    }
}
