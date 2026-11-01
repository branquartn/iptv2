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
import androidx.lifecycle.repeatOnLifecycle
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
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
        // Fin d'un chargement (changement de playlist, rechargement auto) : le contenu a changé — les films/séries du
        // serveur arrivent EN DERNIER — donc on réévalue les tuiles Films/Séries et l'expiration.
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                (application as IptvApplication).catalogLoader.state.collect { st ->
                    if (st == null || st.percent >= 100 || st.error != null) { applyContentFlags(); updateExpiry() }
                }
            }
        }

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
        lifecycleScope.launch {
            val app = application as IptvApplication
            // Codes Xtream de l'admin : relus au plus toutes les 5 min. Session expirée → login ;
            // code retiré (profil actif disparu) → écran de choix.
            if (app.remoteSources.sync(force = false) == com.nicotv.iptv2.account.RemoteSources.Status.UNAUTHORIZED) {
                startActivity(android.content.Intent(this@MainActivity, com.nicotv.iptv2.ui.login.LoginActivity::class.java)); finish(); return@launch
            }
            if (!app.playlistRepository.hasValidActiveProfile()) {
                startActivity(android.content.Intent(this@MainActivity, com.nicotv.iptv2.ui.setup.SetupActivity::class.java)); finish(); return@launch
            }
            applyContentFlags()
            app.catalogLoader.refreshIfNeeded()   // cache périmé (24 h) ou jamais chargé → en arrière-plan avec bandeau %
        }
        applyContentFlags()
        updateExpiry()
        updateSwitchVisibility()
    }

    /** Icône « changer de playlist » : visible seulement s'il y a au moins 2 playlists attribuées. */
    private fun updateSwitchVisibility() {
        lifecycleScope.launch {
            val n = (application as IptvApplication).playlistRepository.getProfiles().first().size
            binding.btnSwitch.visibility = if (n > 1) View.VISIBLE else View.GONE
        }
    }

    /** Bascule en direct sur une autre playlist attribuée par l'admin : chargement en arrière-plan, bandeau %. */
    private fun showSwitchPlaylistDialog() {
        lifecycleScope.launch {
            val app = application as IptvApplication
            val profiles = app.playlistRepository.getProfiles().first()
            if (profiles.isEmpty()) return@launch
            val activeId = app.sourcePrefs.getActiveProfileId()
            val names = profiles.map { (if (it.id == activeId) "✓  " else "     ") + it.name }.toTypedArray()
            val dialog = AlertDialog.Builder(this@MainActivity)
                .setTitle("Changer de playlist")
                .setItems(names) { d, which ->
                    val p = profiles[which]
                    d.dismiss()
                    when {
                        p.id == activeId -> Unit
                        app.catalogLoader.isRunning ->
                            android.widget.Toast.makeText(this@MainActivity, "Un chargement est déjà en cours…", android.widget.Toast.LENGTH_SHORT).show()
                        else -> {
                            // Cache récent : bascule instantanée, aucun téléchargement. Sinon chargement en fond (bandeau %).
                            val cached = !app.catalogLoader.needsLoad(p.id)
                            app.catalogLoader.activate(p.id, p.name)
                            resetHomeBg()
                            if (cached) android.widget.Toast.makeText(this@MainActivity, "${p.name} : cache à jour", android.widget.Toast.LENGTH_SHORT).show()
                            recreate()   // l'accueil relit le catalogue de la nouvelle playlist
                        }
                    }
                }
                .setNegativeButton("Annuler", null)
                .create()
            dialog.show()
            dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog)
        }
    }

    /** Playlist active + date d'expiration de son abonnement Xtream, en bas à droite de l'accueil :
     *  d'abord la valeur en cache (instantané), puis relue auprès du panel. */
    private fun updateExpiry() {
        lifecycleScope.launch {
            val repo = (application as IptvApplication).playlistRepository
            val profile = repo.getActiveProfile()
            if (profile == null || profile.type != com.nicotv.iptv2.data.SourceType.XTREAM.name) {
                binding.tvExpiry.visibility = View.GONE
                return@launch
            }
            showExpiry(profile.name, repo.getXtreamAccount(profile.id))
            val fresh = runCatching { repo.refreshXtreamAccount(profile) }.getOrNull()
            if (fresh != null) showExpiry(profile.name, fresh)
        }
    }

    private fun showExpiry(name: String, info: com.nicotv.iptv2.data.xtream.XtAccountInfo?) {
        if (info == null) { binding.tvExpiry.visibility = View.GONE; return }
        val date = info.expDateMs?.let { com.nicotv.iptv2.data.xtream.formatDate(it) }
        val status = when {
            info.isExpired -> getString(R.string.xtream_expired_short)
            date == null -> getString(R.string.xtream_unlimited)
            else -> getString(R.string.xtream_expires_short, date)
        }
        binding.tvExpiry.text = "$name  ·  $status"
        binding.tvExpiry.setTextColor(androidx.core.content.ContextCompat.getColor(this, when {
            info.isExpired -> R.color.error
            else -> R.color.text_secondary   // gris ; rouge seulement si expiré
        }))
        binding.tvExpiry.visibility = View.VISIBLE
    }

    /** Les cartes Films et Séries restent TOUJOURS visibles : même quand l'admin coupe films/séries d'un code
     *  Xtream, les films et séries du serveur de l'utilisateur doivent rester accessibles. */
    private fun applyContentFlags() {
        binding.cardFilms.visibility = View.VISIBLE
        binding.cardSeries.visibility = View.VISIBLE
        binding.cardLive.visibility = View.VISIBLE
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
        binding.btnSwitch.setOnClickListener { showSwitchPlaylistDialog() }
        binding.btnChat.setOnClickListener { startActivity(Intent(this, com.nicotv.iptv2.ui.chat.ChatActivity::class.java)) }
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
            binding.btnSwitch to binding.btnSwitchRing,
            binding.btnChat to binding.btnChatRing,
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

        // ⚠️ Le PRÉCHAUFFAGE des StateFlow catalogue a été RETIRÉ le
        // 30/08/2026 (signalé "ça charge encore tous puis la catégorie").
        // Il référençait getMovies()/getSeries()/getChannels() dès l'accueil
        // pour payer d'avance le mapping complet du catalogue — utile tant que
        // les écrans Films/Séries/Chaînes consommaient ces Flow, INUTILE
        // depuis leur passage à la pagination (cf. CLAUDE.md) : ces écrans
        // interrogent maintenant la base page par page et ne touchent plus
        // jamais ces Flow. Le préchauffage ne faisait donc plus que brûler du
        // CPU (mapping de ~47 000 films + séries + chaînes, GC en rafale)
        // pendant que l'utilisateur naviguait — exactement le "ça charge
        // tout" perçu. Favoris/Reprise/Recherche, seuls consommateurs
        // restants, déclenchent l'initialisation à leur ouverture.
        // ⚠️ Ne pas le réintroduire sans vérifier qui consomme réellement ces
        // Flow — le remède était devenu la maladie.

        // Fond aléatoire : requêtes BORNÉES (12 lignes, tri/filtre en SQL) au
        // lieu de l'ancien mapping de tout le catalogue en Kotlin, cf.
        // PlaylistRepository.getRecentMoviesWithArt.
        val contentLanguage = app.contentLanguagePrefs.getLanguage()
        lifecycleScope.launch {
            app.playlistRepository.getRecentMoviesWithArt(contentLanguage)
                .map { movies -> movies.map { it.backdropUrl.ifBlank { it.posterUrl } }.distinct() }
                .flowOn(Dispatchers.Default)
                .distinctUntilChanged()
                .collect { movieUrls ->
                    movieHubUrls = movieUrls
                    maybeSetHomeBg(movieUrls + seriesHubUrls)
                }
        }
        lifecycleScope.launch {
            app.playlistRepository.getRecentSeriesWithArt()
                .map { series -> series.map { it.backdropUrl.ifBlank { it.posterUrl } }.distinct() }
                .flowOn(Dispatchers.Default)
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
