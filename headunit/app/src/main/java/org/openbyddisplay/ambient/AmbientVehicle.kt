package org.openbyddisplay.ambient

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.SystemClock

/** Runs solely inside our app_process helper as the genuinely authorized shell UID. */
internal class AmbientVehicle : AmbientLampIo {
    private val auto: Any
    private val get: java.lang.reflect.Method
    private val set: java.lang.reflect.Method
    private val ids = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds")
    private val readIds: IntArray
    private val writeIds: IntArray
    init {
        check(android.os.Process.myUid() == 2000) { "需要已授权的 ADB shell" }
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        val at = Class.forName("android.app.ActivityThread")
        val context = at.getMethod("getSystemContext").invoke(at.getMethod("systemMain").invoke(null)) as Context
        @SuppressLint("WrongConstant")
        val service = context.getSystemService("auto") ?: error("车辆 auto 服务不可用")
        auto = service
        val int = Int::class.javaPrimitiveType!!
        get = auto.javaClass.getMethod("getInt", int, int)
        set = auto.javaClass.getMethod("setIntArray", int, IntArray::class.java, IntArray::class.java)
        readIds = features(listOf("SET_IAL_FRONT_COLOR", "SET_IAL_BACK_COLOR", "SET_IAL_FRONT_BRIGHTNESS",
            "SET_IAL_BACK_BRIGHTNESS", "SET_INTERIOR_ATMOSPHERE_LAMP_AREA"))
        writeIds = features(listOf("SET_INTERIOR_ATMOSPHERE_LAMP_COLOR_SET", "SET_INTERIOR_ATMOSPHERE_LAMP_BRIGHTNESS_SET",
            "SET_INTERIOR_ATMOSPHERE_LAMP_AREA_SET", "SET_INTERIOR_ATMOSPHERE_LAMP_SOURCE_SET"))
    }
    private fun features(names: List<String>) = names.map {
        ids.getField(it).getInt(null).also { value -> check(value != 0) { "车辆字段不可用：$it" } }
    }.toIntArray().also { check(it.distinct().size == it.size) }
    private fun read(device: Int, features: IntArray) = features.map { get.invoke(auto, device, it) as Int }
    private fun write(device: Int, features: IntArray, values: IntArray) {
        check(set.invoke(auto, device, features, values) == 0) { "车辆拒绝灯光命令" }
    }
    override fun read(): AmbientSnapshot = read(1023, readIds).let { AmbientSnapshot(it[0], it[1], it[2], it[3], it[4]) }
    override fun write(color: Int, rawBrightness: Int, area: Int) {
        require(area in 1..3 && ((color in 1..31 && rawBrightness in 1..6) || (color == 0 && rawBrightness == 0)))
        write(1023, writeIds, intArrayOf(color, rawBrightness, area, 0))
    }
    override fun nowMs() = SystemClock.elapsedRealtime()
    override fun sleepMs(milliseconds: Long) = Thread.sleep(milliseconds)

    fun native(): NativeRhythmIo {
        // Fail before reading or writing device 1002 unless this exact platform was qualified.
        val profile = NativeRhythmProfiles.forFingerprint(Build.FINGERPRINT)
            ?: error("本平台原生律动尚未验证，保持关闭；请选择软件模式")
        val nativeRead = features(listOf(profile.modeRead, profile.stateRead) + profile.spectrumRead)
        val controlWrite = features(listOf("AUDIO_MEDIA_AUDIO_WORKING_MODE_SET", "AUDIO_MEDIA_AUDIO_WORKING_STATE_SET"))
        val spectrumWrite = features((1..16).map { "AUDIO_SPECTRUM_FREQ${it}_SET" })
        return object : NativeRhythmIo {
            override fun read(): NativeRhythmState = read(1002, nativeRead).let {
                NativeRhythmState(it[0], it[1], AmbientSpectrum(it.drop(2).toIntArray()), this@AmbientVehicle.read())
            }
            override fun control(mode: Int, state: Int) = write(1002, controlWrite, intArrayOf(mode, state))
            override fun spectrum(values: AmbientSpectrum) = write(1002, spectrumWrite, values.values())
            override fun nowMs() = this@AmbientVehicle.nowMs()
            override fun sleepMs(milliseconds: Long) = this@AmbientVehicle.sleepMs(milliseconds)
        }
    }
}
