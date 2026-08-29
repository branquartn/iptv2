package com.nicotv.iptv2.ui.movies

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nicotv.iptv2.R
import com.nicotv.iptv2.databinding.ActivityMoviesBinding
import com.nicotv.iptv2.ui.common.CategorySidebarAdapter
import com.nicotv.iptv2.ui.common.PosterAdapter
import com.nicotv.iptv2.ui.detail.DetailActivity

class MoviesActivity : com.nicotv.iptv2.ui.common.BaseActivity() {

    private lateinit var binding: ActivityMoviesBinding
    private lateinit var viewModel: MoviesViewModel
    private lateinit var adapter: PosterAdapter
    private lateinit var categoryAdapter: CategorySidebarAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMoviesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel = ViewModelProvider(this)[MoviesViewModel::class.java]

        adapter = PosterAdapter(onClick = { movie ->
            startActivity(Intent(this, DetailActivity::class.java).apply {
                putExtra(DetailActivity.EXTRA_MOVIE_ID, movie.id)
            })
        })

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

        categoryAdapter = CategorySidebarAdapter(getString(R.string.category_all)) { category -> viewModel.selectedCategory.value = category }
        binding.rvCategories.layoutManager = LinearLayoutManager(this)
        binding.rvCategories.adapter = categoryAdapter
        viewModel.categories.observe(this) { cats -> categoryAdapter.submitList(cats) }

        binding.btnBack.setOnClickListener { finish() }
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

        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); true } else false
        }

        // ⚠️ Rendu recalculé sur CHAQUE émission de filteredMovies OU isReady
        // (corrigé 29/08/2026, signalé "la première fois que je vais dans
        // Films il ne charge pas") — avant, le spinner se cachait dès la
        // toute première émission de filteredMovies, qui peut être une liste
        // vide (valeur de départ de moviesFlow, cf. PlaylistRepository)
        // arrivée AVANT que la vraie requête Room (des dizaines de milliers
        // de lignes) n'ait fini de s'exécuter en arrière-plan : "Aucun titre
        // trouvé" s'affichait donc à tort le temps que le catalogue arrive,
        // perçu comme "ça ne charge pas" plutôt que "ça charge encore".
        // `MediatorLiveData` recombine les deux à chaque changement de l'un
        // ou l'autre — `isReady` passe à `true` une seule fois (la requête ne
        // "redevient" jamais non-répondue), donc pas de flicker après coup.
        val render = androidx.lifecycle.MediatorLiveData<Unit>()
        render.addSource(viewModel.filteredMovies) { render.value = Unit }
        render.addSource(viewModel.isReady) { render.value = Unit }
        render.observe(this) {
            val movies = viewModel.filteredMovies.value ?: emptyList()
            val ready = viewModel.isReady.value == true
            binding.progressLoading.visibility = if (!ready) View.VISIBLE else View.GONE
            adapter.submitList(movies)
            binding.tvCount.text = "${movies.size}"
            binding.tvEmpty.visibility = if (ready && movies.isEmpty()) View.VISIBLE else View.GONE
            binding.rvPosters.visibility = if (!ready || movies.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val view = currentFocus ?: binding.root
        imm.hideSoftInputFromWindow(view.windowToken, 0)
        binding.etSearch.clearFocus()
    }

    private fun computeSpanCount(): Int {
        val screenWidthDp = resources.configuration.screenWidthDp
        // ~6 affiches par ligne sur un téléphone, plus sur grand écran — moins
        // large que l'écran total depuis l'ajout de la sidebar catégories
        // (180dp + séparateur + paddings, cf. activity_movies.xml).
        val sidebarDp = 210
        val posterWidthDp = 112
        return ((screenWidthDp - sidebarDp) / posterWidthDp).coerceIn(4, 10)
    }
}
