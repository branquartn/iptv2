package com.nicotv.iptv2.ui.detail

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.nicotv.iptv2.R
import com.nicotv.iptv2.data.tmdb.TmdbCastMember
import com.nicotv.iptv2.databinding.ItemCastMemberBinding

/** Rangée casting horizontale sous le synopsis. Clic sur un acteur → fiche/
 * filmographie (cf. DetailActivity.showActorDialog). */
class CastAdapter(
    private val onClick: (TmdbCastMember) -> Unit
) : RecyclerView.Adapter<CastAdapter.VH>() {

    private var items = listOf<TmdbCastMember>()

    fun submitList(list: List<TmdbCastMember>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemCastMemberBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    inner class VH(private val b: ItemCastMemberBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(member: TmdbCastMember) {
            b.tvCastName.text = member.name
            b.tvCastCharacter.text = member.character
            // Pas de Transformation Coil : le cadrage tête-en-haut est fait par la
            // vue elle-même (TopCropImageView, matrice au dessin).
            b.ivCastPhoto.load(member.profileUrl.ifBlank { null }) {
                crossfade(true)
                placeholder(R.drawable.ic_movie_placeholder)
                error(R.drawable.ic_movie_placeholder)
            }
            b.root.setOnClickListener { onClick(member) }
            b.castFocusRing.visibility = View.INVISIBLE
            b.castFocusRing.stopAnim()
            b.root.setOnFocusChangeListener { _, hasFocus ->
                b.castFocusRing.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
                if (hasFocus) b.castFocusRing.startAnim() else b.castFocusRing.stopAnim()
            }
        }
    }
}
