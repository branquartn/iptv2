package com.nicotv.iptv2.ui.common

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.nicotv.iptv2.R
import com.nicotv.iptv2.databinding.ItemPosterBinding
import com.nicotv.iptv2.domain.model.Movie

/** Adapter pour le mur d'affiches (grille de posters verticaux) — films et séries.
 *
 * ⚠️ Pas un `ListAdapter`/`DiffUtil` (retiré 28/08/2026) : sur un catalogue Xtream
 * de plusieurs dizaines/centaines de milliers de titres (cf. CLAUDE.md, panel de
 * test ~136 700 films), un changement de catégorie/recherche remplace la liste
 * affichée par un sous-ensemble bien plus petit — DiffUtil doit alors calculer un
 * diff entre une liste énorme et une liste réduite (Myers, coût proche de O(N*D)
 * avec D ~ N ici) : plusieurs secondes à largement plus, perçu comme "le filtre
 * ne fait rien" (le compteur de résultats change, l'affichage jamais). Aucune
 * perte fonctionnelle à l'abandon du diff : `onAttachedToRecyclerView` désactive
 * déjà l'item animator (conflit avec le zoom de focus), DiffUtil ne servait donc
 * qu'à calculer des animations jamais jouées. `notifyDataSetChanged()` ne
 * redessine que les vues effectivement visibles, coût constant quelle que soit
 * la taille du catalogue. */
class PosterAdapter(
    private val onClick: (Movie) -> Unit
) : RecyclerView.Adapter<PosterAdapter.PosterViewHolder>() {

    private var items: List<Movie> = emptyList()

    fun submitList(list: List<Movie>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        // Désactive les animations d'items : DefaultItemAnimator anime itemView via
        // view.animate(), le MÊME ViewPropertyAnimator que notre zoom de focus. Le
        // cancel() dans bind() interrompait alors l'animation du RecyclerView en plein
        // layout → « Tmp detached view should be removed ... » (crash au retour du
        // player, quand la barre de progression met l'affiche à jour). Sans item
        // animator, les mises à jour s'appliquent instantanément (plus d'effet de
        // « refresh ») et view.animate() n'appartient plus qu'au zoom de focus.
        recyclerView.itemAnimator = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PosterViewHolder {
        val binding = ItemPosterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PosterViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PosterViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class PosterViewHolder(private val binding: ItemPosterBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(movie: Movie) {
            // Annule toute animation de zoom encore en cours et remet l'échelle à 100%
            binding.root.animate().cancel()
            binding.root.scaleX = 1f
            binding.root.scaleY = 1f
            binding.tvTitle.text = movie.displayTitle
            binding.ivFavorite.visibility = if (movie.isFavorite) View.VISIBLE else View.GONE
            binding.tvBadgeSeen.visibility = if (movie.isFinished) View.VISIBLE else View.GONE

            // Badge reprise pour les films/séries commencés mais non terminés (icône
            // seule, comme la pastille .prog de la PWA — plus de barre de progression).
            binding.badgeResume.visibility = if (movie.inProgress) View.VISIBLE else View.GONE

            binding.ivPoster.load(movie.posterUrl.ifBlank { movie.backdropUrl }) {
                crossfade(true)
                placeholder(R.drawable.ic_movie_placeholder)
                error(R.drawable.ic_movie_placeholder)
            }

            binding.focusOverlay.visibility = View.INVISIBLE
            binding.focusOverlay.stopAnim()

            binding.root.setOnClickListener { onClick(movie) }
            binding.root.setOnFocusChangeListener { v, hasFocus ->
                v.animate()
                    .scaleX(if (hasFocus) 1.08f else 1f)
                    .scaleY(if (hasFocus) 1.08f else 1f)
                    .setDuration(150).start()
                binding.cardView.cardElevation = if (hasFocus) 16f else 3f
                binding.focusOverlay.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
                if (hasFocus) binding.focusOverlay.startAnim() else binding.focusOverlay.stopAnim()
            }
        }
    }
}
