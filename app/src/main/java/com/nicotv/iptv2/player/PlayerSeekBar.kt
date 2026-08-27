package com.nicotv.iptv2.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.nicotv.iptv2.R

/** Barre de progression du player, dessinée nous-mêmes — pas DefaultTimeBar de Media3.
 * Vit dans player_control_view.xml (zone gérée par Media3), donc apparaît/disparaît
 * déjà avec les autres contrôles (exo_play_pause, ⚙) via le fade natif du controller ;
 * ce qu'on gagne en la remplaçant : couleur (bleu accent, cohérent avec le reste de
 * l'app, plus le rouge par défaut de Media3) et un rendu identique sur tous les
 * appareils (le style de DefaultTimeBar dépend du thème système sur certains boîtiers
 * TV bas de gamme). */
class PlayerSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var durationMs: Long = 0L
        set(value) { field = value; invalidate() }
    var positionMs: Long = 0L
        set(value) { if (!dragging) { field = value; invalidate() } }
    var bufferedMs: Long = 0L
        set(value) { field = value; invalidate() }

    /** Relâché (ACTION_UP) : position cible définitive, à appliquer (player.seekTo). */
    var onSeek: ((Long) -> Unit)? = null
    /** Début de glissement : suspendre les mises à jour de positionMs pendant le drag. */
    var onScrubStart: (() -> Unit)? = null

    private var dragging = false
    private var dragPositionMs: Long = 0L

    private val density = resources.displayMetrics.density
    private val barHeightPx = 3f * density
    private val thumbRadiusRestPx = 5f * density
    private val thumbRadiusFocusPx = 7f * density

    private val accentColor = ContextCompat.getColor(context, R.color.accent)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val bufferedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF }
    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }

    init {
        isFocusable = true
        isFocusableInTouchMode = false
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val cy = height / 2f
        val top = cy - barHeightPx / 2f
        val bottom = cy + barHeightPx / 2f
        canvas.drawRect(0f, top, w, bottom, trackPaint)
        if (durationMs <= 0) return
        val bufFrac = (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f)
        canvas.drawRect(0f, top, w * bufFrac, bottom, bufferedPaint)
        val curPos = if (dragging) dragPositionMs else positionMs
        val playFrac = (curPos.toFloat() / durationMs).coerceIn(0f, 1f)
        canvas.drawRect(0f, top, w * playFrac, bottom, playedPaint)
        canvas.drawCircle(w * playFrac, cy, if (isFocused) thumbRadiusFocusPx else thumbRadiusRestPx, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (durationMs <= 0) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                dragPositionMs = positionFromX(event.x)
                onScrubStart?.invoke()
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                dragPositionMs = positionFromX(event.x)
                invalidate()
            }
            MotionEvent.ACTION_UP -> if (dragging) {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                val target = positionFromX(event.x)
                positionMs = target
                onSeek?.invoke(target)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
            }
        }
        return true
    }

    private fun positionFromX(x: Float): Long {
        val frac = (x / width).coerceIn(0f, 1f)
        return (frac * durationMs).toLong()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        thumbPaint.color = if (gainFocus) Color.WHITE else accentColor
        invalidate()
    }
}
