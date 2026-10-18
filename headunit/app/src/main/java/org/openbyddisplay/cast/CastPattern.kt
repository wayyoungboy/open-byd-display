package org.openbyddisplay.cast

import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.SystemClock
import android.view.Display
import android.view.View
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Own moving calibration pattern; never described as an instrument screenshot. */
class CastPattern private constructor(private val presentation: Presentation, private val main: Handler) : AutoCloseable {
    override fun close() { main.post { presentation.dismiss() } }
    companion object {
        fun show(context: Context, display: Display, main: Handler, stopped: AtomicBoolean): CastPattern {
            val aborted = AtomicBoolean(false)
            val task = FutureTask {
                check(!stopped.get() && !aborted.get())
                val p = Presentation(context, display)
                p.setContentView(object : View(p.context) {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                    override fun onDraw(canvas: Canvas) {
                        canvas.drawColor(Color.rgb(20, 30, 45))
                        paint.color = Color.CYAN
                        canvas.drawRect((SystemClock.elapsedRealtime() / 5 % maxOf(1, width)).toFloat(), 0f,
                            (SystemClock.elapsedRealtime() / 5 % maxOf(1, width) + 30).toFloat(), height.toFloat(), paint)
                        paint.color = Color.WHITE; paint.textSize = height / 12f
                        canvas.drawText("OpenBYDDisplay · ${SystemClock.elapsedRealtime() / 1000}", 30f, height / 2f, paint)
                        postInvalidateDelayed(100)
                    }
                })
                p.show()
                if (stopped.get() || aborted.get()) { p.dismiss(); error("标定图已取消") }
                CastPattern(p, main)
            }
            main.post(task)
            return try { task.get(5, TimeUnit.SECONDS) } catch (e: Exception) {
                aborted.set(true); task.cancel(false); throw e
            }
        }
    }
}
