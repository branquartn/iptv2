package com.nicotv.iptv2.ui.live

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nicotv.iptv2.databinding.ItemSeasonBinding

/** Chips de catégories horizontales (réutilise le style des onglets saison). */
class CategoryChipAdapter(
    private val onSelect: (String?) -> Unit
) : ListAdapter<String, CategoryChipAdapter.VH>(DIFF) {

    // null = "Toutes"
    var selected: String? = null
        set(value) {
            val old = field
            field = value
            notifyItemRangeChanged(0, itemCount)
            if (old != value) onSelect(value)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemSeasonBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemSeasonBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(category: String) {
            val isAll = category == ALL
            b.tvSeason.text = if (isAll) "Toutes" else category
            b.root.isSelected = (isAll && selected == null) || category == selected
            b.root.setOnClickListener { selected = if (isAll) null else category }
        }
    }

    companion object {
        const val ALL = "__all__"
        private val DIFF = object : DiffUtil.ItemCallback<String>() {
            override fun areItemsTheSame(a: String, b: String) = a == b
            override fun areContentsTheSame(a: String, b: String) = a == b
        }
    }
}
