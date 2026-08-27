package com.nicotv.iptv.ui.downloads

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.nicotv.iptv.R
import com.nicotv.iptv.data.database.entity.DownloadEntity
import com.nicotv.iptv.databinding.ItemDownloadBinding

class DownloadAdapter(
    private val onClick: (DownloadEntity) -> Unit,
    private val onDelete: (DownloadEntity) -> Unit
) : ListAdapter<DownloadEntity, DownloadAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemDownloadBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(d: DownloadEntity) {
            b.tvTitle.text = d.title
            if (d.type == DownloadEntity.TYPE_EPISODE) {
                b.tvSubtitle.visibility = View.VISIBLE
                b.tvSubtitle.text = "S${d.seasonNumber}E${d.episodeNumber} — ${d.episodeTitle}"
            } else {
                b.tvSubtitle.visibility = View.GONE
            }
            b.ivPoster.load(d.posterUrl.ifBlank { null }) {
                placeholder(R.drawable.ic_movie_placeholder)
                error(R.drawable.ic_movie_placeholder)
            }

            val pct = if (d.bytesTotal > 0) (d.bytesDownloaded * 100 / d.bytesTotal).toInt() else 0
            when (d.state) {
                DownloadEntity.STATE_COMPLETED -> {
                    b.tvState.text = "✓ Téléchargé"
                    b.tvState.setTextColor(0xFF4CAF50.toInt())
                    b.progressBar.visibility = View.GONE
                }
                DownloadEntity.STATE_DOWNLOADING -> {
                    b.tvState.text = "Téléchargement… $pct%"
                    b.tvState.setTextColor(0xFF6E84FF.toInt())
                    b.progressBar.visibility = View.VISIBLE
                    b.progressBar.progress = pct
                }
                DownloadEntity.STATE_QUEUED -> {
                    b.tvState.text = "En attente…"
                    b.tvState.setTextColor(0xFF6E84FF.toInt())
                    b.progressBar.visibility = View.VISIBLE
                    b.progressBar.progress = 0
                }
                else -> {
                    b.tvState.text = "Échec du téléchargement"
                    b.tvState.setTextColor(0xFFE05252.toInt())
                    b.progressBar.visibility = View.GONE
                }
            }

            b.root.setOnClickListener { onClick(d) }
            b.btnDelete.setOnClickListener { onDelete(d) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DownloadEntity>() {
            override fun areItemsTheSame(a: DownloadEntity, b: DownloadEntity) = a.key == b.key
            override fun areContentsTheSame(a: DownloadEntity, b: DownloadEntity) = a == b
        }
    }
}
