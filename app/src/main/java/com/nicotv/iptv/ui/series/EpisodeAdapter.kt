package com.nicotv.iptv.ui.series

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nicotv.iptv.data.database.entity.DownloadEntity
import com.nicotv.iptv.data.database.entity.EpisodeEntity
import com.nicotv.iptv.databinding.ItemEpisodeBinding
import com.nicotv.iptv.domain.model.EpisodeProgress

class EpisodeAdapter(
    private val onPlay: (EpisodeEntity) -> Unit,
    private val onRestart: (EpisodeEntity) -> Unit = {},
    private val onDownload: (EpisodeEntity) -> Unit = {},
    private val onDeleteDownload: (EpisodeEntity) -> Unit = {},
    private val onCancelDownload: (EpisodeEntity) -> Unit = {}
) : ListAdapter<EpisodeEntity, EpisodeAdapter.VH>(DIFF) {

    // watchKey → état (vu / reprise). Absent = jamais commencé.
    private var progress: Map<Long, EpisodeProgress> = emptyMap()
    // fileKey → téléchargement local (mode avion). Absent = pas téléchargé.
    private var downloads: Map<String, DownloadEntity> = emptyMap()

    fun setProgress(map: Map<Long, EpisodeProgress>) {
        progress = map
        notifyDataSetChanged()
    }

    fun setDownloads(map: Map<String, DownloadEntity>) {
        downloads = map
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
            when {
                // En cours : reprise affichée dès la 1re seconde et jusqu'à la dernière,
                // tant qu'une position est mémorisée (plus de seuil en %). Temps réel
                // affiché (comme la PWA : "12:34 / 45:00"), pas un texte générique.
                state != null && !state.seen -> {
                    b.tvStatus.visibility = View.VISIBLE
                    b.tvStatus.text = "▶ " + if (state.durationMs > 0) {
                        "${fmtTime(state.positionMs)} / ${fmtTime(state.durationMs)}"
                    } else {
                        fmtTime(state.positionMs)
                    }
                    b.tvStatus.setTextColor(0xFF6E84FF.toInt())
                    b.progressResume.visibility = View.VISIBLE
                    b.progressResume.progress = state.percent
                    // Comme la PWA : bouton « depuis le début » sur un épisode commencé.
                    b.btnEpisodeRestart.visibility = View.VISIBLE
                }
                state != null && state.seen -> {
                    b.tvStatus.visibility = View.VISIBLE
                    b.tvStatus.text = "✓ Vu"
                    b.tvStatus.setTextColor(0xFF4CAF50.toInt())
                    b.progressResume.visibility = View.GONE
                    b.btnEpisodeRestart.visibility = View.GONE
                }
                else -> {
                    b.tvStatus.visibility = View.GONE
                    b.progressResume.visibility = View.GONE
                    b.btnEpisodeRestart.visibility = View.GONE
                }
            }

            b.btnEpisodeRestart.setOnClickListener { onRestart(ep) }
            b.root.setOnClickListener { onPlay(ep) }

            val dl = downloads[ep.fileKey]
            when (dl?.state) {
                DownloadEntity.STATE_COMPLETED -> {
                    b.progressEpisodeDownload.visibility = View.GONE
                    b.tvEpisodeDownloadPct.visibility = View.GONE
                    b.ivEpisodeDownload.visibility = View.VISIBLE
                    b.ivEpisodeDownload.setImageResource(com.nicotv.iptv.R.drawable.ic_download_done)
                    b.btnEpisodeDownload.setOnClickListener { onDeleteDownload(ep) }
                }
                DownloadEntity.STATE_QUEUED, DownloadEntity.STATE_DOWNLOADING -> {
                    b.ivEpisodeDownload.visibility = View.GONE
                    val pct = if (dl.bytesTotal > 0) (dl.bytesDownloaded * 100 / dl.bytesTotal).toInt() else -1
                    if (pct >= 0) {
                        b.progressEpisodeDownload.visibility = View.GONE
                        b.tvEpisodeDownloadPct.visibility = View.VISIBLE
                        b.tvEpisodeDownloadPct.text = "$pct%"
                    } else {
                        b.progressEpisodeDownload.visibility = View.VISIBLE
                        b.tvEpisodeDownloadPct.visibility = View.GONE
                    }
                    b.btnEpisodeDownload.setOnClickListener { onCancelDownload(ep) }
                }
                else -> {
                    // FAILED ou jamais téléchargé : icône de téléchargement, tap = lancer.
                    b.progressEpisodeDownload.visibility = View.GONE
                    b.tvEpisodeDownloadPct.visibility = View.GONE
                    b.ivEpisodeDownload.visibility = View.VISIBLE
                    b.ivEpisodeDownload.setImageResource(com.nicotv.iptv.R.drawable.ic_download)
                    b.btnEpisodeDownload.setOnClickListener { onDownload(ep) }
                }
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<EpisodeEntity>() {
            override fun areItemsTheSame(a: EpisodeEntity, b: EpisodeEntity) = a.id == b.id
            override fun areContentsTheSame(a: EpisodeEntity, b: EpisodeEntity) = a == b
        }

        /** Même format que la PWA (fmtTime côté app.js) : M:SS, ou H:MM:SS au-delà d'1h. */
        private fun fmtTime(ms: Long): String {
            val totalSec = ms / 1000
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
    }
}
