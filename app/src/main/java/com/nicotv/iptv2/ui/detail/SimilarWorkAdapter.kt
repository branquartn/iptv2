package com.nicotv.iptv2.ui.detail

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.nicotv.iptv2.R
import com.nicotv.iptv2.databinding.ItemSimilarMovieBinding
import com.nicotv.iptv2.domain.model.SimilarWork

/** [onBadgeClick] : le rond ✓, visible **seulement** si le film/série est déjà
 * dans le catalogue chargé (ouvre sa fiche) — plus de "+" pour les absents
 * (retiré 29/08/2026, demande explicite) : pas de backend pour ajouter quoi
 * que ce soit ici, ce "+" n'avait jamais rien fait d'autre qu'un message
 * "pas dans votre playlist", case vide maintenant sans badge du tout.
 * [onPreviewClick] : le reste de la carte (jaquette/titre) — aperçu
 * (synopsis + bande-annonce), jamais d'ouverture directe.
 * [gridMode] : la carte prend toute la largeur de sa cellule (GridLayoutManager,
 * filmographie acteur) au lieu de sa largeur fixe (rangée horizontale). */
class SimilarWorkAdapter(
    private val onBadgeClick: (SimilarWork) -> Unit,
    private val onPreviewClick: (SimilarWork) -> Unit,
    private val gridMode: Boolean = false
) : RecyclerView.Adapter<SimilarWorkAdapter.VH>() {

    private var items = listOf<SimilarWork>()

    fun submitList(list: List<SimilarWork>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemSimilarMovieBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        if (gridMode) {
            // RecyclerView.LayoutParams (pas ViewGroup.LayoutParams) : sinon
            // GridLayoutManager mesure mal la cellule, ratio 2:3 de travers.
            val m = (4 * parent.resources.displayMetrics.density).toInt()
            b.root.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(m, m, m, m)
            }
        }
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    inner class VH(private val b: ItemSimilarMovieBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(work: SimilarWork) {
            b.tvTitle.text = if (work.year.isNotBlank()) "${work.title} · ${work.year}" else work.title
            b.ivPoster.load(work.posterUrl.ifBlank { null }) {
                crossfade(true)
                placeholder(R.drawable.ic_movie_placeholder)
                error(R.drawable.ic_movie_placeholder)
            }
            b.focusOverlay.visibility = View.INVISIBLE
            b.focusOverlay.stopAnim()
            b.tvBadgeType.visibility = View.GONE

            if (work.owned) {
                b.tvBadgeOwned.visibility = View.VISIBLE
                b.tvBadgeOwned.text = "✓"
                b.tvBadgeOwned.setBackgroundResource(R.drawable.bg_badge_owned)
                b.tvBadgeOwned.setOnClickListener { onBadgeClick(work) }
            } else {
                b.tvBadgeOwned.visibility = View.GONE
                b.tvBadgeOwned.setOnClickListener(null)
            }

            b.root.setOnClickListener { onPreviewClick(work) }
            b.root.setOnFocusChangeListener { v, hasFocus ->
                // Rangée horizontale : le zoom pivote depuis le bas (pivotY = hauteur)
                // → la carte grandit vers le haut, jamais coupée sous le ScrollView.
                if (!gridMode) v.pivotY = v.height.toFloat()
                v.animate().scaleX(if (hasFocus) 1.08f else 1f).scaleY(if (hasFocus) 1.08f else 1f)
                    .setDuration(150).start()
                b.cardView.cardElevation = if (hasFocus) 16f else 3f
                b.focusOverlay.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
                if (hasFocus) b.focusOverlay.startAnim() else b.focusOverlay.stopAnim()
            }
        }
    }
}
