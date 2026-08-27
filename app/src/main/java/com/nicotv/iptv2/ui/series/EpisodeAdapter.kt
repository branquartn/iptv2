package com.nicotv.iptv2.ui.series

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nicotv.iptv2.data.database.entity.EpisodeEntity
import com.nicotv.iptv2.databinding.ItemEpisodeBinding
import com.nicotv.iptv2.domain.model.EpisodeProgress

class EpisodeAdapter(
    private val onPlay: (EpisodeEntity) -> Unit,
    private val onRestart: (EpisodeEntity) -> Unit = {}
) : ListAdapter<EpisodeEntity, EpisodeAdapter.VH>(DIFF) {

    // watchKey → état de reprise. Absent = jamais commencé (ou terminé — l'entrée
    // est supprimée à la fin de la lecture, cf. PlaylistRepository.saveWatchPosition).
    private var progress: Map<Long, EpisodeProgress> = emptyMap()

    fun setProgress(map: Map<Long, EpisodeProgress>) {
        progress = map
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemEpisodeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemEpisodeBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            // Anneau blanc tournant au focus clavier/télécommande (RotatingBorderView),
            // remplace l'ancien contour statique de bg_episode_item.
            b.root.setOnFocusChangeListener { _, hasFocus ->
                b.episodeRowRing.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
                if (hasFocus) b.episodeRowRing.startAnim() else b.episodeRowRing.stopAnim()
            }
        }

        fun bind(ep: EpisodeEntity) {
            b.tvTitle.text = ep.episodeTitle

            if (ep.overview.isBlank()) {
                b.tvOverview.visibility = View.GONE
            } else {
                b.tvOverview.visibility = View.VISIBLE
                b.tvOverview.text = ep.overview
            }

            val state = progress[ep.watchKey]
            if (state != null) {
                // En cours : reprise affichée dès la 1re seconde et jusqu'à la dernière,
                // tant qu'une position est mémorisée. Temps réel affiché ("12:34 / 45:00").
                b.tvStatus.visibility = View.VISIBLE
                b.tvStatus.text = "▶ " + if (state.durationMs > 0) {
                    "${fmtTime(state.positionMs)} / ${fmtTime(state.durationMs)}"
                } else {
                    fmtTime(state.positionMs)
                }
                b.tvStatus.setTextColor(0xFF6E84FF.toInt())
                b.progressResume.visibility = View.VISIBLE
                b.progressResume.progress = state.percent
                b.btnEpisodeRestart.visibility = View.VISIBLE
            } else {
                b.tvStatus.visibility = View.GONE
                b.progressResume.visibility = View.GONE
                b.btnEpisodeRestart.visibility = View.GONE
            }

            b.btnEpisodeRestart.setOnClickListener { onRestart(ep) }
            b.root.setOnClickListener { onPlay(ep) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<EpisodeEntity>() {
            override fun areItemsTheSame(a: EpisodeEntity, b: EpisodeEntity) = a.id == b.id
            override fun areContentsTheSame(a: EpisodeEntity, b: EpisodeEntity) = a == b
        }

        /** M:SS, ou H:MM:SS au-delà d'1h. */
        private fun fmtTime(ms: Long): String {
            val totalSec = ms / 1000
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
    }
}
