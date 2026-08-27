package com.nicotv.iptv.ui.movies

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asLiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nicotv.iptv.IptvApplication
import com.nicotv.iptv.R
import com.nicotv.iptv.data.database.entity.DownloadEntity
import com.nicotv.iptv.databinding.ActivityMoviesBinding
import com.nicotv.iptv.domain.model.Movie
import com.nicotv.iptv.ui.common.PosterAdapter
import com.nicotv.iptv.ui.detail.DetailActivity
import kotlinx.coroutines.launch

class MoviesActivity : com.nicotv.iptv.ui.common.BaseActivity() {

    private lateinit var binding: ActivityMoviesBinding
    private lateinit var viewModel: MoviesViewModel
    private lateinit var adapter: PosterAdapter

    // Présence admin.nicotv.ovh (« qui regarde quoi ») : écran courant hors lecture.
    override fun onResume() {
        super.onResume()
        (application as IptvApplication).reportScreen("Films")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMoviesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel = ViewModelProvider(this)[MoviesViewModel::class.java]

        // Pastille « N nouveautés » de l'accueil : ouvre cette même liste mais
        // filtrée sur les nouveautés (comme new-films côté PWA), pas la liste
        // entière.
        if (intent.getBooleanExtra(EXTRA_NEW_ONLY, false)) {
            viewModel.newOnly.value = true
            binding.tvSectionTitle.text = getString(R.string.title_new_movies)
        }

        adapter = PosterAdapter(
            onClick = { movie ->
                startActivity(Intent(this, DetailActivity::class.java).apply {
                    putExtra(DetailActivity.EXTRA_MOVIE_ID, movie.id)
                })
            },
            showDownloadBadge = true,
            onDownloadClick = { movie -> onPosterDownloadClick(movie) }
        )

        binding.rvPosters.apply {
            layoutManager = GridLayoutManager(this@MoviesActivity, computeSpanCount())
            adapter = this@MoviesActivity.adapter
            setHasFixedSize(false)
            // Ferme le clavier dès qu'on défile / touche le mur d'affiches
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) hideKeyboard()
                }
            })
            setOnTouchListener { v, _ -> v.performClick(); hideKeyboard(); false }
        }

        binding.btnBack.setOnClickListener { finish() }
        // Icône + anneau blanc tournant (RotatingBorderView), même pattern que la
        // fiche détail (avant : bouton texte "Retour" avec juste un zoom).
        binding.btnBack.setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) 1.25f else 1f)
                .scaleY(if (hasFocus) 1.25f else 1f)
                .setDuration(150).start()
            v.z = if (hasFocus) 10f else 0f
            binding.btnBackRing.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
            if (hasFocus) binding.btnBackRing.startAnim() else binding.btnBackRing.stopAnim()
        }
        binding.btnClear.setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) 1.2f else 1f)
                .scaleY(if (hasFocus) 1.2f else 1f)
                .setDuration(150).start()
        }

        // Croix : efface le texte et redonne le focus au champ
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
                viewModel.searchQuery.value = s?.toString() ?: ""
            }
        })

        // Loupe du clavier → ferme le clavier
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); true } else false
        }

        binding.progressLoading.visibility = View.VISIBLE
        viewModel.filteredMovies.observe(this) { movies ->
            binding.progressLoading.visibility = View.GONE
            adapter.submitList(movies)
            binding.tvCount.text = "${movies.size}"
            binding.tvEmpty.visibility = if (movies.isEmpty()) View.VISIBLE else View.GONE
            binding.rvPosters.visibility = if (movies.isEmpty()) View.GONE else View.VISIBLE
        }

        // Badge de téléchargement sur chaque affiche (mode avion).
        (application as IptvApplication).downloadRepository.getAllFlow().asLiveData().observe(this) { list ->
            adapter.setDownloads(
                list.filter { it.type == DownloadEntity.TYPE_MOVIE }.associateBy { it.key }
            )
        }

        // Hors-ligne (mode avion) : Films n'affiche que les téléchargements locaux
        // (cf. MoviesViewModel.filteredMovies) — message vide dédié pour l'expliquer.
        (application as IptvApplication).isOnline.observe(this) { online ->
            binding.tvEmpty.text = getString(if (online) R.string.home_empty_title else R.string.offline_empty_movies)
        }

        // Préchauffe le cache casting en tâche de fond → la recherche par acteur
        // devient utile sans attendre l'ouverture individuelle de chaque fiche.
        viewModel.prefetchCast()
    }

    /** Tap sur le badge de téléchargement d'une affiche (mur de films) — le badge
     * n'est visible que si le film est déjà téléchargé, donc toujours une suppression. */
    private fun onPosterDownloadClick(movie: Movie) = confirmDeleteDownload(movie)

    private fun confirmDeleteDownload(movie: Movie) {
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

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val view = currentFocus ?: binding.root
        imm.hideSoftInputFromWindow(view.windowToken, 0)
        binding.etSearch.clearFocus()
    }

    private fun computeSpanCount(): Int {
        val screenWidthDp = resources.configuration.screenWidthDp
        // ~6 affiches par ligne sur un téléphone, plus sur grand écran
        val posterWidthDp = 112
        return (screenWidthDp / posterWidthDp).coerceIn(6, 10)
    }

    companion object {
        const val EXTRA_NEW_ONLY = "extra_new_only"
    }
}
