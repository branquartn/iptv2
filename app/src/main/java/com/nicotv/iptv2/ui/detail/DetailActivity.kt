package com.nicotv.iptv2.ui.detail

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.lifecycle.ViewModelProvider
import coil.load
import com.nicotv.iptv2.R
import com.nicotv.iptv2.databinding.ActivityDetailBinding
import com.nicotv.iptv2.domain.model.Movie
import com.nicotv.iptv2.player.PlayerActivity
import com.nicotv.iptv2.ui.common.BaseActivity

/** Fiche film : affiche/backdrop, infos, lecture (avec reprise si en cours),
 * favori. Pas de casting/bande-annonce/similaires (nécessitait TMDb, absent de
 * cette version grand public sans backend). */
class DetailActivity : BaseActivity() {

    private lateinit var binding: ActivityDetailBinding
    private lateinit var viewModel: DetailViewModel
    private var movieId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel = ViewModelProvider(this)[DetailViewModel::class.java]

        movieId = intent.getLongExtra(EXTRA_MOVIE_ID, -1L)
        if (movieId == -1L) { finish(); return }

        binding.btnBack.setOnClickListener { finish() }
        applyRing(binding.btnBack, binding.btnBackRing, 1.25f)
        applyRing(binding.btnFavorite, binding.btnFavoriteRing, 1.1f)
        applyRing(binding.btnPlay, binding.btnPlayRing, 1.05f)

        binding.btnFavorite.setOnClickListener { viewModel.toggleFavorite() }

        viewModel.movie.observe(this) { movie -> movie?.let { bind(it) } }
        viewModel.resumePositionMs.observe(this) { pos ->
            binding.tvPlayLabel.text = getString(if (pos > 0) R.string.action_resume else R.string.action_play)
        }

        viewModel.load(movieId)
    }

    override fun onResume() {
        super.onResume()
        // Le favori/la reprise ont pu changer pendant qu'on regardait le film.
        viewModel.load(movieId)
    }

    private fun bind(movie: Movie) {
        binding.tvTitle.text = movie.title
        binding.ivPoster.load(movie.posterUrl.ifBlank { movie.backdropUrl }) {
            placeholder(R.drawable.ic_movie_placeholder)
            error(R.drawable.ic_movie_placeholder)
        }
        binding.ivBackdrop.load(movie.backdropUrl.ifBlank { movie.posterUrl }) { crossfade(true) }

        val meta = buildString {
            if (movie.releaseYear.isNotBlank()) append(movie.releaseYear)
            if (movie.runtime > 0) { if (isNotEmpty()) append("  •  "); append(movie.runtimeFormatted) }
            if (movie.rating > 0) { if (isNotEmpty()) append("  •  "); append(movie.ratingFormatted) }
            if (movie.category.isNotBlank()) { if (isNotEmpty()) append("  •  "); append(movie.category) }
        }
        binding.tvMeta.text = meta
        binding.tvMeta.visibility = if (meta.isBlank()) View.GONE else View.VISIBLE

        binding.tvGenres.text = movie.genresFormatted
        binding.tvGenres.visibility = if (movie.genresFormatted.isBlank()) View.GONE else View.VISIBLE

        binding.tvOverview.text = movie.overview
        binding.tvOverview.visibility = if (movie.overview.isBlank()) View.GONE else View.VISIBLE

        binding.ivFavorite.setImageResource(if (movie.isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_border)

        binding.btnPlay.setOnClickListener {
            val resumeMs = viewModel.resumePositionMs.value ?: 0L
            startActivity(Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_MOVIE_ID, movie.id)
                putExtra(PlayerActivity.EXTRA_STREAM_URL, movie.streamUrl)
                putExtra(PlayerActivity.EXTRA_TITLE, movie.title)
                putExtra(PlayerActivity.EXTRA_RESUME, resumeMs > 0)
            })
        }
    }

    private fun applyRing(target: View, ring: com.nicotv.iptv2.ui.common.RotatingBorderView, scale: Float) {
        target.setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) scale else 1f).scaleY(if (hasFocus) scale else 1f).setDuration(150).start()
            ring.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
            if (hasFocus) ring.startAnim() else ring.stopAnim()
        }
    }

    companion object {
        const val EXTRA_MOVIE_ID = "extra_movie_id"
    }
}
