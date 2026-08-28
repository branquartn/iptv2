package com.nicotv.iptv2.ui.live

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.nicotv.iptv2.R
import com.nicotv.iptv2.databinding.ItemChannelTileBinding
import com.nicotv.iptv2.domain.model.Channel
import com.nicotv.iptv2.domain.model.EpgNowNext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Mosaïque de chaînes (écran Chaînes), comme IPTV Smarters Pro — remplace la
 * liste verticale (ChannelAdapter, gardé pour l'écran Recherche uniquement, où
 * films/séries/chaînes se mélangent). Mêmes principes que ChannelAdapter pour
 * l'EPG (fetch à la demande au bind, job annulé au recyclage) et l'absence de
 * ListAdapter/DiffUtil (cf. PosterAdapter — diff impraticable sur un catalogue
 * de dizaines de milliers de chaînes). */
class ChannelGridAdapter(
    private val onClick: (Channel) -> Unit,
    private val onToggleFavorite: (Channel) -> Unit,
    private val epgScope: CoroutineScope,
    private val fetchEpg: suspend (Channel) -> EpgNowNext?
) : RecyclerView.Adapter<ChannelGridAdapter.VH>() {

    private var items: List<Channel> = emptyList()

    fun submitList(list: List<Channel>) {
        items = list
        notifyDataSetChanged()
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

    override fun onViewRecycled(holder: VH) {
        holder.cancelEpgFetch()
    }

    inner class VH(private val b: ItemChannelTileBinding) : RecyclerView.ViewHolder(b.root) {
        private var epgJob: Job? = null

        init {
            b.root.setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.06f else 1f).scaleY(if (hasFocus) 1.06f else 1f).setDuration(150).start()
                b.cardView.cardElevation = if (hasFocus) 14f else 3f
                b.focusOverlay.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
                if (hasFocus) b.focusOverlay.startAnim() else b.focusOverlay.stopAnim()
            }
        }

        fun cancelEpgFetch() {
            epgJob?.cancel()
            epgJob = null
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

            cancelEpgFetch()
            b.tvEpg.visibility = View.GONE
            if (channel.xtreamStreamId.isBlank()) return
            epgJob = epgScope.launch {
                val epg = fetchEpg(channel)
                val pos = adapterPosition
                if (pos == RecyclerView.NO_POSITION || items.getOrNull(pos)?.id != channel.id) return@launch
                if (epg == null || epg.nowTitle.isBlank()) return@launch
                b.tvEpg.text = epg.nowTitle
                b.tvEpg.visibility = View.VISIBLE
            }
        }
    }
}
