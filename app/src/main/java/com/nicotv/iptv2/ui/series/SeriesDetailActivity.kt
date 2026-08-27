package com.nicotv.iptv2.ui.series

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.nicotv.iptv2.IptvApplication
import com.nicotv.iptv2.R
import com.nicotv.iptv2.data.database.entity.EpisodeEntity
import com.nicotv.iptv2.data.database.entity.FavoriteEntity
import com.nicotv.iptv2.data.database.entity.SeriesEntity
import com.nicotv.iptv2.databinding.ActivitySeriesDetailBinding
import com.nicotv.iptv2.player.PlayerActivity
import com.nicotv.iptv2.ui.common.BaseActivity
import kotlinx.coroutines.launch

/** Fiche série : saisons/épisodes. Pour une série Xtream, les épisodes sont
 * récupérés à la demande ici (cf. PlaylistRepository.loadEpisodesForSeries) —
 * pas au chargement initial de la playlist (trop coûteux sur un gros catalogue). */
@UnstableApi
class SeriesDetailActivity : BaseActivity() {

    private lateinit var binding: ActivitySeriesDetailBinding
    private lateinit var episodeAdapter: EpisodeAdapter
    private lateinit var seasonAdapter: SeasonTabAdapter
    private var seriesId: Long = -1L
    private var seriesTitle: String = ""
    private var seriesEntity: SeriesEntity? = null
    private var allEpisodes: List<EpisodeEntity> = emptyList()
    private var isFavorite = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySeriesDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        seriesId = intent.getLongExtra(EXTRA_SERIES_ID, -1L)
        seriesTitle = intent.getStringExtra(EXTRA_SERIES_TITLE) ?: ""
        val posterUrl = intent.getStringExtra(EXTRA_POSTER_URL) ?: ""
        if (seriesId == -1L) { finish(); return }

        binding.tvTitle.text = seriesTitle
        binding.ivPoster.load(posterUrl) { placeholder(R.drawable.ic_movie_placeholder); error(R.drawable.ic_movie_placeholder) }
        binding.ivBackdrop.load(posterUrl) { crossfade(true) }

        binding.btnBack.setOnClickListener { finish() }
        applyRing(binding.btnBack, binding.btnBackRing, 1.25f)
        applyRing(binding.btnFavorite, binding.btnFavoriteRing, 1.1f)
        binding.btnFavorite.setOnClickListener { toggleFavorite() }

        seasonAdapter = SeasonTabAdapter(onSelect = { number -> showSeason(number) })
        binding.rvSeasons.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvSeasons.adapter = seasonAdapter

        episodeAdapter = EpisodeAdapter(
            onPlay = { ep -> playEpisode(ep, resume = true) },
            onRestart = { ep -> playEpisode(ep, resume = false) }
        )
        binding.rvEpisodes.layoutManager = LinearLayoutManager(this)
        binding.rvEpisodes.adapter = episodeAdapter

        loadSeries()
    }

    override fun onResume() {
        super.onResume()
        if (seriesEntity != null) refreshProgress()
    }

    private fun loadSeries() {
        val app = application as IptvApplication
        binding.progressLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val entity = app.playlistRepository.getSeriesEntityById(seriesId)
            if (entity == null) { finish(); return@launch }
            seriesEntity = entity
            binding.tvOverview.text = entity.overview
            binding.tvOverview.visibility = if (entity.overview.isBlank()) View.GONE else View.VISIBLE
            val meta = buildString {
                if (entity.releaseYear.isNotBlank()) append(entity.releaseYear)
                if (entity.rating > 0) { if (isNotEmpty()) append("  •  "); append("★ %.1f".format(entity.rating)) }
                if (entity.category.isNotBlank()) { if (isNotEmpty()) append("  •  "); append(entity.category) }
            }
            binding.tvMeta.text = meta
            binding.tvMeta.visibility = if (meta.isBlank()) View.GONE else View.VISIBLE

            isFavorite = app.playlistRepository.isSeriesFavorite(seriesId)
            updateFavoriteIcon()

            val episodes = try {
                app.playlistRepository.loadEpisodesForSeries(entity)
            } catch (e: Exception) {
                Toast.makeText(this@SeriesDetailActivity, "Impossible de charger les épisodes : ${e.message}", Toast.LENGTH_LONG).show()
                emptyList()
            }
            binding.progressLoading.visibility = View.GONE
            allEpisodes = episodes

            val seasons = episodes.map { it.seasonNumber }.distinct().sorted()
                .map { SeasonTabAdapter.Season(it, "Saison $it") }
            seasonAdapter.submitList(seasons)
            if (seasons.isNotEmpty()) seasonAdapter.selectedNumber = seasons.first().number

            refreshProgress()
        }
    }

    private fun showSeason(number: Int) {
        episodeAdapter.submitList(allEpisodes.filter { it.seasonNumber == number })
    }

    private fun refreshProgress() {
        if (allEpisodes.isEmpty()) return
        lifecycleScope.launch {
            val app = application as IptvApplication
            val map = app.playlistRepository.getEpisodeProgressMap(allEpisodes)
            episodeAdapter.setProgress(map)
        }
    }

    private fun playEpisode(ep: EpisodeEntity, resume: Boolean) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_MOVIE_ID, ep.watchKey)
            putExtra(PlayerActivity.EXTRA_STREAM_URL, ep.streamUrl)
            putExtra(PlayerActivity.EXTRA_TITLE, ep.episodeTitle)
            putExtra(PlayerActivity.EXTRA_RESUME, resume)
            putExtra(PlayerActivity.EXTRA_SERIES_ID, seriesId)
            putExtra(PlayerActivity.EXTRA_SERIES_TITLE, seriesTitle)
            putExtra(PlayerActivity.EXTRA_EPISODE_TITLE, ep.episodeTitle)
            putExtra(PlayerActivity.EXTRA_EPISODE_NUMBER, ep.episodeNumber)
            putExtra(PlayerActivity.EXTRA_SEASON_NUMBER, ep.seasonNumber)
            putExtra(PlayerActivity.EXTRA_FILE_KEY, ep.fileKey)
        })
    }

    private fun toggleFavorite() {
        val app = application as IptvApplication
        lifecycleScope.launch {
            app.playlistRepository.toggleFavorite(seriesId, FavoriteEntity.Type.SERIES, isFavorite)
            isFavorite = !isFavorite
            updateFavoriteIcon()
        }
    }

    private fun updateFavoriteIcon() {
        binding.ivFavorite.setImageResource(if (isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_border)
    }

    private fun applyRing(target: View, ring: com.nicotv.iptv2.ui.common.RotatingBorderView, scale: Float) {
        target.setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) scale else 1f).scaleY(if (hasFocus) scale else 1f).setDuration(150).start()
            ring.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
            if (hasFocus) ring.startAnim() else ring.stopAnim()
        }
    }

    companion object {
        const val EXTRA_SERIES_ID = "extra_series_id"
        const val EXTRA_SERIES_TITLE = "extra_series_title"
        const val EXTRA_POSTER_URL = "extra_poster_url"
    }
}
