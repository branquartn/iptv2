package com.nicotv.iptv.ui.series

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.nicotv.iptv.IptvApplication
import com.nicotv.iptv.R
import com.nicotv.iptv.data.database.entity.DownloadEntity
import com.nicotv.iptv.data.database.entity.EpisodeEntity
import com.nicotv.iptv.databinding.ActivitySeriesDetailBinding
import com.nicotv.iptv.domain.model.EpisodeProgress
import com.nicotv.iptv.databinding.ItemTmdbResultBinding
import com.nicotv.iptv.data.network.model.TmdbMultiResult
import com.nicotv.iptv.player.PlayerActivity
import kotlinx.coroutines.launch

@UnstableApi
class SeriesDetailActivity : com.nicotv.iptv.ui.common.BaseActivity() {

    private lateinit var binding: ActivitySeriesDetailBinding
    private lateinit var episodeAdapter: EpisodeAdapter
    private lateinit var seasonAdapter: SeasonTabAdapter

    private var seriesId: Long = -1L
    private var seriesTitle: String = ""
    private var allEpisodes: List<EpisodeEntity> = emptyList()
    private var episodesBySeason: Map<Int, List<EpisodeEntity>> = emptyMap()
    private var isFavorite: Boolean = false
    private var tmdbDialog: AlertDialog? = null
    // watchKey de l'épisode à cibler (en cours, ou suivant si le dernier vu est
    // terminé) une fois la saison correspondante affichée. Consommé une seule fois.
    private var pendingScrollWatchKey: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySeriesDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        seriesId = intent.getLongExtra(EXTRA_SERIES_ID, -1L)
        seriesTitle = intent.getStringExtra(EXTRA_SERIES_TITLE) ?: ""
        binding.tvTitle.text = seriesTitle

        episodeAdapter = EpisodeAdapter(
            onPlay = { ep -> playEpisode(ep, resume = true) },
            onRestart = { ep -> playEpisode(ep, resume = false) },
            onDownload = { ep -> (application as IptvApplication).downloadRepository.enqueueEpisode(ep, seriesTitle) },
            onDeleteDownload = { ep -> confirmDeleteDownload(ep) },
            onCancelDownload = { ep ->
                lifecycleScope.launch { (application as IptvApplication).downloadRepository.delete(ep.fileKey) }
            }
        )
        binding.rvEpisodes.apply {
            layoutManager = LinearLayoutManager(this@SeriesDetailActivity)
            adapter = episodeAdapter
        }
        lifecycleScope.launch {
            (application as IptvApplication).downloadRepository.getAllFlow().collect { list ->
                episodeAdapter.setDownloads(
                    list.filter { it.type == DownloadEntity.TYPE_EPISODE }.associateBy { it.key }
                )
            }
        }

        seasonAdapter = SeasonTabAdapter(onSelect = { seasonNumber -> applySeasonEpisodes(seasonNumber) })
        binding.rvSeasons.apply {
            layoutManager = LinearLayoutManager(this@SeriesDetailActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = seasonAdapter
        }

        binding.btnBack.setOnClickListener { finish() }
        // Icône + anneau blanc tournant (RotatingBorderView), même pattern que la
        // fiche film (avant : bouton texte "Retour" avec juste un zoom).
        binding.btnBack.setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) 1.25f else 1f)
                .scaleY(if (hasFocus) 1.25f else 1f)
                .setDuration(150).start()
            v.z = if (hasFocus) 10f else 0f
            binding.btnBackRing.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
            if (hasFocus) binding.btnBackRing.startAnim() else binding.btnBackRing.stopAnim()
        }

        // Favori
        binding.btnFavorite.setOnClickListener {
            if (seriesId == -1L) return@setOnClickListener
            val app = application as IptvApplication
            lifecycleScope.launch {
                isFavorite = app.mediaRepository.toggleSeriesFavorite(
                    seriesId,
                    app.sessionManager.getUsername(),
                    app.sessionManager.bearer()
                )
                applyFavoriteState()
            }
        }
        listOf(binding.btnFavorite, binding.btnRelinkTmdb).forEach { view ->
            view.setOnFocusChangeListener { v, hasFocus ->
                v.animate()
                    .scaleX(if (hasFocus) 1.25f else 1f)
                    .scaleY(if (hasFocus) 1.25f else 1f)
                    .setDuration(150).start()
                v.z = if (hasFocus) 10f else 0f
            }
        }

        // TMDb relink
        binding.btnRelinkTmdb.setOnClickListener { showTmdbSearchDialog() }

        // Téléchargement de la saison actuellement sélectionnée (mode avion)
        binding.btnDownloadSeason.setOnClickListener {
            val episodes = episodesBySeason[seasonAdapter.selectedNumber].orEmpty()
            if (episodes.isEmpty()) return@setOnClickListener
            (application as IptvApplication).downloadRepository.enqueueSeason(episodes, seriesTitle)
            Toast.makeText(this, "${episodes.size} épisode(s) en attente de téléchargement", Toast.LENGTH_SHORT).show()
        }

        if (seriesId != -1L) loadData()
    }

    override fun onResume() {
        super.onResume()
        // Présence admin.nicotv.ovh (« qui regarde quoi ») : écran courant hors lecture.
        (application as IptvApplication).reportScreen("Fiche : $seriesTitle")
        // Rafraîchit l'état vu / reprise après retour du lecteur
        if (allEpisodes.isNotEmpty()) refreshProgress()
    }

    private fun applyFavoriteState() {
        binding.ivFavorite.setImageDrawable(
            AppCompatResources.getDrawable(this, R.drawable.ic_favorite_filled)
        )
        binding.ivFavorite.imageTintList = if (isFavorite) null
        else ColorStateList.valueOf(ContextCompat.getColor(this, R.color.text_secondary))
    }

    private fun loadData() {
        val app = application as IptvApplication
        val repo = app.mediaRepository
        lifecycleScope.launch {
            isFavorite = repo.isSeriesFavorite(seriesId)
            applyFavoriteState()

            // Ouvrir la fiche retire le badge NOUVEAU (comme markItemSeen() côté
            // PWA) — et synchronise avec les autres appareils/la PWA.
            repo.markSeriesSeen(seriesId, app.sessionManager.getUsername(), app.sessionManager.bearer())

            repo.getSeriesById(seriesId)?.let { s ->
                binding.ivPoster.load(s.posterUrl.ifBlank { null }) {
                    crossfade(true)
                    placeholder(R.drawable.ic_movie_placeholder)
                    error(R.drawable.ic_movie_placeholder)
                }
                binding.ivBackdrop.load(s.backdropUrl.ifBlank { s.posterUrl }.ifBlank { null }) {
                    crossfade(true)
                }
                val meta = listOfNotNull(
                    s.releaseYear.ifBlank { null },
                    if (s.rating > 0) "★ %.1f".format(s.rating) else null,
                    s.genres.split(",").firstOrNull { it.isNotBlank() }
                ).joinToString("  •  ")
                binding.tvMeta.text = meta
                binding.tvOverview.text = s.overview
            }

            allEpisodes = repo.getEpisodesForSeries(seriesId)
            if (allEpisodes.isEmpty()) {
                binding.tvEmpty.visibility = View.VISIBLE
                return@launch
            }

            episodesBySeason = allEpisodes.groupBy { it.seasonNumber }

            // Calculé ici (avant de construire les onglets) pour le badge « ✓ Vu » par
            // saison — une saison est vue quand TOUS ses épisodes le sont.
            val progressMap = repo.getEpisodesProgress(allEpisodes)
            val seasons = buildSeasons(progressMap)
            seasonAdapter.submitList(seasons)

            // Ouvre la saison de l'épisode à reprendre et positionne la liste dessus :
            // 1. l'épisode en cours (commencé non terminé) ;
            // 2. sinon le premier épisode jamais vu (nouveaux épisodes ajoutés après coup
            //    inclus, grâce à l'ordre saison/épisode de allEpisodes) ;
            // 3. sinon (tout est vu) le tout dernier épisode de la série, qu'il soit
            //    commencé ou non — il n'y a rien de mieux à proposer.
            val inProgress = progressMap.entries
                .filter { !it.value.seen }
                .maxByOrNull { it.value.watchedAt }
                ?.let { e -> allEpisodes.firstOrNull { it.watchKey == e.key } }
            val target = inProgress
                ?: allEpisodes.firstOrNull { progressMap[it.watchKey]?.seen != true }
                ?: allEpisodes.lastOrNull()

            pendingScrollWatchKey = target?.watchKey
            val startSeason = target?.seasonNumber ?: seasons.firstOrNull()?.number
            val previousSeason = seasonAdapter.selectedNumber
            startSeason?.let { seasonAdapter.selectedNumber = it }
            // Le setter de selectedNumber ne redéclenche onSelect (donc submitList + scroll)
            // que si la saison change. Si loadData() est rappelé (ex. relink TMDb) et que la
            // cible reste dans la même saison, on applique explicitement pour ne pas perdre
            // le scroll en attente.
            if (startSeason != null && startSeason == previousSeason) applySeasonEpisodes(startSeason)

            refreshProgress()
        }
    }

    private fun applySeasonEpisodes(seasonNumber: Int) {
        episodeAdapter.submitList(episodesBySeason[seasonNumber].orEmpty()) {
            pendingScrollWatchKey?.let { key -> scrollToEpisode(key) }
            pendingScrollWatchKey = null
        }
        scrollToSeasonTab(seasonNumber)
    }

    /** Amène l'onglet de la saison sélectionnée dans la zone visible de rvSeasons —
     *  sans ça, une saison avancée (ex. saison 5 d'une série qui en a beaucoup) reste
     *  hors champ : l'épisode en cours a bien le focus dans la liste mais l'onglet
     *  correspondant en haut n'est pas visible. */
    private fun scrollToSeasonTab(seasonNumber: Int) {
        val index = seasonAdapter.currentList.indexOfFirst { it.number == seasonNumber }
        if (index < 0) return
        binding.rvSeasons.post {
            (binding.rvSeasons.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(index, 0)
        }
    }

    /** Scrolle rvEpisodes sur l'épisode ciblé (et lui donne le focus pour la télécommande TV). */
    private fun scrollToEpisode(watchKey: Long) {
        val index = episodeAdapter.currentList.indexOfFirst { it.watchKey == watchKey }
        if (index < 0) return
        val rv = binding.rvEpisodes
        rv.post {
            (rv.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(index, 0)
            rv.post {
                rv.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
            }
        }
    }

    /** Confirmation avant de supprimer un épisode déjà téléchargé (mode avion). */
    private fun confirmDeleteDownload(ep: EpisodeEntity) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Supprimer le téléchargement ?")
            .setMessage(ep.episodeTitle)
            .setPositiveButton("Supprimer") { _, _ ->
                lifecycleScope.launch { (application as IptvApplication).downloadRepository.delete(ep.fileKey) }
            }
            .setNegativeButton("Annuler", null)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog)
    }

    private fun refreshProgress() {
        val repo = (application as IptvApplication).mediaRepository
        lifecycleScope.launch {
            val progressMap = repo.getEpisodesProgress(allEpisodes)
            episodeAdapter.setProgress(progressMap)
            // Recalcule le badge « ✓ Vu » par saison (ex. dernier épisode d'une saison
            // terminé pendant la lecture qu'on vient de quitter) sans perturber la
            // sélection en cours — submitList() direct, pas le setter selectedNumber.
            seasonAdapter.submitList(buildSeasons(progressMap))
        }
    }

    /** Onglets de saisons, avec le badge « ✓ Vu » quand tous les épisodes de la
     * saison sont vus (`EpisodeProgress.seen`, cf. `repo.getEpisodesProgress`). */
    private fun buildSeasons(progressMap: Map<Long, EpisodeProgress>) =
        episodesBySeason.keys.sorted().map { num ->
            val episodes = episodesBySeason[num].orEmpty()
            val name = episodes.firstOrNull()?.seasonName?.takeIf { it.isNotBlank() } ?: "Saison $num"
            val allSeen = episodes.isNotEmpty() && episodes.all { progressMap[it.watchKey]?.seen == true }
            SeasonTabAdapter.Season(num, name, allSeen)
        }

    private fun playEpisode(ep: EpisodeEntity, resume: Boolean = true) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_MOVIE_ID, ep.watchKey)
            putExtra(PlayerActivity.EXTRA_STREAM_URL, ep.streamUrl)
            putExtra(PlayerActivity.EXTRA_TITLE, "$seriesTitle — ${ep.episodeTitle}")
            putExtra(PlayerActivity.EXTRA_RESUME, resume)
            putExtra(PlayerActivity.EXTRA_SERIES_ID, seriesId)
            putExtra(PlayerActivity.EXTRA_SERIES_TITLE, seriesTitle)
            putExtra(PlayerActivity.EXTRA_EPISODE_TITLE, ep.episodeTitle)
            putExtra(PlayerActivity.EXTRA_EPISODE_NUMBER, ep.episodeNumber)
            putExtra(PlayerActivity.EXTRA_SEASON_NUMBER, ep.seasonNumber)
            putExtra(PlayerActivity.EXTRA_FILE_KEY, ep.fileKey)
        })
    }

    private fun showTmdbSearchDialog() {
        val repo = (application as IptvApplication).mediaRepository
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_tmdb_search, null)
        val etQuery   = dialogView.findViewById<EditText>(R.id.et_tmdb_query)
        val btnClear  = dialogView.findViewById<ImageView>(R.id.btn_clear_tmdb)
        val btnSearch = dialogView.findViewById<Button>(R.id.btn_tmdb_search)
        val progress  = dialogView.findViewById<ProgressBar>(R.id.progress_tmdb)
        val rv        = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_tmdb_results)

        etQuery.setText(seriesTitle)
        etQuery.selectAll()

        btnClear.visibility = if (etQuery.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        btnClear.setOnClickListener { etQuery.setText(""); etQuery.requestFocus() }
        etQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                btnClear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
        })

        val adapter = TmdbResultAdapter { result ->
            tmdbDialog?.dismiss()
            val app = application as IptvApplication
            lifecycleScope.launch {
                app.mediaRepository.relinkSeriesToTmdb(
                    seriesId, result,
                    app.sessionManager.getUsername(),
                    app.sessionManager.bearer()
                )
                loadData()
                Toast.makeText(this@SeriesDetailActivity,
                    getString(R.string.tmdb_relink_success), Toast.LENGTH_SHORT).show()
            }
        }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.dialog_search_tmdb)
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        tmdbDialog = dialog

        var isSearching = false
        val doSearch = {
            val query = etQuery.text.toString().trim()
            if (query.isNotBlank() && !isSearching) {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(etQuery.windowToken, 0)
                isSearching = true
                progress.visibility = View.VISIBLE
                btnSearch.isEnabled = false
                lifecycleScope.launch {
                    val results = repo.searchTmdb(query)
                    adapter.submitList(results)
                    isSearching = false
                    progress.visibility = View.GONE
                    btnSearch.isEnabled = true
                }
            }
        }
        btnSearch.setOnClickListener { doSearch() }
        etQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false
        }

        dialog.show()
        doSearch()
    }

    inner class TmdbResultAdapter(
        private val onPick: (TmdbMultiResult) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<TmdbResultAdapter.VH>() {

        private var items = listOf<TmdbMultiResult>()

        fun submitList(list: List<TmdbMultiResult>) { items = list; notifyDataSetChanged() }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val b = ItemTmdbResultBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
        override fun getItemCount() = items.size

        inner class VH(private val b: ItemTmdbResultBinding) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(b.root) {
            fun bind(result: TmdbMultiResult) {
                b.tvTmdbTitle.text = result.displayTitle
                b.tvTmdbYear.text = result.displayYear
                b.tvTmdbType.text = getString(
                    if (result.isMovie) R.string.tmdb_type_movie else R.string.tmdb_type_tv
                )
                b.ivTmdbPoster.load(result.posterUrl) {
                    crossfade(true)
                    placeholder(R.drawable.ic_movie_placeholder)
                }
                b.root.setOnClickListener { onPick(result) }
            }
        }
    }

    companion object {
        const val EXTRA_SERIES_ID = "series_id"
        const val EXTRA_SERIES_TITLE = "series_title"
        const val EXTRA_POSTER_URL = "poster_url"
    }
}
