package com.nicotv.iptv2.player

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.nicotv.iptv2.IptvApplication
import com.nicotv.iptv2.R
import com.nicotv.iptv2.data.database.entity.EpisodeEntity
import com.nicotv.iptv2.databinding.ActivityPlayerBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Lecteur ExoPlayer : chaînes live, films, épisodes de série. [EXTRA_MOVIE_ID]
 * n'est renseigné (≠ -1) que pour un film/épisode (reprise + historique) — une
 * chaîne live le laisse à -1, aucune reprise n'a de sens pour du direct. */
@UnstableApi
class PlayerActivity : com.nicotv.iptv2.ui.common.BaseActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var viewModel: PlayerViewModel
    private var player: ExoPlayer? = null
    private var movieId: Long = -1L
    private var streamUrl: String = ""
    private var title: String = ""
    private var resume: Boolean = true
    private var seriesId: Long = -1L
    private var seriesTitle: String = ""
    private var episodeTitle: String = ""
    private var episodeNumber: Int = -1
    private var seasonNumber: Int = -1
    private var fileKeyExtra: String = ""
    private var playbackEnded = false
    // Le seek vers la reprise (initPlayer, coroutine) est ASYNCHRONE (requête Room
    // via getResumePosition) : si l'activité se ferme avant qu'il se termine,
    // exo.currentPosition lirait encore ~0 et saveAndRelease() effacerait la
    // reprise existante au lieu de la laisser telle quelle. true dès qu'aucune
    // reprise n'est demandée ou que le seek a eu lieu (ou échoué à trouver une position).
    private var resumeSeekDone = true
    private var hasStartedPlaying = false
    private var currentSpeed = 1.0f
    private var subtitlesEnabled = false
    private var seekHoldStartMs = 0L
    private var lastSeekMs = 0L
    // Suivi manuel de la visibilité du controller pour le double-tap (toggle simple
    // tap ci-dessous) : reflète l'état déjà notifié par setControllerVisibilityListener,
    // pas de nouvelle source de vérité — juste un miroir local.
    private var controllerShown = true

    // Prompt épisode suivant (pas de vraies métadonnées de générique : on approxime
    // avec les dernières secondes du fichier, cf. showNextEpisodePrompt()).
    private var nextEpisode: EpisodeEntity? = null
    private var nextEpisodePromptShown = false
    private var nextEpisodePromptDismissed = false
    private var nextEpisodeWatcherJob: Job? = null
    private var nextEpisodeCountdownJob: Job? = null
    private var nextEpisodeTriggered = false
    // Alimente PlayerSeekBar (pas un id reconnu par le controller Media3 natif,
    // contrairement à exo_position/exo_duration qu'il met à jour lui-même).
    private var seekBarJob: Job? = null

    // Filet de secours : certains flux IPTV (VOD mal terminée) n'émettent jamais
    // Player.STATE_ENDED — le lecteur reste bloqué en STATE_READY/BUFFERING sur la
    // dernière image, sans jamais marquer l'épisode/film vu ni enchaîner. On détecte
    // ce blocage nous-mêmes : position figée alors qu'on est tout près de la fin et
    // qu'on est censé lire (playWhenReady). Cf. STUCK_NEAR_END_MS/STUCK_TICKS_THRESHOLD.
    private var lastKnownPositionMs = -1L
    private var stalledNearEndTicks = 0
    private var stuckEndTriggered = false

    private enum class SubMenu { SPEED, AUDIO, SUBTITLE }

    /** "m<id>" pour un film, "e:<fileKey>" pour un épisode — vide pour une
     * chaîne live (aucune reprise/historique). */
    private val historyKey: String
        get() = if (movieId == -1L) "" else if (seriesId != -1L) "e:$fileKeyExtra" else "m$movieId"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        viewModel = ViewModelProvider(this)[PlayerViewModel::class.java]

        movieId       = intent.getLongExtra(EXTRA_MOVIE_ID, -1L)
        streamUrl     = intent.getStringExtra(EXTRA_STREAM_URL) ?: ""
        title         = intent.getStringExtra(EXTRA_TITLE) ?: ""
        resume        = intent.getBooleanExtra(EXTRA_RESUME, true)
        seriesId      = intent.getLongExtra(EXTRA_SERIES_ID, -1L)
        seriesTitle   = intent.getStringExtra(EXTRA_SERIES_TITLE) ?: ""
        episodeTitle  = intent.getStringExtra(EXTRA_EPISODE_TITLE) ?: ""
        episodeNumber = intent.getIntExtra(EXTRA_EPISODE_NUMBER, -1)
        seasonNumber  = intent.getIntExtra(EXTRA_SEASON_NUMBER, -1)
        fileKeyExtra  = intent.getStringExtra(EXTRA_FILE_KEY) ?: ""

        setupOverlays()
        setupButtons()
        setupGestures()
    }

    private fun setupOverlays() {
        binding.tvPauseTitle.text = if (seriesId != -1L) seriesTitle else title

        if (seriesId != -1L) {
            val epInfo = buildEpisodeInfo()
            if (epInfo.isNotBlank()) {
                binding.tvPauseEpisodeInfo.text = epInfo
                binding.tvPauseEpisodeInfo.visibility = View.VISIBLE
            }
            binding.btnRestart.visibility = View.VISIBLE
            binding.btnNextEpisode.visibility = View.VISIBLE
        }

        binding.playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                controllerShown = visibility == View.VISIBLE
                if (visibility == View.VISIBLE) {
                    binding.overlayTopButtons.visibility = View.VISIBLE
                    binding.overlayTopButtons.alpha = 1f
                } else {
                    binding.overlayTopButtons.animate()
                        .alpha(0f)
                        .setDuration(225)
                        .withEndAction {
                            binding.overlayTopButtons.visibility = View.GONE
                            binding.overlayTopButtons.alpha = 1f
                        }
                        .start()
                    binding.panelSettings.visibility = View.GONE
                    binding.menuMain.visibility = View.VISIBLE
                    binding.menuSub.visibility = View.GONE
                    binding.playerView.controllerShowTimeoutMs = 5000
                }
            }
        )
    }

    private fun buildEpisodeInfo(): String = when {
        seasonNumber > 0 && episodeNumber > 0 && episodeTitle.isNotBlank() ->
            "S$seasonNumber : E$episodeNumber — $episodeTitle"
        episodeNumber > 0 && episodeTitle.isNotBlank() ->
            "Épisode $episodeNumber : $episodeTitle"
        episodeTitle.isNotBlank() -> episodeTitle
        else -> ""
    }

    private fun setupButtons() {
        binding.btnBack.setOnClickListener { saveAndRelease(); finish() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.btnPip.visibility = View.VISIBLE
            binding.btnPip.setOnClickListener { enterPipIfPossible() }
        }
        binding.btnRestart.setOnClickListener {
            player?.seekTo(0); player?.play()
            stuckEndTriggered = false; stalledNearEndTicks = 0; lastKnownPositionMs = -1L
        }
        binding.btnNextEpisode.setOnClickListener { playbackEnded = false; goToNextEpisode() }
        binding.btnNextEpisodePlay.setOnClickListener {
            nextEpisodeCountdownJob?.cancel()
            playbackEnded = true
            goToNextEpisode()
        }
        binding.btnNextEpisodeCancel.setOnClickListener { cancelNextEpisodePrompt() }

        binding.rowSpeed.setOnClickListener { openSubMenu(SubMenu.SPEED) }
        binding.rowAudio.setOnClickListener { openSubMenu(SubMenu.AUDIO) }
        binding.rowSubtitles.setOnClickListener { openSubMenu(SubMenu.SUBTITLE) }
        binding.btnSubBack.setOnClickListener { showMainMenu() }

        listOf(binding.btnBack, binding.btnRestart, binding.btnNextEpisode, binding.btnSubBack)
            .forEach { it.applyTvFocus(1.25f) }
        listOf(binding.rowSpeed, binding.rowAudio, binding.rowSubtitles,
               binding.btnNextEpisodePlay, binding.btnNextEpisodeCancel)
            .forEach { it.applyTvFocus(1.05f) }
    }

    // ── Gestes tactiles (double-tap avance/recule, comme YouTube) ──────────────

    /** Double-tap moitié droite = avance 10s, moitié gauche = recule 10s. Simple
     * tap = toggle controller (remplace le toggle natif de PlayerView, désactivé
     * via controllerHideOnTouch=false — cf. setupOverlays/initPlayer) : un seul
     * OnTouchListener peut être posé sur la vue, donc on réimplémente le toggle
     * nous-mêmes plutôt que de laisser PlayerView gérer le tap tout en captant le
     * double-tap par-dessus (les deux se marcheraient dessus). Rien sur TV (pas
     * d'évènements tactiles via télécommande — le double-tap n'existe que là où un
     * doigt peut toucher l'écran). */
    private fun setupGestures() {
        val detector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                if (controllerShown) binding.playerView.hideController() else binding.playerView.showController()
                return true
            }
            override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                val forward = e.x > binding.playerView.width / 2f
                seekRelative(if (forward) 10_000L else -10_000L)
                return true
            }
        })
        binding.playerView.setOnTouchListener { _, event -> detector.onTouchEvent(event) }
    }

    // ── Panel ────────────────────────────────────────────────────────────────

    private fun openPanel() {
        updateMainMenuValues()
        binding.panelSettings.visibility = View.VISIBLE
        binding.playerView.controllerShowTimeoutMs = 0
        showMainMenu()
    }

    private fun closePanel() {
        binding.panelSettings.visibility = View.GONE
        binding.menuMain.visibility = View.VISIBLE
        binding.menuSub.visibility = View.GONE
        binding.playerView.controllerShowTimeoutMs = 5000
    }

    private fun showMainMenu() {
        binding.menuMain.visibility = View.VISIBLE
        binding.menuSub.visibility = View.GONE
        binding.rowSpeed.requestFocus()
    }

    private fun updateMainMenuValues() {
        binding.tvSpeedValue.text = speedLabel(currentSpeed)
        binding.tvAudioValue.text = currentAudioLabel()
        binding.tvSubtitleValue.text = currentSubtitleLabel()
    }

    // ── Sous-menus ───────────────────────────────────────────────────────────

    private fun openSubMenu(type: SubMenu) {
        binding.menuMain.visibility = View.GONE
        binding.menuSub.visibility = View.VISIBLE
        binding.containerSubOptions.removeAllViews()

        when (type) {
            SubMenu.SPEED -> {
                binding.tvSubTitle.text = "Vitesse"
                listOf(0.5f to "0.5×", 0.75f to "0.75×", 1.0f to "Normal",
                       1.25f to "1.25×", 1.5f to "1.5×", 2.0f to "2×")
                    .forEach { (speed, label) ->
                        addSubOption(label, speed == currentSpeed) {
                            player?.setPlaybackSpeed(speed)
                            currentSpeed = speed
                            updateMainMenuValues()
                            showMainMenu()
                        }
                    }
            }

            SubMenu.AUDIO -> {
                binding.tvSubTitle.text = "Piste audio"
                val tracks = player?.currentTracks
                var found = false
                tracks?.groups?.filter { it.type == C.TRACK_TYPE_AUDIO }?.forEach { group ->
                    for (ti in 0 until group.length) {
                        found = true
                        val fmt = group.getTrackFormat(ti)
                        val label = buildTrackLabel(fmt.language, fmt.label, "Piste ${ti + 1}")
                        addSubOption(label, group.isTrackSelected(ti)) {
                            player?.let {
                                it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                                    .addOverride(TrackSelectionOverride(group.mediaTrackGroup, listOf(ti)))
                                    .build()
                            }
                            updateMainMenuValues()
                            showMainMenu()
                        }
                    }
                }
                if (!found) addSubOption("Aucune piste disponible", false) {}
            }

            SubMenu.SUBTITLE -> {
                binding.tvSubTitle.text = "Sous-titres"
                addSubOption("Désactivé", !subtitlesEnabled) {
                    subtitlesEnabled = false
                    player?.let {
                        it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .build()
                    }
                    updateMainMenuValues()
                    showMainMenu()
                }
                val tracks = player?.currentTracks
                tracks?.groups?.filter { it.type == C.TRACK_TYPE_TEXT }?.forEach { group ->
                    for (ti in 0 until group.length) {
                        val fmt = group.getTrackFormat(ti)
                        val label = buildTrackLabel(fmt.language, fmt.label, "Sous-titres ${ti + 1}")
                        val selected = subtitlesEnabled && group.isTrackSelected(ti)
                        addSubOption(label, selected) {
                            subtitlesEnabled = true
                            player?.let {
                                it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                    .addOverride(TrackSelectionOverride(group.mediaTrackGroup, listOf(ti)))
                                    .build()
                            }
                            updateMainMenuValues()
                            showMainMenu()
                        }
                    }
                }
            }
        }
        binding.btnSubBack.requestFocus()
    }

    private fun addSubOption(label: String, selected: Boolean, onClick: () -> Unit) {
        val tv = TextView(this).apply {
            text = if (selected) "● $label" else "   $label"
            textSize = 14f
            setTextColor(if (selected) Color.parseColor("#4361EE") else Color.WHITE)
            setPadding(20.dp, 14.dp, 20.dp, 14.dp)
            isClickable = true
            isFocusable = true
            background = ContextCompat.getDrawable(this@PlayerActivity, R.drawable.bg_btn_secondary)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = 1.dp
            }
            setOnClickListener { onClick() }
            applyTvFocus(1.05f)
        }
        binding.containerSubOptions.addView(tv)
    }

    // ── Labels valeurs courantes ─────────────────────────────────────────────

    private fun speedLabel(speed: Float) = when (speed) {
        0.5f  -> "0.5×"
        0.75f -> "0.75×"
        1.0f  -> "Normal"
        1.25f -> "1.25×"
        1.5f  -> "1.5×"
        2.0f  -> "2×"
        else  -> "${speed}×"
    }

    private fun currentAudioLabel(): String {
        val tracks = player?.currentTracks ?: return "Auto"
        tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.forEach { group ->
            for (ti in 0 until group.length) {
                if (group.isTrackSelected(ti)) {
                    val fmt = group.getTrackFormat(ti)
                    return buildTrackLabel(fmt.language, fmt.label, "Piste audio")
                }
            }
        }
        return "Auto"
    }

    private fun currentSubtitleLabel(): String {
        if (!subtitlesEnabled) return "Désactivé"
        val tracks = player?.currentTracks ?: return "Désactivé"
        tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.forEach { group ->
            for (ti in 0 until group.length) {
                if (group.isTrackSelected(ti)) {
                    val fmt = group.getTrackFormat(ti)
                    return buildTrackLabel(fmt.language, fmt.label, "Sous-titres")
                }
            }
        }
        return "Désactivé"
    }

    private fun buildTrackLabel(language: String?, label: String?, fallback: String): String {
        val lang = when (language?.lowercase()) {
            "fr", "fre", "fra" -> "Français"
            "en", "eng"        -> "Anglais"
            "es", "spa"        -> "Espagnol"
            "de", "ger", "deu" -> "Allemand"
            "it", "ita"        -> "Italien"
            "pt", "por"        -> "Portugais"
            "ar", "ara"        -> "Arabe"
            null               -> null
            else               -> language
        }
        return lang ?: label ?: fallback
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density + 0.5f).toInt()

    private fun View.applyTvFocus(scale: Float = 1.08f) {
        setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) scale else 1f)
                .scaleY(if (hasFocus) scale else 1f)
                .setDuration(150).start()
        }
    }

    // ── Player ───────────────────────────────────────────────────────────────

    override fun onStart() {
        super.onStart()
        initPlayer()
    }

    override fun onStop() {
        saveAndRelease()
        super.onStop()
    }

    // ── Picture-in-Picture (Android 8+) ─────────────────────────────────────
    // onUserLeaveHint() se déclenche quand l'utilisateur quitte l'app (bouton Home,
    // changement d'appli) — PAS sur Retour/Échap (ceux-là ferment vraiment le lecteur
    // via close()/finish(), comportement inchangé). Entrer en PiP à ce moment-là
    // intercepte le passage en arrière-plan : l'activité reste visible (fenêtre
    // flottante) au lieu de passer par onStop() (qui coupe le player normalement).
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPipIfPossible()
    }

    /** Déclenché par onUserLeaveHint() (bouton Home/changement d'appli) ET par
     *  btn_pip (déclenchement manuel) — onUserLeaveHint n'est pas fiable à 100% selon
     *  navigation gestuelle/OEM (Samsung notamment), d'où le bouton en filet. */
    private fun enterPipIfPossible() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val p = player ?: return
        if (p.isPlaying || p.playWhenReady) {
            runCatching { enterPictureInPictureMode(buildPipParams()) }
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(): PictureInPictureParams {
        val size = player?.videoSize
        val ratio = if (size != null && size.width > 0 && size.height > 0) {
            // Bornes Android (max 2.39:1 ou min 1:2.39) — au cas où une vidéo aurait
            // un ratio extrême, sinon enterPictureInPictureMode lève une exception.
            val r = size.width.toFloat() / size.height.toFloat()
            when {
                r > 2.39f -> Rational(239, 100)
                r < 1f / 2.39f -> Rational(100, 239)
                else -> Rational(size.width, size.height)
            }
        } else Rational(16, 9)
        return PictureInPictureParams.Builder().setAspectRatio(ratio).build()
    }

    // Fenêtre PiP minuscule : masque tous les contrôles/overlays custom (inutilisables
    // à cette taille), ne garde que l'image vidéo. Restaurés au retour plein écran.
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        binding.playerView.useController = !isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            binding.overlayTopButtons.visibility = View.GONE
            binding.overlayPauseInfo.visibility = View.GONE
            binding.overlayNextEpisode.visibility = View.GONE
            binding.panelSettings.visibility = View.GONE
        }
    }

    private fun initPlayer() {
        if (streamUrl.isBlank()) { finish(); return }

        val trackSelector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters().setPreferredAudioLanguage("fr"))
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 60_000, 2_500, 5_000)
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .build()
            .also { exo ->
                binding.playerView.player = exo
                binding.playerView.controllerAutoShow = true
                binding.playerView.controllerHideOnTouch = false
                binding.playerView.setShowSubtitleButton(false)

                // Sous-titres désactivés par défaut
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()

                // Câble le bouton ⚙ intégré au controller custom
                binding.playerView.post {
                    binding.playerView.findViewById<View>(R.id.btn_settings_ctrl)?.let { btn ->
                        btn.setOnClickListener {
                            if (binding.panelSettings.visibility == View.VISIBLE) closePanel()
                            else openPanel()
                        }
                        btn.applyTvFocus(1.25f)
                    }
                    binding.playerView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)?.let { btn ->
                        // Zoom + anneau bleu tournant dans le même listener (pas
                        // applyTvFocus() ici : setOnFocusChangeListener ne s'empile pas,
                        // un 2e appel écraserait le premier).
                        val ring = binding.playerView.findViewById<com.nicotv.iptv2.ui.common.RotatingBorderView>(R.id.play_pause_ring)
                        btn.setOnFocusChangeListener { _, hasFocus ->
                            btn.animate().scaleX(if (hasFocus) 1.25f else 1f).scaleY(if (hasFocus) 1.25f else 1f)
                                .setDuration(150).start()
                            ring?.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
                            if (hasFocus) ring?.startAnim() else ring?.stopAnim()
                        }
                    }
                    binding.playerView.findViewById<PlayerSeekBar>(R.id.player_seekbar)?.let { seekBar ->
                        seekBar.onSeek = { ms -> player?.seekTo(ms) }
                    }
                }

                // Media3 met à jour lui-même exo_position/exo_duration (ids reconnus,
                // indépendants de exo_progress), mais PlayerSeekBar n'est pas un id
                // reconnu par le controller natif : on la nourrit nous-mêmes.
                seekBarJob?.cancel()
                seekBarJob = lifecycleScope.launch {
                    val seekBar = binding.playerView.findViewById<PlayerSeekBar>(R.id.player_seekbar)
                    while (isActive) {
                        val p = player
                        if (seekBar != null && p != null) {
                            seekBar.durationMs = p.duration.coerceAtLeast(0)
                            seekBar.positionMs = p.currentPosition.coerceAtLeast(0)
                            seekBar.bufferedMs = p.bufferedPosition.coerceAtLeast(0)
                        }
                        if (p != null) checkStuckNearEnd(p)
                        delay(500)
                    }
                }

                exo.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        binding.progressBuffering.visibility =
                            if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                        if (state == Player.STATE_ENDED) onPlaybackEnded()
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) hasStartedPlaying = true
                        if (hasStartedPlaying) {
                            binding.overlayPauseInfo.visibility =
                                if (isPlaying) View.GONE else View.VISIBLE
                        }
                    }
                    override fun onTracksChanged(tracks: Tracks) {
                        if (binding.panelSettings.visibility == View.VISIBLE) {
                            updateMainMenuValues()
                        }
                    }
                })

                exo.setMediaItem(MediaItem.fromUri(Uri.parse(streamUrl)))
                exo.prepare()
                exo.playWhenReady = true

                val key = historyKey
                if (key.isNotBlank() && resume) {
                    resumeSeekDone = false
                    lifecycleScope.launch {
                        val pos = viewModel.getResumePosition(key)
                        if (pos > 0) player?.seekTo(pos)
                        resumeSeekDone = true
                    }
                }

                if (seriesId != -1L) {
                    lifecycleScope.launch { nextEpisode = viewModel.getNextEpisode(movieId, seriesId) }
                    nextEpisodeWatcherJob = lifecycleScope.launch {
                        while (isActive) {
                            checkNextEpisodePrompt()
                            delay(1_000)
                        }
                    }
                }
            }
    }

    // Pas de chapitres/métadonnées de générique disponibles : on considère qu'on est
    // « au générique » dans les NEXT_EPISODE_LOOKAHEAD_MS dernières millisecondes du
    // fichier. Le prompt reste affiché 5s (NEXT_EPISODE_COUNTDOWN_S) avant d'enchaîner.
    private fun checkNextEpisodePrompt() {
        val exo = player ?: return
        if (nextEpisode == null || nextEpisodePromptShown || nextEpisodePromptDismissed) return
        val duration = exo.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        val remaining = duration - exo.currentPosition
        if (remaining in 0..NEXT_EPISODE_LOOKAHEAD_MS) showNextEpisodePrompt()
    }

    private fun showNextEpisodePrompt() {
        val ep = nextEpisode ?: return
        nextEpisodePromptShown = true
        binding.overlayNextEpisode.visibility = View.VISIBLE
        binding.tvNextEpisodeTitle.text = buildString {
            if (ep.seasonNumber > 0 && ep.episodeNumber > 0) append("S${ep.seasonNumber} : E${ep.episodeNumber} — ")
            append(ep.episodeTitle)
        }
        nextEpisodeCountdownJob = lifecycleScope.launch {
            for (s in NEXT_EPISODE_COUNTDOWN_S downTo 1) {
                binding.tvNextEpisodeCountdown.text = "Épisode suivant dans ${s}s"
                delay(1_000)
            }
            playbackEnded = true
            goToNextEpisode()
        }
    }

    private fun cancelNextEpisodePrompt() {
        nextEpisodeCountdownJob?.cancel()
        binding.overlayNextEpisode.visibility = View.GONE
        nextEpisodePromptDismissed = true
    }

    // Voir commentaire sur les champs stuck* : filet de secours quand STATE_ENDED
    // n'arrive jamais. Ne se déclenche que si on est tout près de la fin, qu'on est
    // censé être en lecture (playWhenReady) et que la position ne bouge plus pendant
    // STUCK_TICKS_THRESHOLD tics consécutifs (boucle toutes les 500ms).
    private fun checkStuckNearEnd(p: Player) {
        if (stuckEndTriggered) return
        val duration = p.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        if (p.playbackState == Player.STATE_ENDED) return

        val remaining = duration - p.currentPosition
        val nearEnd = remaining in 0..STUCK_NEAR_END_MS && p.playWhenReady

        if (nearEnd && p.currentPosition == lastKnownPositionMs) {
            stalledNearEndTicks++
            if (stalledNearEndTicks >= STUCK_TICKS_THRESHOLD) {
                stuckEndTriggered = true
                onPlaybackEnded()
            }
        } else {
            stalledNearEndTicks = 0
        }
        lastKnownPositionMs = p.currentPosition
    }

    private fun onPlaybackEnded() {
        playbackEnded = true
        if (seriesId == -1L) { finish(); return }
        goToNextEpisode()
    }

    private fun goToNextEpisode() {
        // Idempotent : la fin réelle (STATE_ENDED) et le countdown du prompt "épisode
        // suivant" peuvent chacun appeler cette fonction à quelques secondes d'intervalle
        // (générique plus court que prévu) → sans garde, ça lancerait 2 activités.
        if (nextEpisodeTriggered) return
        nextEpisodeTriggered = true
        if (seriesId == -1L) { finish(); return }
        lifecycleScope.launch {
            val next = viewModel.getNextEpisode(movieId, seriesId)
            if (next != null) {
                saveAndRelease()
                startActivity(Intent(this@PlayerActivity, PlayerActivity::class.java).apply {
                    putExtra(EXTRA_MOVIE_ID, next.watchKey)
                    putExtra(EXTRA_STREAM_URL, next.streamUrl)
                    putExtra(EXTRA_TITLE, "$seriesTitle — ${next.episodeTitle}")
                    putExtra(EXTRA_RESUME, false)
                    putExtra(EXTRA_SERIES_ID, seriesId)
                    putExtra(EXTRA_SERIES_TITLE, seriesTitle)
                    putExtra(EXTRA_EPISODE_TITLE, next.episodeTitle)
                    putExtra(EXTRA_EPISODE_NUMBER, next.episodeNumber)
                    putExtra(EXTRA_SEASON_NUMBER, next.seasonNumber)
                    putExtra(EXTRA_FILE_KEY, next.fileKey)
                })
                finish()
            } else {
                finish()
            }
        }
    }

    private fun saveAndRelease() {
        nextEpisodeWatcherJob?.cancel()
        nextEpisodeCountdownJob?.cancel()
        seekBarJob?.cancel()
        player?.let { exo ->
            val key = historyKey
            if (key.isNotBlank()) {
                // À moins d'1 min de la fin (durée connue) : considéré comme terminé
                // même si l'utilisateur quitte via Retour — l'auto-enchaînement/prompt
                // ne se déclenche que dans les 45 dernières secondes (cf.
                // NEXT_EPISODE_LOOKAHEAD_MS), donc sans ça un retour entre -60s et -45s
                // ne marquait jamais l'élément comme terminé.
                val duration = exo.duration
                if (!playbackEnded && duration != C.TIME_UNSET && duration > 0 &&
                    duration - exo.currentPosition <= NEAR_END_FINISHED_MS
                ) {
                    playbackEnded = true
                }
                // Le seek de reprise n'a pas fini (fermeture très rapide après
                // réouverture) : exo.currentPosition ne reflète pas encore la vraie
                // position → ne pas toucher la reprise existante plutôt que l'effacer
                // avec une valeur ~0 non fiable. playbackEnded (fin réelle) reste
                // prioritaire : la position n'y est pas utilisée pour la décision.
                if (resumeSeekDone || playbackEnded) {
                    // progressId = movieId : id du film, ou watchKey de l'épisode
                    // (les deux transitent par le même extra EXTRA_MOVIE_ID).
                    viewModel.savePosition(key, movieId, if (seriesId != -1L) seriesTitle else title, exo.currentPosition, exo.duration, playbackEnded)
                    // `resume` ne reflète que l'intent DE DÉPART (false pour un film lancé
                    // depuis zéro via "▶ Lecture") et ne changeait jamais ensuite : au retour
                    // d'arrière-plan, initPlayer() se relance sans jamais chercher la
                    // position qu'on vient tout juste de sauvegarder ci-dessus → repart de
                    // zéro à chaque fois. Une fois une vraie position sauvegardée, un futur
                    // initPlayer() doit toujours tenter de reprendre.
                    if (!playbackEnded) resume = true
                }
            }
            exo.release()
        }
        player = null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_DPAD_CENTER -> {
                player?.let { if (it.isPlaying) it.pause() else it.play() }
                true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // Barre de défilement focusée : droite = avance (+10s, puis de plus
                // en plus vite si maintenu, cf. handleSeekKey) au lieu de déplacer le
                // focus. Autres contrôles (boutons) : navigation D-pad normale.
                if (currentFocus is PlayerSeekBar || !isFocusOnControl()) {
                    handleSeekKey(event, +1)
                    true
                } else super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (currentFocus is PlayerSeekBar || !isFocusOnControl()) {
                    handleSeekKey(event, -1)
                    true
                } else super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (currentFocus is PlayerSeekBar) {
                    binding.playerView.post {
                        binding.playerView.findViewById<View>(R.id.btn_settings_ctrl)?.requestFocus()
                    }
                    true
                } else super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (currentFocus?.id == R.id.btn_settings_ctrl) {
                    binding.playerView.post {
                        binding.playerView.findViewById<View>(R.id.player_seekbar)?.requestFocus()
                    }
                    true
                } else super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_BACK -> { saveAndRelease(); finish(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun handleSeekKey(event: KeyEvent?, direction: Int) {
        val repeatCount = event?.repeatCount ?: 0
        val now = System.currentTimeMillis()
        if (repeatCount == 0) {
            seekHoldStartMs = now
            lastSeekMs = now
            seekRelative(direction * 10_000L)
        } else {
            val holdMs = now - seekHoldStartMs
            val interval = when {
                holdMs < 1_000 -> 800L
                holdMs < 3_000 -> 500L
                else           -> 300L
            }
            if (now - lastSeekMs >= interval) {
                lastSeekMs = now
                // Accélération progressive tant que la touche reste enfoncée :
                // 10s -> 30s -> 1min -> 2min -> 5min -> 10min.
                val step = when {
                    holdMs <  1_000 ->  10_000L
                    holdMs <  3_000 ->  30_000L
                    holdMs <  6_000 ->  60_000L
                    holdMs <  9_000 -> 120_000L
                    holdMs < 13_000 -> 300_000L
                    else            -> 600_000L
                }
                seekRelative(direction * step)
            }
        }
    }

    private fun seekRelative(deltaMs: Long) {
        player?.let {
            val target = it.currentPosition + deltaMs
            it.seekTo(when {
                deltaMs > 0 && it.duration != C.TIME_UNSET -> target.coerceAtMost(it.duration)
                deltaMs < 0 -> target.coerceAtLeast(0)
                else -> target
            })
        }
    }

    private fun isFocusOnControl(): Boolean {
        val f = currentFocus ?: return false
        return f is PlayerSeekBar
            || f is android.widget.ImageButton
            || f is TextView
            || (f is LinearLayout && f.isFocusable)
    }

    companion object {
        const val EXTRA_MOVIE_ID       = "movie_id"
        const val EXTRA_STREAM_URL     = "stream_url"
        const val EXTRA_TITLE          = "title"
        const val EXTRA_RESUME         = "resume"
        const val EXTRA_SERIES_ID      = "series_id"
        const val EXTRA_SERIES_TITLE   = "series_title"
        const val EXTRA_EPISODE_TITLE  = "episode_title"
        const val EXTRA_EPISODE_NUMBER = "episode_number"
        const val EXTRA_SEASON_NUMBER  = "season_number"
        const val EXTRA_FILE_KEY       = "file_key"

        private const val NEXT_EPISODE_LOOKAHEAD_MS = 45_000L
        private const val NEXT_EPISODE_COUNTDOWN_S = 5

        // Filet de secours STATE_ENDED manquant (cf. checkStuckNearEnd) : à moins de
        // 1,5s de la fin annoncée, position figée pendant 4 tics de la boucle seekBar
        // (500ms chacun, donc ~2s) → on considère la lecture terminée nous-mêmes.
        private const val STUCK_NEAR_END_MS = 1_500L
        private const val STUCK_TICKS_THRESHOLD = 4
        // Retour (bouton/télécommande) à moins d'1 min de la fin = considéré comme
        // terminé, même hors de la fenêtre d'auto-enchaînement.
        private const val NEAR_END_FINISHED_MS = 60_000L
    }
}
