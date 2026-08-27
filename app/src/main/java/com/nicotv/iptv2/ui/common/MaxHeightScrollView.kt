package com.nicotv.iptv2.ui.common

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ScrollView
import com.nicotv.iptv2.R

/** ScrollView avec un VRAI plafond de hauteur : wrap_content en dessous (un
 * dialog court reste court), plafonné + scroll interne au-delà.
 *
 * Nécessaire parce que ScrollView ignore silencieusement android:maxHeight, et
 * que l'alternative précédente (fenêtre de dialog à hauteur FIXE posée en
 * Kotlin) laissait une grande carte à moitié vide pour un contenu court.
 * AT_MOST = « au plus » : le ScrollView mesure son contenu et prend le min.
 *
 * Plafond = hauteur écran MOINS une marge fixe pour le chrome AUTOUR de ce
 * ScrollView (titre/✕, éventuelle barre de bouton système, rangée fixe sous le
 * scroll…), pas un simple ratio de l'écran : ce chrome est en dehors du
 * ScrollView, donc un plafond en % seul laissait la fenêtre totale dépasser
 * l'écran sur mobile en paysage (écran court) — bouton coupé, hors d'atteinte
 * du scroll puisque c'est la FENÊTRE qui débordait, pas le ScrollView.
 *
 * app:chromeReserveDp (XML) et/ou la propriété Kotlin du même nom (settable
 * après inflate, avant dialog.show()) — le chrome réel varie beaucoup selon
 * l'usage ET la plateforme pour un MÊME layout partagé : TV garde le vrai
 * titre+bouton OK système (~150-220dp), mobile n'a souvent qu'un petit en-tête
 * ✕ custom (~50-70dp). Une seule constante partagée réservait toujours le
 * maximum TV, même sur mobile où c'est inutile (fenêtre visiblement plus
 * courte que nécessaire — cf. dialog_actor en filmographie acteur). */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : ScrollView(context, attrs, defStyle) {

    var chromeReserveDp: Int
        private set

    init {
        val ta = context.obtainStyledAttributes(attrs, R.styleable.MaxHeightScrollView)
        chromeReserveDp = ta.getInt(R.styleable.MaxHeightScrollView_chromeReserveDp, DEFAULT_CHROME_RESERVE_DP)
        ta.recycle()
    }

    /** À appeler après inflate, avant que le dialog ne (re)mesure sa hauteur —
     * ex. juste avant dialog.show(). */
    fun setChromeReserveDp(dp: Int) {
        chromeReserveDp = dp
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val chromeReservePx = (chromeReserveDp * resources.displayMetrics.density).toInt()
        val maxH = resources.displayMetrics.heightPixels - chromeReservePx
        val cappedSpec = View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, cappedSpec)
    }

    companion object {
        // Repli si app:chromeReserveDp absent du XML : valeur historique
        // (titre + bouton OK système + marge de sécurité).
        private const val DEFAULT_CHROME_RESERVE_DP = 220
    }
}
