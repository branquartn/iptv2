package com.nicotv.iptv2.ui.common

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.nicotv.iptv2.R
import com.nicotv.iptv2.data.database.entity.DownloadEntity
import com.nicotv.iptv2.databinding.ItemPosterBinding
import com.nicotv.iptv2.domain.model.Movie

/** Adapter pour le mur d'affiches (grille de posters verticaux). Le badge de
 * téléchargement (mode avion) n'est activé que si [showDownloadBadge] est vrai
 * (mur de films uniquement pour l'instant — pas de sens sur une fiche série). */
class PosterAdapter(
    private val onClick: (Movie) -> Unit,
    private val showDownloadBadge: Boolean = false,
    private val onDownloadClick: (Movie) -> Unit = {}
) : ListAdapter<Movie, PosterAdapter.PosterViewHolder>(DIFF) {

    // movieKey (DownloadEntity.movieKey) → téléchargement local. Absent = pas téléchargé.
    private var downloads: Map<String, DownloadEntity> = emptyMap()

    fun setDownloads(map: Map<String, DownloadEntity>) {
        downloads = map
        notifyDataSetChanged()
    }

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
        holder.bind(getItem(position))
    }

    inner class PosterViewHolder(private val binding: ItemPosterBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(movie: Movie) {
            // Annule toute animation de zoom encore en cours et remet l'échelle à 100%
            binding.root.animate().cancel()
            binding.root.scaleX = 1f
            binding.root.scaleY = 1f
            binding.tvTitle.text = movie.title
            binding.ivFavorite.visibility = if (movie.isFavorite) View.VISIBLE else View.GONE

            // Badge « NOUVEAU » pour les titres récemment ajoutés au catalogue.
            binding.tvBadgeNew.visibility = if (movie.isNew) View.VISIBLE else View.GONE

            // Badge « ✓ Vu » pour les films regardés jusqu'à la fin (isFinished,
            // PAS isSeen qui se déclenche dès l'ouverture de la fiche).
            binding.tvBadgeSeen.visibility = if (movie.isFinished) View.VISIBLE else View.GONE

            // Badge reprise pour les films commencés mais non terminés (icône seule,
            // comme la pastille .prog de la PWA — plus de barre de progression).
            binding.badgeResume.visibility = if (movie.inProgress) View.VISIBLE else View.GONE

            binding.ivPoster.load(movie.posterUrl.ifBlank { movie.backdropUrl }) {
                crossfade(true)
                placeholder(R.drawable.ic_movie_placeholder)
                error(R.drawable.ic_movie_placeholder)
            }

            binding.focusOverlay.visibility = View.INVISIBLE
            binding.focusOverlay.stopAnim()

            // Badge téléchargement : visible UNIQUEMENT si déjà téléchargé (indicateur
            // de statut, pas un bouton pour lancer un téléchargement).
            val isDownloaded = showDownloadBadge && movie.type == Movie.Type.MOVIE &&
                downloads[DownloadEntity.movieKey(movie.id)]?.state == DownloadEntity.STATE_COMPLETED
            binding.badgeDownload.visibility = if (isDownloaded) View.VISIBLE else View.GONE
            if (isDownloaded) binding.badgeDownload.setOnClickListener { onDownloadClick(movie) }

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

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Movie>() {
            override fun areItemsTheSame(a: Movie, b: Movie) = a.id == b.id
            override fun areContentsTheSame(a: Movie, b: Movie) = a == b
        }
    }
}
