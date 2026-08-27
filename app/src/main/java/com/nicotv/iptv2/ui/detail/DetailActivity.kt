package com.nicotv.iptv2.ui.detail

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.nicotv.iptv2.R
import com.nicotv.iptv2.data.tmdb.TmdbCastMember
import com.nicotv.iptv2.databinding.ActivityDetailBinding
import com.nicotv.iptv2.domain.model.Movie
import com.nicotv.iptv2.domain.model.OpenTarget
import com.nicotv.iptv2.domain.model.SimilarWork
import com.nicotv.iptv2.player.PlayerActivity
import com.nicotv.iptv2.ui.common.BaseActivity
import com.nicotv.iptv2.ui.common.isTvDevice
import com.nicotv.iptv2.ui.series.SeriesDetailActivity
import kotlinx.coroutines.launch

/**
 * Fiche film : affiche/backdrop, infos, lecture (avec reprise), favori, et —
 * même présentation que NicoTV — casting, réalisateur, films similaires et
 * bande-annonce (TMDb, résolu par recherche de titre, pas de compte). Sans
 * backend pour "ajouter à la playlist" : un film similaire/de filmographie déjà
 * présent dans le catalogue chargé s'ouvre (✓), sinon le badge (+) informe juste
 * qu'il n'y est pas.
 */
class DetailActivity : BaseActivity() {

    private lateinit var binding: ActivityDetailBinding
    private lateinit var viewModel: DetailViewModel
    private var movieId: Long = -1L
    private var currentResumePos: Long = 0L
    private var currentDurationMs: Long = 0L
    private val tvMode by lazy { isTvDevice() }
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel = ViewModelProvider(this)[DetailViewModel::class.java]

        // Marge basse réduite sur mobile (pas d'overscan à gérer, contrairement à TV).
        if (!tvMode) {
            binding.contentRoot.setPadding(
                binding.contentRoot.paddingLeft, binding.contentRoot.paddingTop,
                binding.contentRoot.paddingRight, (16 * resources.displayMetrics.density).toInt()
            )
        }

        movieId = intent.getLongExtra(EXTRA_MOVIE_ID, -1L)
        if (movieId == -1L) { finish(); return }

        val castAdapter = CastAdapter(onClick = { showActorDialog(it) })
        binding.rvCast.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvCast.adapter = castAdapter

        val similarAdapter = SimilarWorkAdapter(
            onBadgeClick = { handleWorkClick(it) },
            onPreviewClick = { showWorkPreview(it) }
        )
        binding.rvSimilar.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvSimilar.adapter = similarAdapter

        viewModel.movie.observe(this) { movie -> movie?.let { bind(it) } }
        viewModel.resumePositionMs.observe(this) { pos ->
            currentResumePos = pos
            binding.btnRestart.visibility = if (pos > 0) View.VISIBLE else View.GONE
            updatePlayRuntimeDisplay()
        }
        viewModel.hasTmdbMatch.observe(this) { hasMatch ->
            binding.btnTrailer.visibility = if (hasMatch) View.VISIBLE else View.GONE
        }
        viewModel.cast.observe(this) { cast ->
            castAdapter.submitList(cast)
            binding.tvCastTitle.visibility = if (cast.isNotEmpty()) View.VISIBLE else View.GONE
            binding.rvCast.visibility = if (cast.isNotEmpty()) View.VISIBLE else View.GONE
        }
        viewModel.director.observe(this) { director ->
            binding.llDirector.visibility = if (director != null) View.VISIBLE else View.GONE
            binding.tvDirector.text = director?.name ?: ""
            binding.tvDirector.setOnClickListener {
                director?.let { d ->
                    showActorDialog(TmdbCastMember(id = d.id, name = d.name, character = "", profilePath = d.profilePath), asDirector = true)
                }
            }
        }
        viewModel.similar.observe(this) { similar ->
            similarAdapter.submitList(similar)
            binding.tvSimilarTitle.visibility = if (similar.isNotEmpty()) View.VISIBLE else View.GONE
            binding.rvSimilar.visibility = if (similar.isNotEmpty()) View.VISIBLE else View.GONE
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnFavorite.setOnClickListener { viewModel.toggleFavorite() }

        val iconRings = mapOf(
            binding.btnBack to binding.btnBackRing,
            binding.btnPlay to binding.btnPlayRing,
            binding.btnFavorite to binding.btnFavoriteRing,
            binding.btnRestart to binding.btnRestartRing,
            binding.btnTrailer to binding.btnTrailerRing
        )
        iconRings.forEach { (view, ring) ->
            view.setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.25f else 1f).scaleY(if (hasFocus) 1.25f else 1f).setDuration(150).start()
                v.z = if (hasFocus) 10f else 0f
                ring.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
                if (hasFocus) ring.startAnim() else ring.stopAnim()
            }
        }
        setTextLinkFocusEffect(binding.tvOverviewToggle)
        setTextLinkFocusEffect(binding.tvDirector)

        binding.btnPlay.requestFocus()
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
            crossfade(true)
            placeholder(R.drawable.ic_movie_placeholder)
        }
        binding.ivBackdrop.load(movie.backdropUrl.ifBlank { movie.posterUrl }) {
            crossfade(true)
            placeholder(R.drawable.gradient_hero)
        }
        binding.tvYear.text = movie.releaseYear
        binding.tvRuntime.text = movie.runtimeFormatted
        currentDurationMs = movie.runtime * 60_000L
        updatePlayRuntimeDisplay()
        binding.tvRating.text = movie.ratingFormatted
        binding.tvGenres.text = movie.genresFormatted.ifBlank { movie.category }

        binding.tvOverview.text = movie.overview
        binding.tvOverview.maxLines = 3
        binding.tvOverview.ellipsize = TextUtils.TruncateAt.END
        var overviewExpanded = false
        binding.tvOverview.post {
            binding.tvOverviewToggle.visibility =
                if ((binding.tvOverview.layout?.getEllipsisCount(2) ?: 0) > 0) View.VISIBLE else View.GONE
        }
        binding.tvOverviewToggle.setOnClickListener {
            overviewExpanded = !overviewExpanded
            if (overviewExpanded) {
                binding.tvOverview.maxLines = Int.MAX_VALUE
                binding.tvOverview.ellipsize = null
                binding.tvOverviewToggle.text = getString(R.string.overview_less)
            } else {
                binding.tvOverview.maxLines = 3
                binding.tvOverview.ellipsize = TextUtils.TruncateAt.END
                binding.tvOverviewToggle.text = getString(R.string.overview_more)
            }
        }

        binding.ivFavorite.setImageDrawable(
            AppCompatResources.getDrawable(this, if (movie.isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_border)
        )
        if (!movie.isFavorite) {
            binding.ivFavorite.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.text_secondary))
        } else {
            binding.ivFavorite.imageTintList = null
        }

        binding.btnPlay.setOnClickListener { play(movie, resume = currentResumePos > 0) }
        binding.btnRestart.setOnClickListener { play(movie, resume = false) }

        viewModel.loadExtras(movie.id, movie.tmdbId, movie.title)
        binding.btnTrailer.setOnClickListener {
            binding.btnTrailer.isEnabled = false
            lifecycleScope.launch {
                val key = viewModel.loadTrailerKey()
                binding.btnTrailer.isEnabled = true
                if (key.isNullOrBlank()) {
                    Toast.makeText(this@DetailActivity, getString(R.string.not_available_yet), Toast.LENGTH_SHORT).show()
                } else {
                    openYoutube(key)
                }
            }
        }
    }

    // ── Casting / films similaires / bande-annonce ──────────────────────────

    private fun handleWorkClick(work: SimilarWork) {
        lifecycleScope.launch {
            when (val target = viewModel.resolveTarget(work)) {
                is OpenTarget.MovieTarget -> startActivity(
                    Intent(this@DetailActivity, DetailActivity::class.java).putExtra(EXTRA_MOVIE_ID, target.movieId)
                )
                is OpenTarget.SeriesTarget -> startActivity(
                    Intent(this@DetailActivity, SeriesDetailActivity::class.java)
                        .putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, target.seriesId)
                        .putExtra(SeriesDetailActivity.EXTRA_SERIES_TITLE, target.title)
                        .putExtra(SeriesDetailActivity.EXTRA_POSTER_URL, target.posterUrl)
                )
                null -> Toast.makeText(this@DetailActivity, getString(R.string.work_not_in_playlist), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setTextLinkFocusEffect(tv: TextView) {
        val restColor = tv.currentTextColor
        tv.setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) 1.1f else 1f).scaleY(if (hasFocus) 1.1f else 1f).setDuration(150).start()
            if (hasFocus) {
                tv.setTextColor(android.graphics.Color.WHITE)
                tv.paintFlags = tv.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            } else {
                tv.setTextColor(restColor)
                tv.paintFlags = tv.paintFlags and android.graphics.Paint.UNDERLINE_TEXT_FLAG.inv()
            }
        }
    }

    private fun showActorDialog(member: TmdbCastMember, asDirector: Boolean = false) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_actor, null)
        view.findViewById<com.nicotv.iptv2.ui.common.MaxHeightScrollView>(R.id.actor_scroll)
            ?.setChromeReserveDp(if (tvMode) 220 else 90)
        val actorHeader = view.findViewById<View>(R.id.actor_header)
        val tvActorName = view.findViewById<TextView>(R.id.tv_actor_name)
        val btnActorClose = view.findViewById<TextView>(R.id.btn_actor_close)
        val ivPhoto = view.findViewById<ImageView>(R.id.iv_actor_photo)
        val tvBio = view.findViewById<TextView>(R.id.tv_actor_bio)
        val progress = view.findViewById<ProgressBar>(R.id.progress_actor)
        val rv = view.findViewById<RecyclerView>(R.id.rv_actor_filmography)
        view.findViewById<TextView>(R.id.tv_filmo_label)?.setText(
            if (asDirector) R.string.title_directed_films else R.string.title_filmography
        )

        ivPhoto.load(member.profileUrl.ifBlank { null }) {
            crossfade(true)
            placeholder(R.drawable.ic_movie_placeholder)
            error(R.drawable.ic_movie_placeholder)
        }

        val filmoAdapter = SimilarWorkAdapter(
            onBadgeClick = { handleWorkClick(it) },
            onPreviewClick = { showWorkPreview(it) },
            gridMode = true
        )
        rv.layoutManager = GridLayoutManager(this, filmographySpanCount())
        rv.adapter = filmoAdapter

        val builder = AlertDialog.Builder(this).setView(view)
        if (tvMode) builder.setTitle(member.name).setNegativeButton(android.R.string.ok, null)
        val d = builder.create()
        d.show()
        if (!tvMode) {
            actorHeader.visibility = View.VISIBLE
            tvActorName.text = member.name
            btnActorClose.setOnClickListener { d.dismiss() }
        }
        d.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        d.window?.setBackgroundDrawableResource(R.drawable.bg_dialog)
        rv.isNestedScrollingEnabled = false

        lifecycleScope.launch {
            val person = viewModel.loadPerson(member.id)
            tvBio.text = person?.biography?.takeIf { it.isNotBlank() } ?: ""
            tvBio.visibility = if (tvBio.text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        lifecycleScope.launch {
            val filmography = if (asDirector) viewModel.loadPersonDirected(member.id) else viewModel.loadPersonFilmography(member.id)
            progress.visibility = View.GONE
            filmoAdapter.submitList(filmography)
            rv.post { view.requestLayout() }
        }
    }

    private fun updatePlayRuntimeDisplay() {
        if (currentResumePos <= 0) {
            binding.tvPlayRuntime.visibility = View.GONE
            return
        }
        binding.tvPlayRuntime.text = "${formatClock(currentResumePos)}/${formatClock(currentDurationMs)}"
        binding.tvPlayRuntime.visibility = View.VISIBLE
    }

    private fun formatClock(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    private fun openYoutube(videoKey: String) {
        val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoKey"))
        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoKey"))
        try {
            startActivity(appIntent)
        } catch (e: ActivityNotFoundException) {
            startActivity(webIntent)
        }
    }

    private fun showWorkPreview(work: SimilarWork) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_work_preview, null)
        val previewHeader = view.findViewById<View>(R.id.preview_header)
        val tvPreviewTitle = view.findViewById<TextView>(R.id.tv_preview_title)
        val btnClose = view.findViewById<TextView>(R.id.btn_preview_close)
        val ivPoster = view.findViewById<ImageView>(R.id.iv_preview_poster)
        val tvYear = view.findViewById<TextView>(R.id.tv_preview_year)
        val tvRuntime = view.findViewById<TextView>(R.id.tv_preview_runtime)
        val tvRating = view.findViewById<TextView>(R.id.tv_preview_rating)
        val tvGenres = view.findViewById<TextView>(R.id.tv_preview_genres)
        val tvOverview = view.findViewById<TextView>(R.id.tv_preview_overview)
        val btnTrailer = view.findViewById<View>(R.id.btn_preview_trailer)
        val trailerRing = view.findViewById<com.nicotv.iptv2.ui.common.RotatingBorderView>(R.id.trailer_ring)
        val btnAddWrap = view.findViewById<View>(R.id.btn_preview_add_wrap)
        val btnAdd = view.findViewById<TextView>(R.id.btn_preview_add)
        val addRing = view.findViewById<com.nicotv.iptv2.ui.common.RotatingBorderView>(R.id.add_ring)

        ivPoster.load(work.posterUrl.ifBlank { work.backdropUrl }.ifBlank { null }) {
            crossfade(true)
            placeholder(R.drawable.ic_movie_placeholder)
        }
        if (work.year.isNotBlank()) { tvYear.visibility = View.VISIBLE; tvYear.text = work.year }
        if (work.rating > 0f) { tvRating.visibility = View.VISIBLE; tvRating.text = "★ %.1f".format(work.rating) }
        tvOverview.text = work.overview.ifBlank { getString(R.string.no_overview) }

        val builder = AlertDialog.Builder(this).setView(view)
        if (tvMode) builder.setTitle(work.title).setNegativeButton(android.R.string.ok, null)
        val d = builder.create()
        d.show()
        if (!tvMode) {
            previewHeader.visibility = View.VISIBLE
            tvPreviewTitle.text = work.title
            btnClose.setOnClickListener { d.dismiss() }
        }
        val width = (resources.displayMetrics.widthPixels * 0.8).toInt()
        d.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        d.window?.setBackgroundDrawableResource(R.drawable.bg_dialog)

        lifecycleScope.launch {
            val (genres, runtime) = viewModel.loadWorkGenresAndRuntime(work.tmdbId, work.isTv)
            if (genres.isNotBlank()) { tvGenres.visibility = View.VISIBLE; tvGenres.text = genres }
            if (runtime > 0) { tvRuntime.visibility = View.VISIBLE; tvRuntime.text = "${runtime / 60}h ${runtime % 60}min" }
        }

        btnTrailer.setOnClickListener {
            btnTrailer.isEnabled = false
            lifecycleScope.launch {
                val key = viewModel.loadTrailerKeyFor(work.tmdbId, work.isTv)
                btnTrailer.isEnabled = true
                if (key.isNullOrBlank()) {
                    Toast.makeText(this@DetailActivity, getString(R.string.not_available_yet), Toast.LENGTH_SHORT).show()
                } else {
                    openYoutube(key)
                }
            }
        }
        btnTrailer.setOnFocusChangeListener { _, hasFocus ->
            trailerRing.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
            if (hasFocus) trailerRing.startAnim() else trailerRing.stopAnim()
        }

        btnAdd.text = if (work.owned) "✓" else "+"
        btnAdd.setBackgroundResource(if (work.owned) R.drawable.bg_badge_owned else R.drawable.bg_badge_add)
        btnAddWrap.setOnClickListener {
            d.dismiss()
            handleWorkClick(work)
        }
        btnAddWrap.setOnFocusChangeListener { _, hasFocus ->
            addRing.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
            if (hasFocus) addRing.startAnim() else addRing.stopAnim()
        }
    }

    private fun play(movie: Movie, resume: Boolean) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_MOVIE_ID, movie.id)
            putExtra(PlayerActivity.EXTRA_STREAM_URL, movie.streamUrl)
            putExtra(PlayerActivity.EXTRA_TITLE, movie.title)
            putExtra(PlayerActivity.EXTRA_RESUME, resume)
        })
    }

    /** Même formule que MoviesActivity.computeSpanCount() : jaquettes de la
     * filmographie acteur à la même taille que le mur Films. */
    private fun filmographySpanCount(): Int {
        val screenWidthDp = resources.configuration.screenWidthDp
        return (screenWidthDp / 112).coerceIn(6, 10)
    }

    companion object {
        const val EXTRA_MOVIE_ID = "extra_movie_id"
    }
}
