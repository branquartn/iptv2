package com.nicotv.iptv.ui.detail

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.view.WindowManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.view.KeyEvent
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
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
import com.nicotv.iptv.IptvApplication
import com.nicotv.iptv.R
import com.nicotv.iptv.data.database.entity.DownloadEntity
import com.nicotv.iptv.data.network.model.TmdbCastMember
import com.nicotv.iptv.data.network.model.TmdbMultiResult
import com.nicotv.iptv.databinding.ActivityDetailBinding
import com.nicotv.iptv.databinding.ItemTmdbResultBinding
import com.nicotv.iptv.domain.model.OpenTarget
import com.nicotv.iptv.domain.model.SimilarWork
import com.nicotv.iptv.player.PlayerActivity
import com.nicotv.iptv.ui.common.isTvDevice
import com.nicotv.iptv.ui.series.SeriesDetailActivity
import kotlinx.coroutines.launch

class DetailActivity : com.nicotv.iptv.ui.common.BaseActivity() {

    private lateinit var binding: ActivityDetailBinding
    private lateinit var viewModel: DetailViewModel
    private var currentResumePos: Long = 0L
    private var currentDurationMs: Long = 0L
    private var movieId: Long = -1L
    // Téléchargement local courant (mode avion) pour ce film — null = pas téléchargé.
    private var currentDownload: DownloadEntity? = null
    // TV : dernière carte/acteur focusé dans chaque rangée, restauré en y revenant
    // depuis une rangée voisine (D-pad) — cf. restoreSimilarFocus/restoreCastFocus.
    private var lastSimilarFocusedPosition = 0
    private var lastCastFocusedPosition = 0
    // TV : dernier bouton icône focusé (Lecture/Favori/…), restauré en revenant depuis
    // Films similaires (D-pad haut) — sinon focus « le plus proche géométriquement ».
    private var lastIconRowFocused: View? = null
    // TV/Shield/Fire TV : badge ✓/+ masqué sur les cartes (D-pad peu fiable pour un
    // 2e cible cliquable) — l'aperçu (clic carte) propose alors ajouter/ouvrir.
    private val tvMode by lazy { isTvDevice() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel = ViewModelProvider(this)[DetailViewModel::class.java]

        // Marge basse 48dp = zone de sécurité TV (overscan). Sur mobile, pas
        // d'overscan à gérer : la rangée de boutons (Lecture/Favori/…) flottait
        // avec un vide inutile en dessous — réduite pour coller plus près du bas.
        if (!tvMode) {
            binding.contentRoot.setPadding(
                binding.contentRoot.paddingLeft,
                binding.contentRoot.paddingTop,
                binding.contentRoot.paddingRight,
                (16 * resources.displayMetrics.density).toInt()
            )
        } else {
            // TV : casting poussé trop bas par l'empilement des marges du synopsis/
            // réalisateur, et la rangée de boutons + son écart avec la zone de scroll
            // grignotent la hauteur restante pour Films similaires (jaquettes tronquées
            // en bas faute de place). Compacté ici, pas en dur dans le XML : le mobile
            // garde ses marges normales.
            val d = resources.displayMetrics.density
            fun View.setBottomMargin(dp: Int) {
                (layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                    it.bottomMargin = (dp * d).toInt()
                    layoutParams = it
                }
            }
            fun View.setTopMargin(dp: Int) {
                (layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                    it.topMargin = (dp * d).toInt()
                    layoutParams = it
                }
            }
            binding.tvOverview.setBottomMargin(6)
            binding.tvOverviewToggle.setBottomMargin(8)
            binding.llDirector.setBottomMargin(8)
            binding.tvCastTitle.setTopMargin(8)
            binding.scrollFrame.setBottomMargin(10)
            // Le zoom au focus des jaquettes similaires pivote depuis le bas (cf.
            // SimilarWorkAdapter) → il grandit vers le HAUT ; laisser un peu de
            // padding haut pour ce débordement, bas compact.
            binding.rvSimilar.setPadding(
                binding.rvSimilar.paddingLeft, (8 * d).toInt(),
                binding.rvSimilar.paddingRight, (4 * d).toInt()
            )
        }

        movieId = intent.getLongExtra(EXTRA_MOVIE_ID, -1L)
        if (movieId == -1L) { finish(); return }

        lifecycleScope.launch {
            val key = DownloadEntity.movieKey(movieId)
            (application as IptvApplication).downloadRepository.getAllFlow().collect { list ->
                currentDownload = list.find { it.key == key }
                updateDownloadButton()
            }
        }

        viewModel.movie.observe(this) { movie ->
            if (movie == null) return@observe
            (application as IptvApplication).reportScreen("Fiche : " + movie.title)
            binding.ivBackdrop.load(movie.backdropUrl.ifBlank { movie.posterUrl }) {
                crossfade(true)
                placeholder(R.drawable.gradient_hero)
            }
            binding.ivPoster.load(movie.posterUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_movie_placeholder)
            }
            binding.tvTitle.text = movie.title
            binding.tvYear.text = movie.releaseYear
            binding.tvRuntime.text = movie.runtimeFormatted
            currentDurationMs = movie.runtime * 60_000L
            updatePlayRuntimeDisplay()
            binding.tvRating.text = movie.ratingFormatted
            binding.tvGenres.text = movie.genresFormatted

            binding.tvOverview.text = movie.overview
            binding.tvOverview.maxLines = 3
            binding.tvOverview.ellipsize = android.text.TextUtils.TruncateAt.END
            var overviewExpanded = false
            binding.tvOverview.post {
                // Bouton affiché seulement si le texte dépasse réellement 3 lignes
                // (layout déjà limité par maxLines : la dernière ligne visible porte
                // l'ellipse si le texte a été tronqué).
                binding.tvOverviewToggle.visibility =
                    if ((binding.tvOverview.layout?.getEllipsisCount(2) ?: 0) > 0)
                        View.VISIBLE else View.GONE
            }
            binding.tvOverviewToggle.setOnClickListener {
                overviewExpanded = !overviewExpanded
                if (overviewExpanded) {
                    binding.tvOverview.maxLines = Int.MAX_VALUE
                    binding.tvOverview.ellipsize = null
                    binding.tvOverviewToggle.text = getString(R.string.overview_less)
                } else {
                    binding.tvOverview.maxLines = 3
                    binding.tvOverview.ellipsize = android.text.TextUtils.TruncateAt.END
                    binding.tvOverviewToggle.text = getString(R.string.overview_more)
                }
            }

            // Étoile always filled ; tint gris si non favori, orange naturel si favori.
            binding.ivFavorite.setImageDrawable(
                AppCompatResources.getDrawable(this, R.drawable.ic_favorite_filled)
            )
            binding.ivFavorite.imageTintList = if (movie.isFavorite) null
            else ColorStateList.valueOf(ContextCompat.getColor(this, R.color.text_secondary))

            binding.btnPlay.setOnClickListener { play(movie, resume = currentResumePos > 0) }
            binding.btnRestart.setOnClickListener { play(movie, resume = false) }
            binding.btnFavorite.setOnClickListener { viewModel.toggleFavorite() }
            binding.btnDownload.setOnClickListener {
                val app = application as IptvApplication
                when (currentDownload?.state) {
                    DownloadEntity.STATE_COMPLETED -> confirmDeleteDownload(movie)
                    DownloadEntity.STATE_DOWNLOADING, DownloadEntity.STATE_QUEUED ->
                        lifecycleScope.launch { app.downloadRepository.delete(DownloadEntity.movieKey(movie.id)) }
                    else -> app.downloadRepository.enqueueMovie(movie)
                }
            }

            if (movie.tmdbId > 0) viewModel.loadExtras(movie.tmdbId)
            binding.btnTrailer.visibility = if (movie.tmdbId > 0) View.VISIBLE else View.GONE
            rewireIconRowFocus()
            binding.btnTrailer.setOnClickListener {
                binding.btnTrailer.isEnabled = false
                lifecycleScope.launch {
                    val key = viewModel.loadTrailerKey(movie.tmdbId)
                    binding.btnTrailer.isEnabled = true
                    if (key.isNullOrBlank()) {
                        Toast.makeText(this@DetailActivity, getString(R.string.not_available_yet), Toast.LENGTH_SHORT).show()
                    } else {
                        openYoutube(key)
                    }
                }
            }
        }

        val castAdapter = CastAdapter(
            onClick = { showActorDialog(it) },
            onDpadDownFromCast = { restoreSimilarFocus() },
            onDpadUpFromCast = { focusAboveCast() },
            onFocusPositionChanged = { lastCastFocusedPosition = it }
        )
        binding.rvCast.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvCast.adapter = castAdapter

        val similarAdapter = SimilarWorkAdapter(
            onBadgeClick = { handleWorkClick(it) },
            onPreviewClick = { showWorkPreview(it) },
            showBadge = !tvMode,
            onFocusPositionChanged = { lastSimilarFocusedPosition = it },
            onDpadUpFromSimilar = { restoreCastFocus() },
            onDpadDownFromSimilar = { restoreIconRowFocus() }
        )
        binding.rvSimilar.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvSimilar.adapter = similarAdapter

        // « + Lire la suite » -> bas D-pad : s'arrête sur Réalisé par s'il est visible
        // (ordre vertical logique), sinon restaure le dernier acteur focusé dans le
        // casting (même mécanisme que Films similaires <-> Casting ci-dessus).
        binding.tvOverviewToggle.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (binding.llDirector.visibility == View.VISIBLE) {
                    binding.tvDirector.requestFocus()
                } else {
                    restoreCastFocus()
                }
            } else false
        }
        // Réalisé par <-> voisins : haut vers « + Lire la suite » (ou rien s'il est
        // absent, synopsis court), bas vers le casting.
        binding.tvDirector.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP ->
                    if (binding.tvOverviewToggle.visibility == View.VISIBLE) {
                        binding.tvOverviewToggle.requestFocus()
                    } else false
                KeyEvent.KEYCODE_DPAD_DOWN -> restoreCastFocus()
                else -> false
            }
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
        viewModel.addResult.observe(this) { msg ->
            if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        viewModel.resumePosition.observe(this) { pos ->
            currentResumePos = pos
            binding.btnRestart.visibility = if (pos > 0) View.VISIBLE else View.GONE
            updatePlayRuntimeDisplay()
            rewireIconRowFocus()
        }

        viewModel.relinkDone.observe(this) { done ->
            if (done) Toast.makeText(this, getString(R.string.tmdb_relink_success), Toast.LENGTH_SHORT).show()
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRelinkTmdb.setOnClickListener { showTmdbSearchDialog() }

        // Liens texte (Lire la suite, réalisateur) : au focus D-pad, texte souligné
        // en blanc + léger zoom — même logique de retour visuel que les boutons
        // icône (le focus doit se voir à la télécommande), mais sans anneau
        // (RotatingBorderView réservé aux boutons icône). Sans soulignement, la
        // couleur d'accent seule ne signalait pas le focus.
        setTextLinkFocusEffect(binding.tvOverviewToggle)
        setTextLinkFocusEffect(binding.tvDirector)

        // Boutons icône (retour, lecture, favori, recommencer, TMDb, bande-annonce) :
        // zoom renforcé comme l'accueil + anneau blanc tournant (RotatingBorderView).
        val iconRings = mapOf(
            binding.btnBack to binding.btnBackRing,
            binding.btnPlay to binding.btnPlayRing,
            binding.btnFavorite to binding.btnFavoriteRing,
            binding.btnDownload to binding.btnDownloadRing,
            binding.btnRestart to binding.btnRestartRing,
            binding.btnRelinkTmdb to binding.btnTmdbRing,
            binding.btnTrailer to binding.btnTrailerRing
        )
        iconRings.forEach { (view, ring) ->
            view.setOnFocusChangeListener { v, hasFocus ->
                v.animate()
                    .scaleX(if (hasFocus) 1.25f else 1f)
                    .scaleY(if (hasFocus) 1.25f else 1f)
                    .setDuration(150).start()
                v.z = if (hasFocus) 10f else 0f
                ring.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
                if (hasFocus) ring.startAnim() else ring.stopAnim()
                if (hasFocus) lastIconRowFocused = v
            }
            // Haut D-pad depuis n'importe quelle icône -> restaure la dernière carte
            // focusée dans « Films similaires » (même mécanisme que casting/similaires).
            view.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    restoreSimilarFocus()
                } else false
            }
        }

        binding.btnPlay.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        if (movieId != -1L) viewModel.loadMovie(movieId)
    }

    // ── Dialogue de recherche TMDb ───────────────────────────────────────────

    private fun showTmdbSearchDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_tmdb_search, null)
        val etQuery  = dialogView.findViewById<EditText>(R.id.et_tmdb_query)
        val btnClear = dialogView.findViewById<ImageView>(R.id.btn_clear_tmdb)
        val btnSearch = dialogView.findViewById<Button>(R.id.btn_tmdb_search)
        val progress  = dialogView.findViewById<ProgressBar>(R.id.progress_tmdb)
        val rv        = dialogView.findViewById<RecyclerView>(R.id.rv_tmdb_results)
        val searchRing = dialogView.findViewById<com.nicotv.iptv.ui.common.RotatingBorderView>(R.id.search_ring)

        etQuery.setText(viewModel.movie.value?.title ?: "")
        etQuery.selectAll()

        // Bouton croix : efface le texte et redonne le focus au champ
        btnClear.visibility = if (etQuery.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        btnClear.setOnClickListener {
            etQuery.setText("")
            etQuery.requestFocus()
        }
        etQuery.setOnFocusChangeListener { _, hasFocus ->
            searchRing.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
            if (hasFocus) searchRing.startAnim() else searchRing.stopAnim()
        }
        etQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                btnClear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
        })

        val adapter = TmdbResultAdapter { result ->
            dialog?.dismiss()
            viewModel.relinkMovie(result)
        }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.dialog_search_tmdb)
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog)

        this.dialog = dialog

        viewModel.isSearching.observe(this) { searching ->
            progress.visibility = if (searching) View.VISIBLE else View.GONE
            btnSearch.isEnabled = !searching
        }
        viewModel.searchResults.observe(this) { results ->
            adapter.submitList(results)
        }

        val doSearch = {
            val query = etQuery.text.toString().trim()
            if (query.isNotBlank()) {
                hideKeyboard(etQuery)
                viewModel.searchTmdb(query)
            }
        }
        btnSearch.setOnClickListener { doSearch() }
        etQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false
        }

        dialog.show()
        // Lance une recherche immédiate avec le titre actuel
        doSearch()
    }

    private var dialog: AlertDialog? = null

    private fun hideKeyboard(view: View) {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(view.windowToken, 0)
    }

    // ── Adapter inline ───────────────────────────────────────────────────────

    inner class TmdbResultAdapter(
        private val onPick: (TmdbMultiResult) -> Unit
    ) : RecyclerView.Adapter<TmdbResultAdapter.VH>() {

        private var items = listOf<TmdbMultiResult>()

        fun submitList(list: List<TmdbMultiResult>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val b = ItemTmdbResultBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
        override fun getItemCount() = items.size

        inner class VH(private val b: ItemTmdbResultBinding) : RecyclerView.ViewHolder(b.root) {
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

    // ── Casting / films similaires / bande-annonce (portage PWA) ────────────

    /** Déjà possédé → ouvre sa fiche ; sinon ajoute à la file de téléchargement
     * (Toast via viewModel.addResult, observé dans onCreate). */
    private fun handleWorkClick(work: SimilarWork) {
        lifecycleScope.launch {
            when (val target = viewModel.resolveOrAdd(work)) {
                is OpenTarget.MovieTarget -> startActivity(
                    Intent(this@DetailActivity, DetailActivity::class.java)
                        .putExtra(EXTRA_MOVIE_ID, target.movieId)
                )
                is OpenTarget.SeriesTarget -> startActivity(
                    Intent(this@DetailActivity, SeriesDetailActivity::class.java)
                        .putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, target.seriesId)
                        .putExtra(SeriesDetailActivity.EXTRA_SERIES_TITLE, target.title)
                )
                null -> Unit   // ajouté : le Toast vient de addResult
            }
        }
    }

    /** Lien texte focusable (réalisateur, « Lire la suite »…) : au focus, texte blanc
     * souligné + léger zoom ; au repos, couleur d'origine sans soulignement. Même rôle
     * de signal de focus que l'anneau des boutons icône, adapté au texte. */
    private fun setTextLinkFocusEffect(tv: TextView) {
        val restColor = tv.currentTextColor
        tv.setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) 1.1f else 1f)
                .scaleY(if (hasFocus) 1.1f else 1f)
                .setDuration(150).start()
            if (hasFocus) {
                tv.setTextColor(Color.WHITE)
                tv.paintFlags = tv.paintFlags or Paint.UNDERLINE_TEXT_FLAG
            } else {
                tv.setTextColor(restColor)
                tv.paintFlags = tv.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
            }
        }
    }

    private fun showActorDialog(member: TmdbCastMember, asDirector: Boolean = false) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_actor, null)
        // Mobile : en-tête custom léger (✕, ~50dp) au lieu du vrai titre+bouton OK
        // système de TV (~150-220dp) — plafond de hauteur du dialog moins conservateur,
        // sinon la fenêtre restait plus courte que nécessaire sur mobile.
        view.findViewById<com.nicotv.iptv.ui.common.MaxHeightScrollView>(R.id.actor_scroll)
            ?.setChromeReserveDp(if (tvMode) 220 else 90)
        val actorHeader = view.findViewById<View>(R.id.actor_header)
        val tvActorName = view.findViewById<TextView>(R.id.tv_actor_name)
        val btnActorClose = view.findViewById<TextView>(R.id.btn_actor_close)
        val ivPhoto = view.findViewById<ImageView>(R.id.iv_actor_photo)
        val tvBio = view.findViewById<TextView>(R.id.tv_actor_bio)
        val progress = view.findViewById<ProgressBar>(R.id.progress_actor)
        val rv = view.findViewById<RecyclerView>(R.id.rv_actor_filmography)
        // Réalisateur : liste des films réalisés (crew), pas la filmographie d'acteur.
        view.findViewById<TextView>(R.id.tv_filmo_label)?.setText(
            if (asDirector) R.string.title_directed_films else R.string.title_filmography
        )

        // Cadrage tête-en-haut fait par TopCropImageView (pas de Transformation Coil).
        ivPhoto.load(member.profileUrl.ifBlank { null }) {
            crossfade(true)
            placeholder(R.drawable.ic_movie_placeholder)
            error(R.drawable.ic_movie_placeholder)
        }

        val filmoAdapter = SimilarWorkAdapter(
            onBadgeClick = { handleWorkClick(it) },
            onPreviewClick = { showWorkPreview(it) },
            gridMode = true,
            showBadge = !tvMode
        )
        rv.layoutManager = GridLayoutManager(this, filmographySpanCount())
        rv.adapter = filmoAdapter

        // Mobile : en-tête custom (titre + ✕), pas de titre/bouton OK système — comme
        // la fiche aperçu (dialog_work_preview). TV : garde setTitle/setNegativeButton.
        val builder = AlertDialog.Builder(this).setView(view)
        if (tvMode) builder.setTitle(member.name).setNegativeButton(android.R.string.ok, null)
        val dialog = builder.create()
        dialog.show()
        if (!tvMode) {
            actorHeader.visibility = View.VISIBLE
            tvActorName.text = member.name
            btnActorClose.setOnClickListener { dialog.dismiss() }
        }
        // Largeur pleine écran (par défaut AlertDialog reste étroit) : sinon la
        // grille compresse les jaquettes en carré au lieu du format 2:3. Hauteur
        // WRAP_CONTENT : le plafond (85 % écran) + le scroll sont gérés par
        // MaxHeightScrollView (racine de dialog_actor.xml) — contenu court =
        // dialog court, contenu long = plafonné + scroll interne.
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        // Fond rectangulaire système gris (pas de coin arrondi) remplacé par notre
        // fond de dialog habituel — comme dialog_quit.xml, cf. showQuitDialog.
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog)
        // Filmographie : un seul scroll unifié (photo+bio+grille), comme la PWA —
        // la grille ne doit pas scroller "à part" dans son propre cadre.
        rv.isNestedScrollingEnabled = false

        lifecycleScope.launch {
            val person = viewModel.loadPerson(member.id)
            tvBio.text = person?.biography?.takeIf { it.isNotBlank() } ?: ""
            tvBio.visibility = if (tvBio.text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        lifecycleScope.launch {
            val filmography = if (asDirector) viewModel.loadPersonDirected(member.id)
                              else viewModel.loadPersonFilmography(member.id)
            progress.visibility = View.GONE
            filmoAdapter.submitList(filmography)
            // La grille arrive APRÈS que le dialog soit déjà affiché/dimensionné
            // (fetch réseau async) : sans forcer un nouveau passage de layout sur
            // tout l'arbre, le ScrollView garde sa plage de scroll d'avant
            // (mesurée quand la grille était encore vide) → seule la 1re rangée
            // apparaît, le reste est invisible et inatteignable au scroll.
            rv.post { view.requestLayout() }
        }
    }

    /** "position/durée" (ex. 14:19/2:20:43) à côté de l'icône bande-annonce,
     * SEULEMENT si une reprise est disponible (pos vient d'un autre observer,
     * movie d'un autre — appelée par les deux, chacun avec sa dernière valeur
     * connue) — masqué sinon (pas de durée totale affichée pour un film jamais
     * commencé). */
    private fun updatePlayRuntimeDisplay() {
        if (currentResumePos <= 0) {
            binding.tvPlayRuntime.visibility = View.GONE
            return
        }
        binding.tvPlayRuntime.text =
            "${formatClock(currentResumePos)}/${formatClock(currentDurationMs)}"
        binding.tvPlayRuntime.visibility = View.VISIBLE
    }

    /** H:MM:SS si ≥1h, sinon MM:SS. */
    private fun formatClock(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** Chaînage D-pad explicite gauche/droite entre les icônes de ll_buttons_row
     * (retour, lecture, favori, recommencer, TMDb, bande-annonce) — sans ça, la
     * recherche de focus par défaut d'Android saute parfois toute la rangée (ex.
     * Lecture → droite atterrissait sur « Films similaires » en dessous au lieu de
     * Favori juste à côté). Recalculé à chaque changement de visibilité (recommencer/
     * bande-annonce conditionnels) : seuls les boutons VISIBLES sont chaînés entre eux. */
    private fun rewireIconRowFocus() {
        val order = listOfNotNull(
            binding.btnPlay,
            binding.btnFavorite,
            binding.btnDownload,
            binding.btnRestart.takeIf { it.visibility == View.VISIBLE },
            binding.btnRelinkTmdb,
            binding.btnTrailer.takeIf { it.visibility == View.VISIBLE },
            binding.btnBack
        )
        for (i in order.indices) {
            order.getOrNull(i - 1)?.let { order[i].nextFocusLeftId = it.id }
            order.getOrNull(i + 1)?.let { order[i].nextFocusRightId = it.id }
        }
        // Lecture → gauche boucle sur Retour (le spacer qui pousse Retour tout à
        // droite le sort sinon de la recherche de focus par défaut depuis Lecture,
        // le bouton le plus à gauche de la rangée).
        binding.btnPlay.nextFocusLeftId = binding.btnBack.id
    }

    /** Confirmation avant de supprimer un film déjà téléchargé (mode avion). */
    private fun confirmDeleteDownload(movie: com.nicotv.iptv.domain.model.Movie) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Supprimer le téléchargement ?")
            .setMessage(movie.title)
            .setPositiveButton("Supprimer") { _, _ ->
                lifecycleScope.launch {
                    (application as IptvApplication).downloadRepository.delete(DownloadEntity.movieKey(movie.id))
                }
            }
            .setNegativeButton("Annuler", null)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog)
    }

    /** Icône du bouton téléchargement selon l'état courant (mode avion). */
    private fun updateDownloadButton() {
        val d = currentDownload
        when (d?.state) {
            DownloadEntity.STATE_COMPLETED -> {
                binding.progressDownload.visibility = View.GONE
                binding.tvDownloadPct.visibility = View.GONE
                binding.ivDownload.visibility = View.VISIBLE
                binding.ivDownload.setImageResource(R.drawable.ic_download_done)
            }
            DownloadEntity.STATE_QUEUED, DownloadEntity.STATE_DOWNLOADING -> {
                binding.ivDownload.visibility = View.GONE
                val pct = if (d.bytesTotal > 0) (d.bytesDownloaded * 100 / d.bytesTotal).toInt() else -1
                if (pct >= 0) {
                    binding.progressDownload.visibility = View.GONE
                    binding.tvDownloadPct.visibility = View.VISIBLE
                    binding.tvDownloadPct.text = "$pct%"
                } else {
                    binding.progressDownload.visibility = View.VISIBLE
                    binding.tvDownloadPct.visibility = View.GONE
                }
            }
            else -> {
                binding.progressDownload.visibility = View.GONE
                binding.tvDownloadPct.visibility = View.GONE
                binding.ivDownload.visibility = View.VISIBLE
                binding.ivDownload.setImageResource(R.drawable.ic_download)
            }
        }
    }

    /** TV : restaure le focus sur la carte de « Films similaires » quittée en dernier
     * (D-pad bas depuis le casting ou « + Lire la suite »), au lieu de la carte la
     * plus proche géométriquement de la vue actuellement focusée (comportement par
     * défaut Android, pas nécessairement le même film qu'avant). true = focus repris,
     * consomme la touche. */
    private fun restoreSimilarFocus(): Boolean =
        restoreRowFocus(binding.rvSimilar, lastSimilarFocusedPosition)

    /** TV : symétrique de restoreSimilarFocus, pour le casting (D-pad haut depuis
     * Films similaires, ou bas depuis « + Lire la suite »). */
    private fun restoreCastFocus(): Boolean =
        restoreRowFocus(binding.rvCast, lastCastFocusedPosition)

    /** TV : haut D-pad depuis le casting — Réalisé par s'il est visible, sinon
     * « + Lire la suite », sinon recherche de focus par défaut. */
    private fun focusAboveCast(): Boolean = when {
        binding.llDirector.visibility == View.VISIBLE -> binding.tvDirector.requestFocus()
        binding.tvOverviewToggle.visibility == View.VISIBLE -> binding.tvOverviewToggle.requestFocus()
        else -> false
    }

    /** TV : restaure la dernière icône focusée (Lecture/Favori/…) en y revenant
     * depuis Films similaires (D-pad bas) — sinon focus toujours la même icône
     * (la plus proche géométriquement) quelle que soit celle quittée avant. */
    private fun restoreIconRowFocus(): Boolean {
        val v = lastIconRowFocused ?: return false
        if (v.visibility != View.VISIBLE) return false
        v.requestFocus()
        return true
    }

    private fun restoreRowFocus(rv: RecyclerView, lastPosition: Int): Boolean {
        if (rv.visibility != View.VISIBLE || rv.adapter?.itemCount == 0) return false
        val pos = lastPosition.coerceIn(0, (rv.adapter?.itemCount ?: 1) - 1)
        val lm = rv.layoutManager as? LinearLayoutManager ?: return false
        lm.findViewByPosition(pos)?.let { it.requestFocus(); return true }
        // Carte pas encore mesurée (hors de la zone visible) : scroll puis focus au
        // prochain passage de layout.
        lm.scrollToPosition(pos)
        rv.post { lm.findViewByPosition(pos)?.requestFocus() }
        return true
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

    /** Aperçu léger (synopsis + bande-annonce + icône +/✓) sur la carte elle-même
     * (jaquette/titre) — mobile ET TV, comme la PWA (openWorkPreview). Sur mobile
     * le badge ✓/+ SUR LA CARTE (SimilarWorkAdapter.showBadge=true) reste un raccourci
     * direct sans passer par l'aperçu ; sur TV il est masqué (showBadge=false), l'icône
     * ICI est alors le seul moyen d'ajouter sans quitter le focus D-pad de la carte. */
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
        val trailerRing = view.findViewById<com.nicotv.iptv.ui.common.RotatingBorderView>(R.id.trailer_ring)
        val btnAddWrap = view.findViewById<View>(R.id.btn_preview_add_wrap)
        val btnAdd = view.findViewById<TextView>(R.id.btn_preview_add)
        val addRing = view.findViewById<com.nicotv.iptv.ui.common.RotatingBorderView>(R.id.add_ring)

        // Poster (pas la grande affiche/backdrop) : même taille et source que la
        // fiche film normale (card_poster/iv_poster, activity_detail.xml).
        ivPoster.load(work.posterUrl.ifBlank { work.backdropUrl }.ifBlank { null }) {
            crossfade(true)
            placeholder(R.drawable.ic_movie_placeholder)
        }
        if (work.year.isNotBlank()) { tvYear.visibility = View.VISIBLE; tvYear.text = work.year }
        if (work.rating > 0f) {
            tvRating.visibility = View.VISIBLE
            tvRating.text = "★ %.1f".format(work.rating)
        }
        tvOverview.text = work.overview.ifBlank { getString(R.string.no_overview) }

        // Mobile : en-tête custom (titre + ✕), pas de titre/bouton OK système — comme
        // la PWA. TV : garde setTitle/setNegativeButton (OK, D-pad-friendly).
        val builder = AlertDialog.Builder(this).setView(view)
        if (tvMode) builder.setTitle(work.title).setNegativeButton(android.R.string.ok, null)
        val dialog = builder.create()
        dialog.show()
        if (!tvMode) {
            previewHeader.visibility = View.VISIBLE
            tvPreviewTitle.text = work.title
            btnClose.setOnClickListener { dialog.dismiss() }
        }
        // Moins large que l'écran (pas MATCH_PARENT) — la hauteur suit le contenu
        // (poster réduit à 110x165 pour tenir sans scroll dans la plupart des cas).
        val width = (resources.displayMetrics.widthPixels * 0.8).toInt()
        dialog.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog)

        // Genres + durée : mêmes infos que la fiche film normale (tous les détails).
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

        // Icône +/✓ : visible sur mobile ET TV maintenant (avant : TV seulement,
        // le mobile devait fermer l'aperçu et taper le badge sur la carte —
        // incohérent avec la PWA, qui l'affiche partout dans son aperçu).
        btnAdd.text = if (work.owned) "✓" else "+"
        if (tvMode) {
            // Sur TV : pas de rond coloré (bg_badge_add/owned) — juste le glyphe, plus
            // gros, avec l'anneau tournant comme seul repère de focus (cohérent avec
            // bande-annonce à côté, qui n'a pas non plus de fond coloré).
            btnAdd.background = null
            btnAdd.textSize = 22f
            val iconSizePx = (32 * resources.displayMetrics.density).toInt()
            btnAdd.layoutParams = btnAdd.layoutParams.also { it.width = iconSizePx; it.height = iconSizePx }
            val wrapSizePx = (48 * resources.displayMetrics.density).toInt()
            btnAddWrap.layoutParams = btnAddWrap.layoutParams.also { it.width = wrapSizePx; it.height = wrapSizePx }
        } else {
            btnAdd.setBackgroundResource(if (work.owned) R.drawable.bg_badge_owned else R.drawable.bg_badge_add)
        }
        btnAddWrap.setOnClickListener {
            dialog.dismiss()
            handleWorkClick(work)
        }
        btnAddWrap.setOnFocusChangeListener { _, hasFocus ->
            addRing.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
            if (hasFocus) addRing.startAnim() else addRing.stopAnim()
        }
    }

    // ────────────────────────────────────────────────────────────────────────

    private fun play(movie: com.nicotv.iptv.domain.model.Movie, resume: Boolean) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_MOVIE_ID, movie.id)
            putExtra(PlayerActivity.EXTRA_STREAM_URL, movie.streamUrl)
            putExtra(PlayerActivity.EXTRA_TITLE, movie.title)
            putExtra(PlayerActivity.EXTRA_RESUME, resume)
        })
    }


    /** Même formule que MoviesActivity.computeSpanCount() : jaquettes de la
     * filmographie acteur à la même taille que le mur Films (~112dp/cellule),
     * pas une grille à spanCount fixe qui donne des cellules de taille
     * différente selon la largeur d'écran. */
    private fun filmographySpanCount(): Int {
        val screenWidthDp = resources.configuration.screenWidthDp
        return (screenWidthDp / 112).coerceIn(6, 10)
    }

    companion object {
        const val EXTRA_MOVIE_ID = "movie_id"
    }
}
