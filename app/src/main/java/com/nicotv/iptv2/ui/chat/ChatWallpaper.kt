package com.nicotv.iptv2.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Papier peint du tchat, identique à la PWA : dégradé rose→bleu à 35° avec, par-dessus,
 * le motif doodle (assets/chat-pattern.svg) tuilé en cases de 112 dp.
 */
class ChatWallpaper(private val tile: Bitmap, private val density: Float) : Drawable() {

    private val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).also {
            val scale = 112f * density / tile.width
            it.setLocalMatrix(Matrix().apply { setScale(scale, scale) })
        }
    }

    override fun onBoundsChange(b: Rect) {
        // CSS linear-gradient(35deg, …) : ligne de dégradé centrée, longueur |w·sin|+|h·cos|.
        val a = Math.toRadians(35.0)
        val w = b.width().toFloat(); val h = b.height().toFloat()
        val len = abs(w * sin(a).toFloat()) + abs(h * cos(a).toFloat())
        val dx = sin(a).toFloat() * len / 2f; val dy = -cos(a).toFloat() * len / 2f
        val cx = b.exactCenterX(); val cy = b.exactCenterY()
        gradientPaint.shader = LinearGradient(
            cx - dx, cy - dy, cx + dx, cy + dy,
            intArrayOf(0xFFF0B6DD.toInt(), 0xFFD3AAE6.toInt(), 0xFFA9B4EC.toInt(), 0xFF84C8F0.toInt(), 0xFF77D6F2.toInt()),
            floatArrayOf(0f, .26f, .55f, .80f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun draw(c: Canvas) {
        c.drawRect(bounds, gradientPaint)
        c.drawRect(bounds, tilePaint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.OPAQUE
}
