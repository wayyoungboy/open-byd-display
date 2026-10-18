package org.openbyddisplay.cast

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.min

/** Procedural soft-edge / vignette mask generated at runtime (no vendor bitmaps). */
object SoftEdgeMask {
    fun create(width: Int, height: Int, edgeFraction: Float = 0.12f): Bitmap {
        require(width >= 64 && height >= 64)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f
        val edge = (min(width, height) * edgeFraction).coerceAtLeast(8f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.BLACK),
            floatArrayOf(0f, ((radius - edge) / radius).coerceIn(0.2f, 0.95f), 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        // Top/bottom dark bands for instrument bezels
        val band = (height * 0.04f).toInt().coerceAtLeast(4)
        val bandPaint = Paint().apply { color = Color.BLACK }
        canvas.drawRect(0f, 0f, width.toFloat(), band.toFloat(), bandPaint)
        canvas.drawRect(0f, (height - band).toFloat(), width.toFloat(), height.toFloat(), bandPaint)
        return bitmap
    }
}
