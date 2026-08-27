package com.nicotv.iptv2.ui.common

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/** RecyclerView (GridLayoutManager) en hauteur wrap_content, nichée dans un
 * ScrollView : force la mesure de hauteur en UNSPECIFIED plutôt que de
 * reprendre celle imposée par le parent. Sans ça, quand le contenu arrive de
 * façon asynchrone (fetch réseau) APRÈS que le dialog soit déjà affiché et
 * dimensionné, le calcul wrap_content peut rester bloqué sur la mesure
 * initiale (contenu quasi vide) → seule 1 rangée (spanCount items) s'affiche,
 * le reste est invisible/inatteignable au scroll (cf. dialog_actor.xml —
 * filmographie acteur). UNSPECIFIED force GridLayoutManager à toujours
 * recalculer la hauteur réelle du contenu à chaque mesure. */
class WrapHeightRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : RecyclerView(context, attrs, defStyle) {

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        super.onMeasure(widthSpec, unspecified)
    }
}
