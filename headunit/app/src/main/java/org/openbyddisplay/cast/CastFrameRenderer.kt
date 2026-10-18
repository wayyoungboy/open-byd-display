package org.openbyddisplay.cast

import android.graphics.SurfaceTexture
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLUtils
import android.os.SystemClock
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Independent source scaling, horizontal reflection and theme-colored encoded edge fade. */
internal class CastFrameRenderer(
    private val sourceWidth: Int, private val sourceHeight: Int,
    private val targetWidth: Int, private val targetHeight: Int,
    private val target: Surface, private val fps: Int,
    private val edgeFraction: Float, private val flip: Boolean,
    private val edgeRgb: () -> Int,
    private val overlay: () -> List<String>,
    private val onError: (String) -> Unit
) : AutoCloseable {
    private val thread = HandlerThread("openbyd-frame-render").also { it.start() }
    private val handler = Handler(thread.looper)
    private val closed = AtomicBoolean(false)
    private val released = CountDownLatch(1)
    @Volatile private var cleanupOk = true
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var window = EGL14.EGL_NO_SURFACE
    private var texture = 0
    private var program = 0
    private var overlayProgram = 0
    private var overlayTexture = 0
    private var lastOverlayAt = 0L
    private var hasOverlay = false
    private var source: SurfaceTexture? = null
    private var lastFrameNs = 0L
    private var hasImage = false
    private val refreshOverlay = object : Runnable {
        override fun run() {
            if (closed.get()) return
            if (hasImage && System.nanoTime()-lastFrameNs >= 500_000_000L) renderFrame(false)
            handler.postDelayed(this, 500)
        }
    }
    private val matrix = FloatArray(16)
    private val vertices = floats(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val coordinates = floats(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
    lateinit var inputSurface: Surface
        private set

    fun start() {
        val ready = CountDownLatch(1)
        var failure: Throwable? = null
        handler.post {
            try {
                check(!closed.get())
                initialize(sourceWidth, sourceHeight)
                check(!closed.get())
            }
            catch (t: Throwable) { failure = t }
            finally { ready.countDown() }
        }
        if (!ready.await(5, TimeUnit.SECONDS)) {
            close()
            error("地图缩放器初始化超时")
        }
        failure?.let { close(); throw IllegalStateException("地图缩放器初始化失败", it) }
    }

    private fun initialize(sourceWidth: Int, sourceHeight: Int) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1))
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, 0x3142, 1, EGL14.EGL_NONE)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0)
        val config = configs[0] ?: error("No recordable EGL config")
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT)
        window = EGL14.eglCreateWindowSurface(display, config, target, intArrayOf(EGL14.EGL_NONE), 0)
        check(window != EGL14.EGL_NO_SURFACE)
        check(EGL14.eglMakeCurrent(display, window, window, context))
        val vertex = shader(GLES20.GL_VERTEX_SHADER, """
            attribute vec2 aPosition;
            attribute vec2 aTexture;
            uniform mat4 uMatrix;
            uniform float uFlip;
            varying vec2 vPosition;
            varying vec2 vTexture;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vec2 uv = vec2(mix(aTexture.x, 1.0-aTexture.x, uFlip), aTexture.y);
                vTexture = (uMatrix * vec4(uv, 0.0, 1.0)).xy;
                vPosition = aTexture;
            }
        """.trimIndent())
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER, """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            uniform float uEdge;
            uniform vec3 uEdgeColor;
            varying vec2 vPosition;
            varying vec2 vTexture;
            void main() {
                float distance = min(min(vPosition.x, 1.0-vPosition.x), min(vPosition.y, 1.0-vPosition.y));
                float fade = uEdge > 0.0 ? smoothstep(0.0, uEdge, distance) : 1.0;
                gl_FragColor = vec4(mix(uEdgeColor, texture2D(uTexture, vTexture).rgb, fade), 1.0);
            }
        """.trimIndent())
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] != 0) { GLES20.glGetProgramInfoLog(program) }
        val overlayVertex = shader(GLES20.GL_VERTEX_SHADER, """
            attribute vec2 aPosition; attribute vec2 aTexture; varying vec2 uv;
            void main() { gl_Position=vec4(aPosition,0.0,1.0); uv=vec2(aTexture.x,1.0-aTexture.y); }
        """.trimIndent())
        val overlayFragment = shader(GLES20.GL_FRAGMENT_SHADER, """
            precision mediump float; uniform sampler2D uTexture; varying vec2 uv;
            void main() { gl_FragColor=texture2D(uTexture,uv); }
        """.trimIndent())
        overlayProgram=GLES20.glCreateProgram()
        GLES20.glAttachShader(overlayProgram,overlayVertex); GLES20.glAttachShader(overlayProgram,overlayFragment)
        GLES20.glLinkProgram(overlayProgram)
        GLES20.glDeleteShader(overlayVertex); GLES20.glDeleteShader(overlayFragment)
        GLES20.glGetProgramiv(overlayProgram,GLES20.GL_LINK_STATUS,linked,0)
        check(linked[0]!=0)
        val overlayIds=IntArray(1); GLES20.glGenTextures(1,overlayIds,0); overlayTexture=overlayIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,overlayTexture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        texture = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        source = SurfaceTexture(texture).also {
            it.setDefaultBufferSize(sourceWidth, sourceHeight)
            inputSurface = Surface(it)
            it.setOnFrameAvailableListener({ renderFrame(true) }, handler)
        }
        handler.postDelayed(refreshOverlay, 500)
    }

    private fun renderFrame(update: Boolean) {
        if (closed.get()) return
        try {
            val input = source ?: return
            if (update) { input.updateTexImage(); hasImage = true }
            val timestamp = System.nanoTime()
            if (lastFrameNs > 0 && timestamp - lastFrameNs < 1_000_000_000L / fps) return
            lastFrameNs = timestamp
            input.getTransformMatrix(matrix)
            GLES20.glViewport(0, 0, targetWidth, targetHeight)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uFlip"), if (flip) 1f else 0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uEdge"), edgeFraction)
            val color = edgeRgb()
            GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "uEdgeColor"),
                ((color shr 16) and 255) / 255f, ((color shr 8) and 255) / 255f, (color and 255) / 255f)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMatrix"), 1, false, matrix, 0)
            val position = GLES20.glGetAttribLocation(program, "aPosition")
            val coordinate = GLES20.glGetAttribLocation(program, "aTexture")
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glEnableVertexAttribArray(coordinate)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices)
            GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 0, coordinates)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            renderOverlay()
            EGLExt.eglPresentationTimeANDROID(display, window, timestamp)
            check(EGL14.eglSwapBuffers(display, window)) { "EGL swap failed" }
        } catch (e: Exception) {
            if (!closed.get()) onError("地图缩放失败：${e.message}")
            close()
        }
    }

    private fun renderOverlay() {
        val now=SystemClock.elapsedRealtime()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,overlayTexture)
        if(now-lastOverlayAt>=500) {
            lastOverlayAt=now
            val lines=overlay().take(3)
            hasOverlay=lines.isNotEmpty()
            if(hasOverlay) {
                val bitmap=Bitmap.createBitmap(1024,144,Bitmap.Config.ARGB_8888)
                try {
                    val canvas=Canvas(bitmap)
                    canvas.drawColor(0xb0000000.toInt())
                    val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE; textSize=32f }
                    lines.forEachIndexed { i,line -> canvas.drawText(line.take(120),20f,38f+i*44f,paint) }
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,bitmap,0)
                } finally { bitmap.recycle() }
            }
        }
        if(!hasOverlay) return
        GLES20.glViewport(0,0,targetWidth,maxOf(48,targetHeight/3))
        GLES20.glUseProgram(overlayProgram)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(overlayProgram,"uTexture"),0)
        val position=GLES20.glGetAttribLocation(overlayProgram,"aPosition")
        val coordinate=GLES20.glGetAttribLocation(overlayProgram,"aTexture")
        GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(coordinate)
        GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,0,vertices)
        GLES20.glVertexAttribPointer(coordinate,2,GLES20.GL_FLOAT,false,0,coordinates)
        GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE,GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        if (!handler.post {
            fun dispose(block: () -> Unit) { try { block() } catch (_: Exception) { cleanupOk = false } }
            try {
                dispose { source?.setOnFrameAvailableListener(null) }
                dispose { if (::inputSurface.isInitialized) inputSurface.release() }
                dispose { source?.release() }
                dispose { if (program != 0) GLES20.glDeleteProgram(program) }
                dispose { if (overlayProgram != 0) GLES20.glDeleteProgram(overlayProgram) }
                dispose { if (overlayTexture != 0) GLES20.glDeleteTextures(1,intArrayOf(overlayTexture),0) }
                dispose { if (texture != 0) GLES20.glDeleteTextures(1,intArrayOf(texture),0) }
                if (display != EGL14.EGL_NO_DISPLAY) {
                    dispose { check(EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)) }
                    dispose { if (window != EGL14.EGL_NO_SURFACE) check(EGL14.eglDestroySurface(display,window)) }
                    dispose { if (context != EGL14.EGL_NO_CONTEXT) check(EGL14.eglDestroyContext(display,context)) }
                    dispose { check(EGL14.eglTerminate(display)) }
                }
            } finally { released.countDown(); thread.quitSafely() }
        }) { cleanupOk = false; released.countDown(); thread.quitSafely() }
    }

    fun awaitRelease(): Boolean = released.await(5, TimeUnit.SECONDS) && cleanupOk

    private fun shader(type: Int, code: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, code)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] != 0) { GLES20.glGetShaderInfoLog(shader) }
        return shader
    }

    private fun floats(values: FloatArray) = ByteBuffer.allocateDirect(values.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
}
