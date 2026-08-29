package com.nicotv.iptv2.ui.main

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Outline
import android.os.Bundle
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import coil.load
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : com.nicotv.iptv2.ui.common.BaseActivity() {

    private lateinit var binding: ActivityMainBinding

    // Dernières listes de films/séries récents connues — servent uniquement au
    // fond plein écran aléatoire (maybeSetHomeBg). Les cartes Films/Séries,
    // elles, n'en dépendent plus (images collage statiques, cf. activity_main.xml).
    private var movieHubUrls: List<String> = emptyList()
    private var seriesHubUrls: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvVersion.text = "v${com.nicotv.iptv2.BuildConfig.VERSION_NAME}"

        setupNavigation()
        sizeHubCards()
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

    /** Taille des 3 cartes (Chaînes/Films/Séries) calculée à partir de la
     * largeur d'écran réelle (29/08/2026, demande explicite "réduire pour ne
     * pas dépasser de l'écran" — les 280dp fixes en XML débordaient sur les
     * téléphones en `sensorLandscape`, où la largeur dispo est bien moindre
     * que sur une tablette/TV). Même principe que `MoviesActivity.
     * computeSpanCount()` : `screenWidthDp`, pas de mesure de vue. Ratio 3:2
     * conservé (`cardWidthDp / 1.5f`), identique à celui des 3 images collage
     * — aucun rognage `centerCrop` nécessaire quand la taille calculée tombe
     * pile sur ce ratio. `layout_gravity="center"` sur la rangée (XML,
     * inchangé) centre le résultat quelle que soit la taille retenue. */
    private fun sizeHubCards() {
        val screenWidthDp = resources.configuration.screenWidthDp
        // 18dp de marge entre les 3 cartes (×2) + 12dp de padding de chaque
        // côté de la rangée (×2) — cf. les marges/paddings fixes d'activity_main.xml.
        val reservedDp = 18 * 2 + 12 * 2
        val maxCardWidthDp = 280 // taille d'origine, confortable sur TV/tablette
        val minCardWidthDp = 120
        val cardWidthDp = ((screenWidthDp - reservedDp) / 3).coerceIn(minCardWidthDp, maxCardWidthDp)
        val cardHeightDp = (cardWidthDp / 1.5f).toInt()
        val widthPx = (cardWidthDp * resources.displayMetrics.density).toInt()
        val heightPx = (cardHeightDp * resources.displayMetrics.density).toInt()
        listOf(binding.cardLive, binding.cardFilms, binding.cardSeries).forEach { card ->
            card.layoutParams = card.layoutParams.apply { width = widthPx; height = heightPx }
        }
    }

    private fun applyHubCardClipping() {
        listOf(binding.cardLive, binding.cardFilms, binding.cardSeries).forEach { view ->
            view.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp())
                }
            }
            view.clipToOutline = true
        }
    }

    private fun dp(value: Float = HUB_CARD_RADIUS_DP): Float = value * resources.displayMetrics.density

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
                    movieHubUrls = movieUrls
                    maybeSetHomeBg(movieUrls + seriesHubUrls)
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
        private const val HUB_CARD_RADIUS_DP = 16f
        // Fond plein écran : stable pour tout le process (survit à une
        // réouverture de l'accueil), remis à null quand le catalogue change
        // (nouveau profil chargé, cf. SetupActivity.loadProfile) pour ne pas
        // garder un fond de l'ancienne source.
        @Volatile private var cachedHomeBgUrl: String? = null

        fun resetHomeBg() { cachedHomeBgUrl = null }
    }
}
