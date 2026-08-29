package com.nicotv.iptv2.ui.main

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Outline
import android.os.Bundle
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.transform.RoundedCornersTransformation
import com.nicotv.iptv2.IptvApplication
import com.nicotv.iptv2.R
import com.nicotv.iptv2.databinding.ActivityMainBinding
import com.nicotv.iptv2.ui.favorites.FavoritesActivity
import com.nicotv.iptv2.ui.live.LiveActivity
import com.nicotv.iptv2.ui.movies.MoviesActivity
import com.nicotv.iptv2.ui.resume.ResumeActivity
import com.nicotv.iptv2.ui.search.SearchActivity
import com.nicotv.iptv2.ui.series.SeriesActivity
import com.nicotv.iptv2.update.checkForAppUpdate
import com.nicotv.iptv2.util.extractLeadingLanguageCode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : com.nicotv.iptv2.ui.common.BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private var movieRotationJob: Job? = null
    private var seriesRotationJob: Job? = null

    // Listes courantes des fonds de vignettes — mises à jour par Room, lues par
    // les jobs de rotation à chaque tick (mêmes noms/principe que NicoTV
    // MainActivity, cf. son CLAUDE.md).
    private var movieHubUrls: List<String> = emptyList()
    private var seriesHubUrls: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvVersion.text = "v${com.nicotv.iptv2.BuildConfig.VERSION_NAME}"

        setupNavigation()
        setupFocusAnimations()
        applyHubCardClipping()
        binding.cardLive.requestFocus()
        observeData()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { showQuitDialog() }
        })
    }

    override fun onStart() {
        super.onStart()
        checkForAppUpdate()
        // Rafraîchissement silencieux du catalogue si le profil actif n'a pas
        // été rechargé depuis 24h (cf. PlaylistRepository.refreshActiveProfileIfStale) —
        // best-effort, ne bloque jamais l'accueil.
        lifecycleScope.launch { (application as IptvApplication).playlistRepository.refreshActiveProfileIfStale() }
    }

    /** Fond plein écran de l'accueil (jaquette/backdrop tirée au sort dans le
     * catalogue chargé) — stable pour toute la session (pas de changement à
     * chaque retour à l'accueil), cf. companion.cachedHomeBgUrl. Même principe
     * que NicoTV MainActivity.maybeSetHomeBg. */
    private fun maybeSetHomeBg(urls: List<String>) {
        if (cachedHomeBgUrl == null) {
            if (urls.isEmpty()) return
            cachedHomeBgUrl = urls.random()
        }
        binding.ivHomeBg.load(cachedHomeBgUrl) { crossfade(true) }
    }

    private fun loadRotatingHubImage(imageView: ImageView?, urls: List<String>, offset: Int) {
        if (imageView == null || urls.isEmpty()) return
        val slot = (System.currentTimeMillis() / HUB_ROTATION_INTERVAL_MS).toInt()
        val url = urls[(slot + offset).floorMod(urls.size)]
        // Évite de relancer un crossfade si l'image affichée est déjà la bonne.
        if (imageView.tag == url) return
        imageView.tag = url
        imageView.load(url) {
            crossfade(true)
            transformations(RoundedCornersTransformation(dp()))
        }
    }

    private fun applyHubCardClipping() {
        listOf(binding.cardLive, binding.cardFilms, binding.cardSeries, binding.ivFilmsBg, binding.ivSeriesBg).forEach { view ->
            view.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp())
                }
            }
            view.clipToOutline = true
        }
    }

    private fun dp(value: Float = HUB_CARD_RADIUS_DP): Float = value * resources.displayMetrics.density

    private fun Int.floorMod(other: Int): Int = ((this % other) + other) % other

    private var quitDialog: AlertDialog? = null

    private fun showQuitDialog() {
        if (quitDialog?.isShowing == true) return
        val view = layoutInflater.inflate(R.layout.dialog_quit, null)
        val dialog = AlertDialog.Builder(this).setView(view).create()
        quitDialog = dialog
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        view.findViewById<Button>(R.id.btn_quit_cancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<Button>(R.id.btn_quit_confirm).setOnClickListener { dialog.dismiss(); finishAffinity() }
        dialog.show()
        view.findViewById<Button>(R.id.btn_quit_cancel).requestFocus()
    }

    private fun setupNavigation() {
        binding.cardLive.setOnClickListener { startActivity(Intent(this, LiveActivity::class.java)) }
        binding.cardFilms.setOnClickListener { startActivity(Intent(this, MoviesActivity::class.java)) }
        binding.cardSeries.setOnClickListener { startActivity(Intent(this, SeriesActivity::class.java)) }
        binding.btnResume.setOnClickListener { startActivity(Intent(this, ResumeActivity::class.java)) }
        binding.btnSearch.setOnClickListener { startActivity(Intent(this, SearchActivity::class.java)) }
        binding.btnFavorites.setOnClickListener { startActivity(Intent(this, FavoritesActivity::class.java)) }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, com.nicotv.iptv2.ui.settings.SettingsActivity::class.java))
        }
    }

    private fun setupFocusAnimations() {
        listOf(
            binding.cardLive to binding.focusRingLive,
            binding.cardFilms to binding.focusRingFilms,
            binding.cardSeries to binding.focusRingSeries
        ).forEach { (card, ring) ->
            card.setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.04f else 1f).scaleY(if (hasFocus) 1.04f else 1f).setDuration(150).start()
                ring.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
                if (hasFocus) ring.startAnim() else ring.stopAnim()
            }
        }

        listOf(
            binding.btnResume to binding.btnResumeRing,
            binding.btnSearch to binding.btnSearchRing,
            binding.btnFavorites to binding.btnFavoritesRing,
            binding.btnSettings to binding.btnSettingsRing
        ).forEach { (view, ring) ->
            view.setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.25f else 1f).scaleY(if (hasFocus) 1.25f else 1f).setDuration(150).start()
                v.z = if (hasFocus) 10f else 0f
                ring.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
                if (hasFocus) ring.startAnim() else ring.stopAnim()
            }
        }
    }

    private fun observeData() {
        val app = application as IptvApplication

        // Préchauffe le cache StateFlow du repository (cf. PlaylistRepository.
        // moviesFlow/seriesFlow/channelsFlow) pendant que l'utilisateur est
        // encore sur l'accueil — juste référencer getMovies()/getSeries()/
        // getChannels() suffit à déclencher le `by lazy` + le démarrage du
        // partage `Eagerly` (tourne sur appScope, indépendant de cet écran).
        // Sans ça, le premier visite de Films/Séries/Chaînes dans la session
        // paie quand même le coût initial ; avec ça, il est déjà payé la
        // plupart du temps le temps que l'utilisateur clique depuis l'accueil.
        app.playlistRepository.getMovies()
        app.playlistRepository.getSeries()
        app.playlistRepository.getChannels()

        lifecycleScope.launch {
            app.database.movieDao().getAllMovies()
                .map { movies ->
                    // Films FR uniquement, les plus récents (demande explicite
                    // 28/08/2026) — cf. util.LanguageCode.
                    //
                    // ⚠️ Bug corrigé 29/08/2026 : `== "FR"` strict excluait tout
                    // film SANS préfixe de langue détecté dans sa catégorie — sur
                    // un panel où la plupart des catégories françaises n'ont
                    // justement aucun préfixe (cf. section Réglages/Langue du
                    // contenu du CLAUDE.md, même piège déjà rencontré et corrigé
                    // là-bas), ça ne laissait qu'une poignée de films, parfois un
                    // seul -> le fond "tournait" toujours sur la même image.
                    // Même règle que MoviesViewModel.applyLanguageFilter : aucun
                    // préfixe détecté = accepté, exclut seulement un préfixe
                    // explicite d'une AUTRE langue.
                    movies.filter {
                        val c = extractLeadingLanguageCode(it.category)
                        (c == null || c == "FR") &&
                            (it.backdropUrl.isNotBlank() || it.posterUrl.isNotBlank())
                    }
                        .sortedByDescending { it.updatedAt }
                        .take(12)
                        .map { it.backdropUrl.ifBlank { it.posterUrl } }
                        .distinct()
                }
                .distinctUntilChanged()
                .collect { movieUrls ->
                    // Room émet en rafale pendant un chargement de profil : on ne
                    // redémarre pas le job (sinon les images défilent trop vite),
                    // juste la liste que la rotation lit à son prochain tick.
                    movieHubUrls = movieUrls
                    maybeSetHomeBg(movieUrls + seriesHubUrls)
                    if (movieRotationJob == null && movieUrls.isNotEmpty()) {
                        movieRotationJob = lifecycleScope.launch {
                            while (isActive) {
                                loadRotatingHubImage(binding.ivFilmsBg, movieHubUrls, HUB_MOVIE_OFFSET)
                                delay(HUB_ROTATION_INTERVAL_MS)
                            }
                        }
                    }
                }
        }
        lifecycleScope.launch {
            app.database.seriesDao().getAllSeries()
                .map { series ->
                    series.filter { it.backdropUrl.isNotBlank() || it.posterUrl.isNotBlank() }
                        .sortedByDescending { it.updatedAt }
                        .take(12)
                        .map { it.backdropUrl.ifBlank { it.posterUrl } }
                        .distinct()
                }
                .distinctUntilChanged()
                .collect { seriesUrls ->
                    seriesHubUrls = seriesUrls
                    maybeSetHomeBg(movieHubUrls + seriesUrls)
                    if (seriesRotationJob == null && seriesUrls.isNotEmpty()) {
                        seriesRotationJob = lifecycleScope.launch {
                            while (isActive) {
                                loadRotatingHubImage(binding.ivSeriesBg, seriesHubUrls, HUB_SERIES_OFFSET)
                                delay(HUB_ROTATION_INTERVAL_MS)
                            }
                        }
                    }
                }
        }

        lifecycleScope.launch {
            app.playlistRepository.getUnifiedHistory().collect { history ->
                binding.btnResume.visibility = if (history.isNotEmpty()) View.VISIBLE else View.GONE
                binding.tvResumeCount.text = history.size.toString()
            }
        }

        lifecycleScope.launch {
            app.playlistRepository.getFavoritesCount().collect { count ->
                if (count > 0) {
                    binding.tvFavoritesCount.visibility = View.VISIBLE
                    binding.tvFavoritesCount.text = count.toString()
                } else {
                    binding.tvFavoritesCount.visibility = View.GONE
                }
            }
        }
    }

    companion object {
        private const val HUB_ROTATION_INTERVAL_MS = 45_000L
        private const val HUB_CARD_RADIUS_DP = 16f
        private const val HUB_MOVIE_OFFSET = 0
        private const val HUB_SERIES_OFFSET = 5
        // Fond plein écran : stable pour tout le process (survit à une
        // réouverture de l'accueil), remis à null quand le catalogue change
        // (nouveau profil chargé, cf. SetupActivity.loadProfile) pour ne pas
        // garder un fond de l'ancienne source.
        @Volatile private var cachedHomeBgUrl: String? = null

        fun resetHomeBg() { cachedHomeBgUrl = null }
    }
}
