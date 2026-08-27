package com.nicotv.iptv.ui.series

import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nicotv.iptv.databinding.ItemSeasonBinding

/** Onglets de saisons affichés horizontalement. */
class SeasonTabAdapter(
    private val onSelect: (Int) -> Unit
) : ListAdapter<SeasonTabAdapter.Season, SeasonTabAdapter.VH>(DIFF) {

    data class Season(val number: Int, val label: String, val allSeen: Boolean = false)

    var selectedNumber: Int = -1
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
        fun bind(season: Season) {
            // « ✓ Vu » ajouté au libellé quand tous les épisodes de la saison sont vus
            // (équivalent du badge .s-vu de la PWA) — vert, comme les autres badges Vu.
            b.tvSeason.text = if (season.allSeen) {
                SpannableStringBuilder(season.label).apply {
                    val suffix = "  ✓ Vu"
                    append(suffix)
                    val start = length - suffix.length
                    setSpan(ForegroundColorSpan(0xFF4CAF50.toInt()), start, length, 0)
                    setSpan(StyleSpan(Typeface.BOLD), start, length, 0)
                }
            } else season.label
            b.root.isSelected = season.number == selectedNumber
            b.root.setOnClickListener { selectedNumber = season.number }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Season>() {
            override fun areItemsTheSame(a: Season, b: Season) = a.number == b.number
            override fun areContentsTheSame(a: Season, b: Season) = a == b
        }
    }
}
