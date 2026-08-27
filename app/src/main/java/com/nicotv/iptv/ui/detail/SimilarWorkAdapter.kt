package com.nicotv.iptv.ui.detail

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.nicotv.iptv.R
import com.nicotv.iptv.databinding.ItemSimilarMovieBinding
import com.nicotv.iptv.domain.model.SimilarWork

/** [onBadgeClick] : le rond ✓/+ (ouvre si possédé, ajoute sinon). [onPreviewClick] :
 * le reste de la carte (jaquette/titre) — aperçu (synopsis + bande-annonce), jamais
 * d'ajout ni de navigation directe. Comme la PWA.
 * [gridMode] : la carte prend toute la largeur de sa cellule (GridLayoutManager,
 * filmographie acteur) au lieu de sa largeur fixe (rangée horizontale, films
 * similaires).
 * [showBadge] : masqué sur TV/Shield/Fire TV (un badge cliquable séparé du reste de
 * la carte est peu fiable au D-pad) — sur ces appareils, un seul clic ouvre
 * toujours l'aperçu, qui propose alors ajouter/ouvrir à côté de la bande-annonce.
 * [showTypeBadge] : pastille "Film"/"Série" coin opposé (cf. .poster .badge PWA) —
 * recherche TMDb uniquement, pas de sens pour similaires/filmographie (déjà su).
 * [onFocusPositionChanged] : TV seulement — position de la carte qui vient de prendre
 * le focus, pour que l'appelant puisse la restaurer après un aller-retour D-pad vers
 * une autre rangée (sinon focus « le plus proche géométriquement » par défaut, pas
 * forcément la même carte qu'avant — cf. onDpadDownFromCast de CastAdapter).
 * [onDpadUpFromSimilar] : TV seulement — haut D-pad depuis une carte (rangée « Films
 * similaires » du détail film uniquement, pas filmographie/recherche). Même contrat
 * que onDpadDownFromCast : true = focus repris par l'appelant, consomme la touche.
 * [onDpadDownFromSimilar] : symétrique, bas D-pad — vers la rangée d'icônes fixe
 * (Lecture/Favori/…) sous le scroll, restaure la dernière icône focusée. */
class SimilarWorkAdapter(
    private val onBadgeClick: (SimilarWork) -> Unit,
    private val onPreviewClick: (SimilarWork) -> Unit,
    private val gridMode: Boolean = false,
    private val showBadge: Boolean = true,
    private val showTypeBadge: Boolean = false,
    private val onFocusPositionChanged: ((Int) -> Unit)? = null,
    private val onDpadUpFromSimilar: (() -> Boolean)? = null,
    private val onDpadDownFromSimilar: (() -> Boolean)? = null
) : RecyclerView.Adapter<SimilarWorkAdapter.VH>() {

    private var items = listOf<SimilarWork>()

    fun submitList(list: List<SimilarWork>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemSimilarMovieBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        if (gridMode) {
            // RecyclerView.LayoutParams (pas ViewGroup.LayoutParams) : sinon
            // GridLayoutManager ne mesure pas correctement la cellule → largeur
            // incohérente et le ratio 2:3 (calculé à partir d'elle) part de travers.
            // setMargins() explicite : construire un LayoutParams neuf efface la
            // marge de 4dp du XML (layout_margin), non reprise par ce constructeur
            // → cartes collées les unes aux autres en mode grille (filmographie).
            val m = (4 * parent.resources.displayMetrics.density).toInt()
            b.root.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(m, m, m, m)
            }
        }
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    inner class VH(private val b: ItemSimilarMovieBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(work: SimilarWork) {
            b.tvTitle.text = if (work.year.isNotBlank()) "${work.title} · ${work.year}" else work.title
            b.ivPoster.load(work.posterUrl.ifBlank { null }) {
                crossfade(true)
                placeholder(R.drawable.ic_movie_placeholder)
                error(R.drawable.ic_movie_placeholder)
            }
            b.focusOverlay.visibility = View.INVISIBLE
            b.focusOverlay.stopAnim()
            if (showTypeBadge) {
                b.tvBadgeType.visibility = View.VISIBLE
                b.tvBadgeType.text = b.root.context.getString(
                    if (work.isTv) R.string.type_series else R.string.type_movie
                )
            } else {
                b.tvBadgeType.visibility = View.GONE
            }
            if (showBadge) {
                b.tvBadgeOwned.visibility = View.VISIBLE
                b.tvBadgeOwned.text = if (work.owned) "✓" else "+"
                b.tvBadgeOwned.setBackgroundResource(
                    if (work.owned) R.drawable.bg_badge_owned else R.drawable.bg_badge_add
                )
                b.tvBadgeOwned.setOnClickListener { onBadgeClick(work) }
            } else {
                b.tvBadgeOwned.visibility = View.GONE
            }
            b.root.setOnClickListener { onPreviewClick(work) }
            b.root.setOnFocusChangeListener { v, hasFocus ->
                // Rangée horizontale (films similaires) : le zoom pivote depuis le BAS
                // (pivotY = hauteur) → la carte grandit vers le HAUT, jamais sous son
                // bord inférieur. Sinon, la carte étant le dernier contenu du ScrollView
                // (qui cadre le rect de l'item sur le bas de la zone visible), le
                // débordement du zoom passait sous la zone visible et était coupé.
                // Grille (filmographie) : pivot centre par défaut, entourée de marge.
                if (!gridMode) v.pivotY = v.height.toFloat()
                v.animate().scaleX(if (hasFocus) 1.08f else 1f).scaleY(if (hasFocus) 1.08f else 1f)
                    .setDuration(150).start()
                b.cardView.cardElevation = if (hasFocus) 16f else 3f
                b.focusOverlay.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
                if (hasFocus) b.focusOverlay.startAnim() else b.focusOverlay.stopAnim()
                if (hasFocus) {
                    @Suppress("DEPRECATION")
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) onFocusPositionChanged?.invoke(pos)
                }
            }
            b.root.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> onDpadUpFromSimilar?.invoke() == true
                    KeyEvent.KEYCODE_DPAD_DOWN -> onDpadDownFromSimilar?.invoke() == true
                    else -> false
                }
            }
        }
    }
}
