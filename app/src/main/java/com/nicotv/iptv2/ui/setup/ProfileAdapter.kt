package com.nicotv.iptv2.ui.setup

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nicotv.iptv2.R
import com.nicotv.iptv2.data.SourceType
import com.nicotv.iptv2.data.database.entity.PlaylistProfileEntity
import com.nicotv.iptv2.databinding.ItemProfileBinding

class ProfileAdapter(
    private val onClick: (PlaylistProfileEntity) -> Unit,
    private val onDelete: (PlaylistProfileEntity) -> Unit
) : ListAdapter<PlaylistProfileEntity, ProfileAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemProfileBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            b.root.setOnFocusChangeListener { _, hasFocus ->
                b.profileRowRing.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
                if (hasFocus) b.profileRowRing.startAnim() else b.profileRowRing.stopAnim()
            }
        }

        fun bind(profile: PlaylistProfileEntity) {
            b.tvName.text = profile.name
            b.tvType.text = when (profile.type) {
                SourceType.M3U_URL.name -> "Playlist M3U (URL)"
                SourceType.M3U_FILE.name -> "Playlist M3U (fichier local)"
                SourceType.XTREAM.name -> "Xtream Codes · ${profile.xtreamHost}"
                else -> profile.type
            }
            b.ivTypeIcon.setImageResource(
                when (profile.type) {
                    SourceType.XTREAM.name -> R.drawable.ic_settings
                    SourceType.M3U_FILE.name -> R.drawable.ic_download
                    else -> R.drawable.ic_refresh
                }
            )
            b.root.setOnClickListener { onClick(profile) }
            b.btnDelete.setOnClickListener { onDelete(profile) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<PlaylistProfileEntity>() {
            override fun areItemsTheSame(a: PlaylistProfileEntity, b: PlaylistProfileEntity) = a.id == b.id
            override fun areContentsTheSame(a: PlaylistProfileEntity, b: PlaylistProfileEntity) = a == b
        }
    }
}
