package com.nicotv.iptv2.ui.live

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.nicotv.iptv2.R
import com.nicotv.iptv2.databinding.ItemChannelTileBinding
import com.nicotv.iptv2.domain.model.Channel

/** Mosaïque de chaînes (écran Chaînes), comme IPTV Smarters Pro — remplace la
 * liste verticale (ChannelAdapter, gardé pour l'écran Recherche uniquement, où
 * films/séries/chaînes se mélangent). Même principe que ChannelAdapter pour
 * l'absence de ListAdapter/DiffUtil (cf. PosterAdapter — diff impraticable sur
 * un catalogue de dizaines de milliers de chaînes). */
class ChannelGridAdapter(
    private val onClick: (Channel) -> Unit,
    private val onToggleFavorite: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelGridAdapter.VH>() {

    private var items: List<Channel> = emptyList()

    /** ⚠️ Une page ajoutée en fin de liste (scroll infini, cf. CLAUDE.md) est
     * signalée par `notifyItemRangeInserted`, PAS par `notifyDataSetChanged` :
     * ce dernier invalide tout, ce qui sur Android TV peut déplacer ou perdre
     * le focus D-pad en plein défilement — précisément au moment où la page
     * suivante arrive. L'insertion ciblée ne touche que les nouvelles lignes.
     *
     * ⚠️ Ce n'est PAS un retour à `DiffUtil`/`ListAdapter`, retiré
     * délibérément (cf. commentaire de classe) : on ne calcule aucun diff, on
     * vérifie juste que la nouvelle liste commence exactement par l'ancienne
     * (comparaison d'identité, pas d'égalité structurelle — l'ajout de page
     * réutilise les mêmes instances). Tout autre changement (filtre, recherche,
     * rafraîchissement des favoris qui recrée les objets) retombe sur
     * `notifyDataSetChanged`, au coût constant qui était déjà le nôtre. */
    fun submitList(list: List<Channel>) {
        // Même instance re-soumise (le rendu est recalculé sur plusieurs
        // sources, cf. les Activity) : rien n'a changé, ne pas invalider.
        if (list === items) return
        val previous = items
        items = list
        if (isAppendOf(previous, list)) {
            notifyItemRangeInserted(previous.size, list.size - previous.size)
        } else {
            notifyDataSetChanged()
        }
    }

    private fun isAppendOf(previous: List<Channel>, next: List<Channel>): Boolean {
        if (previous.isEmpty() || next.size <= previous.size) return false
        for (i in previous.indices) {
            if (previous[i] !== next[i]) return false
        }
        return true
    }

    override fun getItemCount(): Int = items.size

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        // Cf. PosterAdapter : évite le conflit entre l'item animator par défaut
        // et le zoom de focus (les deux animent itemView via view.animate()).
        recyclerView.itemAnimator = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemChannelTileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val b: ItemChannelTileBinding) : RecyclerView.ViewHolder(b.root) {

        init {
            b.root.setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.06f else 1f).scaleY(if (hasFocus) 1.06f else 1f).setDuration(150).start()
                b.cardView.cardElevation = if (hasFocus) 14f else 3f
                b.focusOverlay.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
                if (hasFocus) b.focusOverlay.startAnim() else b.focusOverlay.stopAnim()
            }
        }

        fun bind(channel: Channel) {
            b.root.animate().cancel()
            b.root.scaleX = 1f
            b.root.scaleY = 1f

            b.tvName.text = channel.name
            b.ivLogo.load(channel.logoUrl) {
                placeholder(R.drawable.ic_movie_placeholder)
                error(R.drawable.ic_movie_placeholder)
            }
            b.ivFavorite.visibility = if (channel.isFavorite) View.VISIBLE else View.GONE
            b.root.setOnClickListener { onClick(channel) }
            b.root.setOnLongClickListener { onToggleFavorite(channel); true }

            b.focusOverlay.visibility = View.INVISIBLE
            b.focusOverlay.stopAnim()
        }
    }
}
