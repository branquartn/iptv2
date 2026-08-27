package com.nicotv.iptv.ui.main

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import coil.load
import coil.transform.RoundedCornersTransformation
import androidx.lifecycle.lifecycleScope
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.nicotv.iptv.IptvApplication
import com.nicotv.iptv.R
import com.nicotv.iptv.data.database.entity.DownloadEntity
import com.nicotv.iptv.data.network.PresenceItem
import com.nicotv.iptv.databinding.ActivityMainBinding
import com.nicotv.iptv.ui.downloads.DownloadsActivity
import com.nicotv.iptv.ui.favorites.FavoritesActivity
import com.nicotv.iptv.ui.login.LoginActivity
import com.nicotv.iptv.ui.movies.MoviesActivity
import com.nicotv.iptv.ui.resume.ResumeActivity
import com.nicotv.iptv.ui.search.SearchActivity
import com.nicotv.iptv.ui.series.SeriesActivity
import com.nicotv.iptv.ui.users.UsersActivity
import com.nicotv.iptv.update.checkForAppUpdate
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : com.nicotv.iptv.ui.common.BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private var movieRotationJob: Job? = null
    private var seriesRotationJob: Job? = null

    // « En cours sur <appareil> » : bandeau construit en code (pas en XML — évite de
    // dupliquer le changement dans layout/ ET layout-sw600dp/) affichant la session
    // d'un AUTRE appareil du même compte en train de regarder un film, avec un
    // bouton pause/reprendre à distance. Cf. api/iptv.php presence_list/remote_pause/
    // remote_resume (scopés au compte, pendant mobile du panel admin.nicotv.ovh).
    private var otherDevicePollJob: Job? = null
    private var otherDeviceBanner: LinearLayout? = null
    private var otherDeviceLabel: TextView? = null
    private var otherDeviceBtn: Button? = null
    private var currentOtherDevice: PresenceItem? = null

    // Listes courantes des fonds de vignettes : mises à jour par Room, lues par
    // les jobs de rotation à chaque tick (les jobs ne sont jamais redémarrés).
    private var movieHubUrls: List<String> = emptyList()
    private var seriesHubUrls: List<String> = emptyList()

    // Pastille « N nouveautés » : comptes courants (films/séries), mis à jour par
    // les 2 observers séparés (cf. observeData()), lus par updateNewBadge() et le
    // clic sur la pastille pour choisir la destination (comme newBadgeView() PWA).
    private var newFilmsCount = 0
    private var newSeriesCount = 0

    /** Charge le fond plein écran de l'accueil (comme le hero de la PWA) : un
     * film/série tiré au sort, stable pour toute la session (pas de changement à
     * chaque retour à l'accueil) — cf. companion.cachedHomeBgUrl, remis à null au
     * logout pour ne pas fuiter vers le profil suivant. */
    private fun maybeSetHomeBg(urls: List<String>) {
        if (cachedHomeBgUrl == null) {
            if (urls.isEmpty()) return
            cachedHomeBgUrl = urls.random()
        }
        binding.ivHomeBg.load(cachedHomeBgUrl) { crossfade(true) }
    }

    // Présence admin.nicotv.ovh (« qui regarde quoi ») : écran courant hors lecture.
    override fun onResume() {
        super.onResume()
        (application as IptvApplication).reportScreen("Accueil")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val username = (application as IptvApplication).sessionManager.getUsername()
        binding.tvUsername.text = username.ifBlank { "admin" }
        binding.tvVersion?.text = "v${com.nicotv.iptv.BuildConfig.VERSION_NAME}"

        setupNavigation()
        setupFocusAnimations()

        // Gestion des comptes : réservée à l'administrateur
        if ((application as IptvApplication).sessionManager.isAdmin()) {
            binding.btnUsers.visibility = View.VISIBLE
            binding.btnUsers.setOnClickListener {
                startActivity(Intent(this, UsersActivity::class.java))
            }
        }

        binding.btnLogout.setOnClickListener { logout() }

        applyHubCardClipping()
        binding.cardFilms.requestFocus()
        observeData()

        // Retour sur l'accueil → confirmation de sortie (au lieu de quitter sec).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { showQuitDialog() }
        })
    }

    private var quitDialog: AlertDialog? = null

    private fun showQuitDialog() {
        if (quitDialog?.isShowing == true) return
        val view = layoutInflater.inflate(R.layout.dialog_quit, null)
        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()
        quitDialog = dialog
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<Button>(R.id.btn_quit_cancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<Button>(R.id.btn_quit_confirm).setOnClickListener {
            dialog.dismiss()
            finishAffinity()   // ferme l'app → retour au launcher
        }
        dialog.show()
        view.findViewById<Button>(R.id.btn_quit_cancel).requestFocus()
    }

    private fun setupNavigation() {
        binding.cardFilms.setOnClickListener {
            startActivity(Intent(this, MoviesActivity::class.java))
        }

        binding.btnResume?.setOnClickListener {
            startActivity(Intent(this, ResumeActivity::class.java))
        }

        @OptIn(UnstableApi::class)
        val seriesClickListener = View.OnClickListener {
            startActivity(Intent(this, SeriesActivity::class.java))
        }
        binding.cardSeries.setOnClickListener(seriesClickListener)

        binding.btnSearch.setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }

        binding.btnFavorites.setOnClickListener {
            startActivity(Intent(this, FavoritesActivity::class.java))
        }

        binding.btnDownloads.setOnClickListener {
            startActivity(Intent(this, DownloadsActivity::class.java))
        }

        // Pastille « N nouveautés » (comme la PWA, newBadgeView()) : ouvre Films
        // filtré si seuls des films sont nouveaux, Séries filtré si seules des
        // séries le sont. Si les deux : Films par défaut (la PWA a un "new-all"
        // unifié films+séries, pas d'équivalent simple côté APK pour l'instant).
        binding.tvNewBadge.setOnClickListener {
            if (newFilmsCount == 0 && newSeriesCount > 0) {
                startActivity(
                    Intent(this, SeriesActivity::class.java)
                        .putExtra(SeriesActivity.EXTRA_NEW_ONLY, true)
                )
            } else {
                startActivity(
                    Intent(this, MoviesActivity::class.java)
                        .putExtra(MoviesActivity.EXTRA_NEW_ONLY, true)
                )
            }
        }
    }

    private fun setupFocusAnimations() {
        // Animation de focus sur les grandes cartes : léger zoom + anneau blanc
        // tournant (RotatingBorderView, comme les affiches du mur Films) à la place
        // de l'ancienne bordure blanche statique.
        listOf(
            binding.cardFilms to binding.focusRingFilms,
            binding.cardSeries to binding.focusRingSeries
        ).forEach { (card, ring) ->
            card.setOnFocusChangeListener { v, hasFocus ->
                v.animate()
                    .scaleX(if (hasFocus) 1.04f else 1f)
                    .scaleY(if (hasFocus) 1.04f else 1f)
                    .setDuration(150).start()
                ring.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
                if (hasFocus) ring.startAnim() else ring.stopAnim()
            }
        }

        // Boutons icône de la barre du haut (recherche, reprendre, favoris,
        // téléchargements, comptes, déconnexion) : zoom + anneau blanc tournant
        // (RotatingBorderView), même pattern que la fiche détail/le bouton retour.
        // btn_resume/btn_resume_ring absents du layout-sw600dp (tablette/TV) → nullable.
        val resumeRingPair = binding.btnResume?.let { v -> binding.btnResumeRing?.let { r -> v to r } }
        listOfNotNull(
            binding.btnSearch to binding.btnSearchRing,
            binding.btnRemote to binding.btnRemoteRing,
            resumeRingPair,
            binding.btnFavorites to binding.btnFavoritesRing,
            binding.btnDownloads to binding.btnDownloadsRing,
            binding.btnUsers to binding.btnUsersRing,
            binding.btnLogout to binding.btnLogoutRing
        ).forEach { (view, ring) ->
            view.setOnFocusChangeListener { v, hasFocus ->
                v.animate()
                    .scaleX(if (hasFocus) 1.25f else 1f)
                    .scaleY(if (hasFocus) 1.25f else 1f)
                    .setDuration(150).start()
                v.z = if (hasFocus) 10f else 0f
                ring.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
                if (hasFocus) ring.startAnim() else ring.stopAnim()
            }
        }

        // Pastille « N nouveautés » : pilule, pas un bouton icône rond → zoom seul,
        // pas d'anneau (forme incompatible avec RotatingBorderView circulaire).
        binding.tvNewBadge.setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) 1.25f else 1f)
                .scaleY(if (hasFocus) 1.25f else 1f)
                .setDuration(150).start()
            v.z = if (hasFocus) 10f else 0f
        }
    }

    override fun onStart() {
        super.onStart()
        // Ouvre le bus temps réel (couvre le cas « juste après le login » où le passage au
        // premier plan a déjà été signalé au niveau application).
        (application as IptvApplication).connectRealtime()
        checkForAppUpdate()
        startOtherDevicePresencePoll()
    }

    override fun onStop() {
        super.onStop()
        otherDevicePollJob?.cancel(); otherDevicePollJob = null
    }

    private fun startOtherDevicePresencePoll() {
        if (otherDevicePollJob != null) return
        val app = application as IptvApplication
        binding.btnRemote.setOnClickListener {
            startActivity(android.content.Intent(this, com.nicotv.iptv.ui.remote.RemoteControlActivity::class.java))
        }
        otherDevicePollJob = lifecycleScope.launch {
            while (isActive) {
                val bearer = app.sessionManager.bearer()
                val all = runCatching { app.mediaRepository.otherDevicesPresence(bearer) }.getOrDefault(emptyList())
                binding.btnRemote.visibility = if (all.isNotEmpty()) View.VISIBLE else View.GONE
                updateOtherDeviceBanner(all.firstOrNull { it.kind == "watching" })
                delay(15_000)
            }
        }
    }

    private fun updateOtherDeviceBanner(item: PresenceItem?) {
        currentOtherDevice = item
        if (item == null) {
            otherDeviceBanner?.visibility = View.GONE
            return
        }
        val banner = otherDeviceBanner ?: buildOtherDeviceBanner().also { otherDeviceBanner = it }
        banner.visibility = View.VISIBLE
        val deviceLabel = item.device.ifBlank { "un autre appareil" }
        otherDeviceLabel?.text = "▶ En cours sur $deviceLabel\n${item.title}"
        otherDeviceBtn?.text = if (item.playing) "Pause à distance" else "Reprendre à distance"
    }

    /** Construit le bandeau une seule fois (lazy), ajouté directement au FrameLayout
     *  racine — pas de vue XML dédiée à maintenir dans les 2 layouts (mobile/sw600dp). */
    private fun buildOtherDeviceBanner(): LinearLayout {
        val density = resources.displayMetrics.density
        fun dpToPx(v: Int) = (v * density).toInt()
        val bg = GradientDrawable().apply {
            setColor(Color.parseColor("#E6141414"))
            cornerRadius = dpToPx(14).toFloat()
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
            visibility = View.GONE
        }
        val label = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btn = Button(this).apply {
            textSize = 12f
            isAllCaps = false
            setOnClickListener { toggleOtherDevicePlayback() }
        }
        row.addView(label)
        row.addView(btn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dpToPx(12)
        })
        otherDeviceLabel = label
        otherDeviceBtn = btn
        binding.root.addView(row, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = dpToPx(24) })
        return row
    }

    private fun toggleOtherDevicePlayback() {
        val item = currentOtherDevice ?: return
        val app = application as IptvApplication
        otherDeviceBtn?.isEnabled = false
        lifecycleScope.launch {
            val bearer = app.sessionManager.bearer()
            if (item.playing) app.mediaRepository.remotePauseOther(item.deviceId, bearer)
            else app.mediaRepository.remoteResumeOther(item.deviceId, bearer)
            delay(500)   // laisse le temps au heartbeat de l'autre appareil de confirmer
            val refreshed = runCatching { app.mediaRepository.otherDevicesPresence(bearer) }
                .getOrDefault(emptyList())
                .firstOrNull { it.deviceId == item.deviceId }
            updateOtherDeviceBanner(refreshed)
            otherDeviceBtn?.isEnabled = true
        }
    }

    private fun observeData() {
        val app = application as IptvApplication
        val username = app.sessionManager.getUsername()
        
        // Synchronisation initiale du catalogue
        lifecycleScope.launch {
            runCatching { app.mediaRepository.syncCatalog(username, app.sessionManager.bearer()) }
        }

        // Observation des compteurs et de la rotation des backgrounds
        lifecycleScope.launch {
            app.database.movieDao().getAllMovies(username)
                .map { movies ->
                    binding.tvFilmsCount?.text = formatCount(movies.size)
                    movies.filter { it.backdropUrl.isNotBlank() || it.posterUrl.isNotBlank() }
                        .sortedByDescending { it.updatedAt }
                        .take(12)
                        .map { it.backdropUrl.ifBlank { it.posterUrl } }
                        .distinct()
                }
                .distinctUntilChanged()
                .collect { movieUrls ->
                    // Pendant la synchro, Room émet en rafale : on ne redémarre pas le
                    // job (sinon les images défilent), on met juste à jour la liste que
                    // la rotation lira à son prochain tick.
                    movieHubUrls = movieUrls
                    maybeSetHomeBg(movieUrls + seriesHubUrls)
                    if (movieRotationJob == null && movieUrls.isNotEmpty()) {
                        movieRotationJob = launch {
                            while (isActive) {
                                loadRotatingHubImage(binding.ivFilmsBg, movieHubUrls, HUB_MOVIE_OFFSET)
                                delay(HUB_ROTATION_INTERVAL_MS)
                            }
                        }
                    }
                }
        }

        lifecycleScope.launch {
            app.database.seriesDao().getAllSeries(username)
                .map { series ->
                    binding.tvSeriesCount?.text = formatCount(series.size)
                    series.filter { it.backdropUrl.isNotBlank() || it.posterUrl.isNotBlank() }
                        .sortedByDescending { it.updatedAt }
                        .take(12)
                        .map { it.backdropUrl.ifBlank { it.posterUrl } }
                        .distinct()
                }
                .distinctUntilChanged()
                .collect { seriesUrls ->
                    // Même logique que les films : pas de redémarrage du job.
                    seriesHubUrls = seriesUrls
                    maybeSetHomeBg(movieHubUrls + seriesUrls)
                    if (seriesRotationJob == null && seriesUrls.isNotEmpty()) {
                        seriesRotationJob = launch {
                            while (isActive) {
                                loadRotatingHubImage(binding.ivSeriesBg, seriesHubUrls, HUB_SERIES_OFFSET)
                                delay(HUB_ROTATION_INTERVAL_MS)
                            }
                        }
                    }
                }
        }

        // Pastille « N nouveautés » (comme newBadgeLabel() côté PWA) — films et
        // séries comptés séparément, mis à jour indépendamment.
        lifecycleScope.launch {
            app.mediaRepository.getNewMoviesCount(username).collect { count ->
                newFilmsCount = count
                updateNewBadge()
            }
        }
        lifecycleScope.launch {
            app.mediaRepository.getNewSeriesCount(username).collect { count ->
                newSeriesCount = count
                updateNewBadge()
            }
        }

        // Téléchargements (mode avion) : nombre de films téléchargés.
        lifecycleScope.launch {
            app.downloadRepository.getAllFlow().collect { list ->
                val count = list.count { it.type == DownloadEntity.TYPE_MOVIE && it.state == DownloadEntity.STATE_COMPLETED }
                binding.tvDownloadsCount?.visibility = if (count > 0) View.VISIBLE else View.GONE
                binding.tvDownloadsCount?.text = count.toString()
            }
        }

        // Reprendre la lecture (Dernier élément de l'historique)
        lifecycleScope.launch {
            app.mediaRepository.getWatchHistory().collect { history ->
                // L'historique ne contient que des éléments en cours (les terminés en sont retirés)
                // → on les compte tous, dès la 1re seconde et jusqu'à la dernière.
                if (history.isNotEmpty()) {
                    binding.btnResume?.visibility = View.VISIBLE
                    binding.tvResumeCount?.text = history.size.toString()
                } else {
                    binding.btnResume?.visibility = View.GONE
                }
            }
        }

        // Favoris : étoile jaune + badge (films + séries réunis, comme la reprise)
        lifecycleScope.launch {
            app.mediaRepository.getFavoritesCount().collect { count ->
                val hasFavs = count > 0
                val tint = if (hasFavs) {
                    ContextCompat.getColor(this@MainActivity, R.color.favorite_yellow)
                } else {
                    ContextCompat.getColor(this@MainActivity, R.color.text_primary)
                }
                binding.ivFavoriteStar?.imageTintList = android.content.res.ColorStateList.valueOf(tint)
                if (hasFavs) {
                    binding.tvFavoritesCount?.visibility = View.VISIBLE
                    binding.tvFavoritesCount?.text = count.toString()
                } else {
                    binding.tvFavoritesCount?.visibility = View.GONE
                }
            }
        }
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
        listOf(binding.cardFilms, binding.cardSeries, binding.ivFilmsBg, binding.ivSeriesBg).forEach { view ->
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

    private fun formatCount(count: Int, label: String = "titre"): String =
        "$count $label${if (count > 1) "s" else ""}"

    /** Libellé de la pastille « N nouveautés », comme newBadgeLabel() côté PWA :
     * "+N films"/"+N séries" si un seul type, "+N" (total) si les deux. */
    private fun updateNewBadge() {
        val nf = newFilmsCount
        val ns = newSeriesCount
        val label = when {
            nf == 0 && ns == 0 -> null
            nf > 0 && ns == 0 -> "+$nf film${if (nf > 1) "s" else ""}"
            nf == 0 && ns > 0 -> "+$ns série${if (ns > 1) "s" else ""}"
            else -> "+${nf + ns}"
        }
        if (label != null) {
            binding.tvNewBadge.visibility = View.VISIBLE
            binding.tvNewBadge.text = label
        } else {
            binding.tvNewBadge.visibility = View.GONE
        }
    }

    companion object {
        private const val HUB_ROTATION_INTERVAL_MS = 45_000L
        private const val HUB_CARD_RADIUS_DP = 16f
        private const val HUB_MOVIE_OFFSET = 0
        private const val HUB_SERIES_OFFSET = 5
        // Fond plein écran de l'accueil : stable pour toute la session (static, pas
        // par instance d'Activity — survit à une réouverture de l'accueil), remis à
        // null au logout pour ne pas fuiter vers le profil suivant.
        @Volatile private var cachedHomeBgUrl: String? = null
    }

    private fun logout() {
        val app = application as IptvApplication
        app.sessionManager.clearSession()
        cachedHomeBgUrl = null
        // Coupe le bus temps réel (le prochain compte rouvrira avec son propre jeton).
        app.disconnectRealtime()
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }
}
