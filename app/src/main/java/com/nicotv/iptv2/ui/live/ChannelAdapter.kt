package com.nicotv.iptv2.ui.live

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.nicotv.iptv2.R
import com.nicotv.iptv2.databinding.ItemChannelBinding
import com.nicotv.iptv2.domain.model.Channel
import com.nicotv.iptv2.domain.model.EpgNowNext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** [epgScope] : portée liée au cycle de vie de LiveActivity (lifecycleScope) —
 * les requêtes get_short_epg en cours sont annulées avec l'écran, pas
 * seulement avec le ViewHolder recyclé. [fetchEpg] résout "en cours/à suivre"
 * pour une chaîne (cache Room + appel Xtream à la demande, cf.
 * PlaylistRepository.getShortEpg) — appelé au bind, jamais en amont pour tout
 * le catalogue (des milliers de chaînes = des milliers d'appels). */
class ChannelAdapter(
    private val onClick: (Channel) -> Unit,
    private val onToggleFavorite: (Channel) -> Unit,
    private val epgScope: CoroutineScope,
    private val fetchEpg: suspend (Channel) -> EpgNowNext?
) : ListAdapter<Channel, ChannelAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    override fun onViewRecycled(holder: VH) {
        holder.cancelEpgFetch()
    }

    inner class VH(private val b: ItemChannelBinding) : RecyclerView.ViewHolder(b.root) {
        private var epgJob: Job? = null

        init {
            b.root.setOnFocusChangeListener { _, hasFocus ->
                b.channelRowRing.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
                if (hasFocus) b.channelRowRing.startAnim() else b.channelRowRing.stopAnim()
            }
        }

        fun cancelEpgFetch() {
            epgJob?.cancel()
            epgJob = null
        }

        fun bind(channel: Channel) {
            b.tvName.text = channel.name
            b.ivLogo.load(channel.logoUrl) {
                placeholder(R.drawable.ic_movie_placeholder)
                error(R.drawable.ic_movie_placeholder)
            }
            b.ivFavorite.setImageResource(
                if (channel.isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_border
            )
            b.btnFavorite.setOnClickListener { onToggleFavorite(channel) }
            b.root.setOnClickListener { onClick(channel) }

            cancelEpgFetch()
            b.tvEpg.visibility = View.GONE
            // Vide pour une chaîne issue d'un M3U : pas de mini-guide possible,
            // inutile de lancer une requête qui reviendra toujours bredouille.
            if (channel.xtreamStreamId.isBlank()) return
            epgJob = epgScope.launch {
                val epg = fetchEpg(channel)
                // La vue a pu être recyclée pour une autre chaîne pendant l'appel
                // réseau — adapterPosition + comparaison d'id avant d'écrire.
                val pos = adapterPosition
                if (pos == RecyclerView.NO_POSITION || getItem(pos).id != channel.id) return@launch
                val text = formatEpg(epg) ?: return@launch
                b.tvEpg.text = text
                b.tvEpg.visibility = View.VISIBLE
            }
        }

        private fun formatEpg(epg: EpgNowNext?): String? {
            if (epg == null || epg.nowTitle.isBlank()) return null
            return if (epg.nextTitle.isNotBlank()) "${epg.nowTitle}  ·  à suivre : ${epg.nextTitle}" else epg.nowTitle
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Channel>() {
            override fun areItemsTheSame(a: Channel, b: Channel) = a.id == b.id
            override fun areContentsTheSame(a: Channel, b: Channel) = a == b
        }
    }
}
