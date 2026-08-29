package com.nicotv.iptv2.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.nicotv.iptv2.R

/** Cercle de progression déterminé (0-100), pour le dialogue de chargement
 * (29/08/2026, demande explicite : "voir la progression dans un cercle avec
 * le pourcentage dedans"). Piste discrète en fond + arc `accent` par-dessus,
 * démarre en haut (`-90°`) et tourne dans le sens horaire — le pourcentage
 * lui-même est un `TextView` superposé au centre (cf. dialog_loading.xml),
 * pas dessiné ici. */
class CircularProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val strokeWidthPx = STROKE_DP * resources.displayMetrics.density
    private val accentColor = ContextCompat.getColor(context, R.color.accent)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        color = ColorUtils.setAlphaComponent(accentColor, 35)
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        strokeCap = Paint.Cap.ROUND
        color = accentColor
    }
    private val rectF = RectF()

    /** 0-100 — `invalidate()` à chaque changement, pas d'animation interne
     * (gérée par l'appelant, cf. LoadingDialog, qui anime la valeur affichée
     * pour un rendu fluide plutôt que des sauts). */
    var progress: Int = 0
        set(value) {
            field = value.coerceIn(0, 100)
            invalidate()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = strokeWidthPx / 2f
        rectF.set(inset, inset, w - inset, h - inset)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawArc(rectF, 0f, 360f, false, trackPaint)
        canvas.drawArc(rectF, -90f, 360f * progress / 100f, false, progressPaint)
    }

    companion object {
        private const val STROKE_DP = 6f
    }
}
