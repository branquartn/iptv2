package com.nicotv.iptv.ui.detail

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.nicotv.iptv.R
import com.nicotv.iptv.data.network.model.TmdbCastMember
import com.nicotv.iptv.databinding.ItemCastMemberBinding

/** Rangée casting horizontale sous le synopsis (portage de la rangée « Casting » PWA).
 * Clic sur un acteur → fiche/filmographie (cf. DetailActivity.showActorDialog).
 * [onDpadDownFromCast] : TV seulement — bas D-pad depuis un acteur. Par défaut (retour
 * null/false), la recherche de focus standard s'applique (plus proche géométriquement,
 * pas forcément le même film qu'avant). Renvoyer true = l'appelant a repris la main sur
 * le focus (restaure la position mémorisée dans « Films similaires »), consomme la touche.
 * [onDpadUpFromCast] : TV seulement — haut D-pad depuis un acteur, même contrat que
 * [onDpadDownFromCast] (retour true = focus repris par l'appelant, ex. Réalisé par).
 * [onFocusPositionChanged] : position de l'acteur qui vient de prendre le focus, pour la
 * restaurer après un aller-retour D-pad vers « + Lire la suite » (au-dessus) ou « Films
 * similaires » (en dessous) — symétrique de SimilarWorkAdapter.onFocusPositionChanged. */
class CastAdapter(
    private val onClick: (TmdbCastMember) -> Unit,
    private val onDpadDownFromCast: (() -> Boolean)? = null,
    private val onDpadUpFromCast: (() -> Boolean)? = null,
    private val onFocusPositionChanged: ((Int) -> Unit)? = null
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
            // vue elle-même (TopCropImageView, matrice au dessin) — déterministe,
            // insensible aux bugs de timing/cache/config des transformations bitmap.
            b.ivCastPhoto.load(member.profileUrl.ifBlank { null }) {
                crossfade(true)
                placeholder(R.drawable.ic_movie_placeholder)
                error(R.drawable.ic_movie_placeholder)
            }
            b.root.setOnClickListener { onClick(member) }
            // Juste l'anneau autour de la photo ronde au focus télécommande — pas de
            // zoom sur tout l'item (nom/rôle ne doivent pas bouger).
            b.castFocusRing.visibility = View.INVISIBLE
            b.castFocusRing.stopAnim()
            b.root.setOnFocusChangeListener { _, hasFocus ->
                b.castFocusRing.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
                if (hasFocus) b.castFocusRing.startAnim() else b.castFocusRing.stopAnim()
                if (hasFocus) {
                    @Suppress("DEPRECATION")
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) onFocusPositionChanged?.invoke(pos)
                }
            }
            b.root.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> onDpadDownFromCast?.invoke() == true
                    KeyEvent.KEYCODE_DPAD_UP -> onDpadUpFromCast?.invoke() == true
                    else -> false
                }
            }
        }
    }
}
