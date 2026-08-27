package com.nicotv.iptv.ui.search

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.nicotv.iptv.IptvApplication
import com.nicotv.iptv.R
import com.nicotv.iptv.data.network.model.TmdbCastMember
import com.nicotv.iptv.databinding.ActivitySearchBinding
import com.nicotv.iptv.domain.model.OpenTarget
import com.nicotv.iptv.domain.model.SimilarWork
import com.nicotv.iptv.ui.common.BaseActivity
import com.nicotv.iptv.ui.common.isTvDevice
import com.nicotv.iptv.ui.detail.CastAdapter
import com.nicotv.iptv.ui.detail.DetailActivity
import com.nicotv.iptv.ui.detail.SimilarWorkAdapter
import com.nicotv.iptv.ui.series.SeriesDetailActivity
import kotlinx.coroutines.launch

class SearchActivity : BaseActivity() {

    private lateinit var binding: ActivitySearchBinding
    private lateinit var viewModel: SearchViewModel
    private lateinit var gridAdapter: SimilarWorkAdapter
    // TV/Shield/Fire TV : badge ✓/+ masqué sur les cartes filmographie (cf. DetailActivity).
    private val tvMode by lazy { isTvDevice() }

    // Présence admin.nicotv.ovh (« qui regarde quoi ») : écran courant hors lecture.
    override fun onResume() {
        super.onResume()
        (application as IptvApplication).reportScreen("Recherche")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel = ViewModelProvider(this)[SearchViewModel::class.java]

        // Fermeture clavier au défilement, commun aux deux modes.
        binding.rvResults.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) hideKeyboard()
            }
        })
        binding.rvResults.setOnTouchListener { v, _ -> v.performClick(); hideKeyboard(); false }

        // Grille de jaquettes (badge type Film/Série + badge ✓/+), comme la PWA
        // (workCardHTML/wireWorkCard) — mobile et TV, même composant que la
        // filmographie acteur (SimilarWorkAdapter). Badge « + »/« ✓ » : ajoute ou
        // ouvre si déjà possédé. Clic sur la jaquette : toujours la fiche aperçu
        // (synopsis + bande-annonce), jamais d'ajout direct — sur TV cette fiche
        // affiche en plus le bouton Ajouter/Ouvrir (badge masqué, showBadge=false).
        gridAdapter = SimilarWorkAdapter(
            onBadgeClick = { handleWorkClick(it) },
            onPreviewClick = { showWorkPreview(it) },
            gridMode = true,
            showBadge = !tvMode,
            showTypeBadge = true
        )
        binding.rvResults.layoutManager = GridLayoutManager(this, filmographySpanCount())
        binding.rvResults.adapter = gridAdapter
        // Marge pour le débordement du zoom focus (cf. activity_detail rv_similar).
        val pad = (8 * resources.displayMetrics.density).toInt()
        binding.rvResults.setPadding(pad, pad, pad, binding.rvResults.paddingBottom)
        viewModel.results.observe(this) { results ->
            gridAdapter.submitList(results)
            binding.tvEmpty.visibility = if (results.isEmpty() && !binding.etSearch.text.isNullOrBlank()) View.VISIBLE else View.GONE
        }

        // Personnes trouvées par la recherche (comme la PWA) : clic → fiche/filmographie.
        // known_for_department (isDirector) distingue réalisateur/acteur — sinon un
        // réalisateur cherché ici tombait sur ses rôles d'acteur (souvent hors-sujet)
        // au lieu de ce qu'il a réalisé (cf. DetailActivity.showActorDialog(asDirector)).
        var peopleIsDirector = mapOf<Int, Boolean>()
        val peopleAdapter = CastAdapter(onClick = { showActorDialog(it, peopleIsDirector[it.id] == true) })
        binding.rvPeople.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvPeople.adapter = peopleAdapter
        viewModel.people.observe(this) { people ->
            peopleIsDirector = people.associate { it.id to it.isDirector }
            val members = people.map {
                TmdbCastMember(id = it.id, name = it.displayTitle,
                    character = deptLabel(it.knownForDepartment), profilePath = it.profilePath)
            }
            peopleAdapter.submitList(members)
            binding.tvPeopleTitle.visibility = if (members.isNotEmpty()) View.VISIBLE else View.GONE
            binding.rvPeople.visibility = if (members.isNotEmpty()) View.VISIBLE else View.GONE
        }

        binding.btnBack.setOnClickListener { finish() }
        // Icône + anneau blanc tournant (RotatingBorderView), même pattern que la
        // fiche détail (avant : bouton texte "Retour").
        binding.btnBack.setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) 1.25f else 1f)
                .scaleY(if (hasFocus) 1.25f else 1f)
                .setDuration(150).start()
            v.z = if (hasFocus) 10f else 0f
            binding.btnBackRing.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
            if (hasFocus) binding.btnBackRing.startAnim() else binding.btnBackRing.stopAnim()
        }

        // Bouton croix : efface le texte et redonne le focus au champ
        binding.btnClear.setOnClickListener {
            binding.etSearch.setText("")
            binding.etSearch.requestFocus()
        }

        binding.searchRing.stopAnim()
        binding.etSearch.setOnFocusChangeListener { _, hasFocus ->
            binding.searchRing.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
            if (hasFocus) binding.searchRing.startAnim() else binding.searchRing.stopAnim()
        }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                binding.btnClear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                viewModel.search(s?.toString() ?: "")
            }
        })

        // Loupe du clavier → ferme le clavier
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); true } else false
        }

        viewModel.isLoading.observe(this) { loading ->
            binding.progressLoading.visibility = if (loading) View.VISIBLE else View.GONE
        }

        viewModel.addResult.observe(this) { msg ->
            if (msg != null) {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                viewModel.addResult.value = null
            }
        }

        binding.etSearch.requestFocus()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val view = currentFocus ?: binding.root
        imm.hideSoftInputFromWindow(view.windowToken, 0)
        binding.etSearch.clearFocus()
    }

    // ── Fiche acteur (même mécanique que DetailActivity — casting/similaires) ──

    /** Département TMDb → libellé FR affiché sous le nom dans la liste de résultats
     * (même mapping que la PWA, DEPT_LABEL dans app.js). */
    private fun deptLabel(dept: String): String = when (dept) {
        "Directing" -> "Réalisateur"
        "Acting" -> "Acteur"
        "Writing" -> "Scénariste"
        "Production" -> "Producteur"
        "Camera" -> "Image"
        "Sound" -> "Son"
        "Editing" -> "Montage"
        else -> dept
    }

    /** Déjà possédé → ouvre sa fiche ; sinon ajoute à la file de téléchargement
     * (Toast via viewModel.addResult, déjà observé dans onCreate). */
    private fun handleWorkClick(work: SimilarWork) {
        lifecycleScope.launch {
            when (val target = viewModel.resolveOrAdd(work)) {
                is OpenTarget.MovieTarget -> startActivity(
                    Intent(this@SearchActivity, DetailActivity::class.java)
                        .putExtra(DetailActivity.EXTRA_MOVIE_ID, target.movieId)
                )
                is OpenTarget.SeriesTarget -> startActivity(
                    Intent(this@SearchActivity, SeriesDetailActivity::class.java)
                        .putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, target.seriesId)
                        .putExtra(SeriesDetailActivity.EXTRA_SERIES_TITLE, target.title)
                )
                null -> Unit   // ajouté : le Toast vient de addResult
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
        // Réalisateur : liste des films réalisés (crew), pas la filmographie d'acteur
        // (même logique que DetailActivity.showActorDialog).
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
        // WRAP_CONTENT : plafond 85 % + scroll gérés par MaxHeightScrollView
        // (racine du layout) — contenu court = dialog court.
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        // Fond rectangulaire système gris (pas de coin arrondi) remplacé par notre
        // fond de dialog habituel — comme dialog_quit.xml.
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog)
        // Filmographie : un seul scroll unifié (photo+bio+grille), comme la PWA.
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

    private fun openYoutube(videoKey: String) {
        val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoKey"))
        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoKey"))
        try {
            startActivity(appIntent)
        } catch (e: ActivityNotFoundException) {
            startActivity(webIntent)
        }
    }

    /** Aperçu léger (synopsis + bande-annonce + icône +/✓) sur la carte elle-même —
     * mobile ET TV, comme la PWA. Sur mobile le badge ✓/+ SUR LA CARTE reste un
     * raccourci direct ; sur TV il est masqué, l'icône ICI est le seul moyen
     * d'ajouter sans quitter le focus D-pad de la carte. */
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
        // fiche film normale.
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
                    Toast.makeText(this@SearchActivity, getString(R.string.not_available_yet), Toast.LENGTH_SHORT).show()
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
        btnAddWrap.setOnFocusChangeListener { _, hasFocus ->
            addRing.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
            if (hasFocus) addRing.startAnim() else addRing.stopAnim()
        }
        btnAddWrap.setOnClickListener {
            dialog.dismiss()
            handleWorkClick(work)
        }
    }

    /** Même formule que MoviesActivity.computeSpanCount() : jaquettes de la
     * filmographie acteur à la même taille que le mur Films (~112dp/cellule). */
    private fun filmographySpanCount(): Int {
        val screenWidthDp = resources.configuration.screenWidthDp
        return (screenWidthDp / 112).coerceIn(6, 10)
    }
}
