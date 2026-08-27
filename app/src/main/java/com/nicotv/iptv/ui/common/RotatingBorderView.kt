package com.nicotv.iptv.ui.common

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.nicotv.iptv.R

/** Contour focus TV : au lieu d'une couleur fixe, un dégradé tourne en continu
 * autour du cadre tant que la vue a le focus (start/stopAnim, appelé par
 * l'adaptateur avec la visibilité). Le rectangle/cercle dessiné reste fixe —
 * seule la matrice locale du SweepGradient tourne, pas le canvas (sinon les
 * coins arrondis d'un cadre non carré se déformeraient en tournant).
 *
 * app:borderColor (défaut @color/accent) et app:circular (défaut false, cadre
 * arrondi) permettent de réutiliser la même vue pour un anneau rond (casting,
 * bouton icône) ou un contour blanc (bouton icône) sans dupliquer la classe. */
class RotatingBorderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val strokeWidthPx = STROKE_DP * resources.displayMetrics.density
    private val cornerRadiusPx: Float
    private val borderColor: Int
    private val circular: Boolean
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val matrix = Matrix()
    private var angle = 0f
    private var animator: ValueAnimator? = null

    init {
        val ta = context.obtainStyledAttributes(attrs, R.styleable.RotatingBorderView)
        borderColor = ta.getColor(R.styleable.RotatingBorderView_borderColor, ContextCompat.getColor(context, R.color.accent))
        circular = ta.getBoolean(R.styleable.RotatingBorderView_circular, false)
        cornerRadiusPx = ta.getDimension(R.styleable.RotatingBorderView_cornerRadius, CORNER_DP * resources.displayMetrics.density)
        ta.recycle()
        paint.strokeWidth = strokeWidthPx
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            val dim = ColorUtils.setAlphaComponent(borderColor, 40)
            // 1er/dernier identique : pas de "couture" visible au bouclage du dégradé.
            paint.shader = SweepGradient(w / 2f, h / 2f, intArrayOf(borderColor, dim, borderColor), null)
        }
    }

    override fun onDraw(canvas: Canvas) {
        paint.shader?.let {
            matrix.reset()
            matrix.postRotate(angle, width / 2f, height / 2f)
            it.setLocalMatrix(matrix)
        }
        val inset = strokeWidthPx / 2f
        val radius = if (circular) (minOf(width, height) - strokeWidthPx) / 2f else cornerRadiusPx
        canvas.drawRoundRect(inset, inset, width - inset, height - inset, radius, radius, paint)
    }

    fun startAnim() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = ROTATION_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { angle = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun stopAnim() {
        animator?.cancel()
        animator = null
    }

    companion object {
        private const val STROKE_DP = 1.5f
        private const val CORNER_DP = 6f
        private const val ROTATION_DURATION_MS = 2200L
    }
}
