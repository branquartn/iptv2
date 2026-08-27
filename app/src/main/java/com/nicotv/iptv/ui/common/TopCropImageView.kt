package com.nicotv.iptv.ui.common

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/** centerCrop mais biaisé vers le HAUT (15%) : les photos TMDb (acteurs) ont la
 * tête dans la partie haute — un centrage vertical coupe le sommet du crâne.
 *
 * Crop fait AU DESSIN par la vue (imageMatrix), PAS par une Transformation
 * Coil sur le bitmap : les transformations bitmap ont causé une série de bugs
 * de timing insolubles (bitmap HARDWARE → Canvas plante → fallback silencieux
 * de Coil sur l'image non transformée ; taille de décodage déduite d'une vue
 * pas encore mesurée au 1er rendu → crop faussé au 1er affichage seulement).
 * Ici, la matrice est recalculée à CHAQUE changement de drawable ou de taille
 * de vue — déterministe, aucun état de cache ni ordre d'exécution en jeu.
 * L'arrondi (cercle) reste à la charge du parent (CardView cornerRadius). */
class TopCropImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : AppCompatImageView(context, attrs, defStyle) {

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setFrame(l: Int, t: Int, r: Int, b: Int): Boolean {
        val changed = super.setFrame(l, t, r, b)
        applyTopCrop()
        return changed
    }

    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(drawable)
        applyTopCrop()
    }

    private fun applyTopCrop() {
        val d = drawable ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (vw <= 0f || vh <= 0f || dw <= 0f || dh <= 0f) return

        val scale = maxOf(vw / dw, vh / dh)
        val dx = (vw - dw * scale) / 2f                       // centré horizontalement
        val dy = (vh - dh * scale) * TOP_BIAS                 // biaisé vers le haut
        imageMatrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
    }

    companion object {
        // 0 = collé en haut, 0.5 = centré. (vh - dh*scale) est négatif quand ça
        // dépasse → multiplier par 0.15 remonte le cadrage vers le haut.
        private const val TOP_BIAS = 0.15f
    }
}
