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

class ChannelAdapter(
    private val onClick: (Channel) -> Unit,
    private val onToggleFavorite: (Channel) -> Unit
) : ListAdapter<Channel, ChannelAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemChannelBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            b.root.setOnFocusChangeListener { _, hasFocus ->
                b.channelRowRing.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
                if (hasFocus) b.channelRowRing.startAnim() else b.channelRowRing.stopAnim()
            }
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
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Channel>() {
            override fun areItemsTheSame(a: Channel, b: Channel) = a.id == b.id
            override fun areContentsTheSame(a: Channel, b: Channel) = a == b
        }
    }
}
