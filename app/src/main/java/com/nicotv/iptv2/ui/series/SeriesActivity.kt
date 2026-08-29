package com.nicotv.iptv2.ui.series

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nicotv.iptv2.R
import com.nicotv.iptv2.databinding.ActivitySeriesBinding
import com.nicotv.iptv2.ui.common.CategorySidebarAdapter
import com.nicotv.iptv2.ui.common.PosterAdapter

@UnstableApi
class SeriesActivity : com.nicotv.iptv2.ui.common.BaseActivity() {

    private lateinit var binding: ActivitySeriesBinding
    private lateinit var viewModel: SeriesViewModel
    private lateinit var adapter: PosterAdapter
    private lateinit var categoryAdapter: CategorySidebarAdapter

    /** Défilement accéléré de la sidebar à la télécommande — cf.
     * ui.common.CategoryFastScroll (2 appuis rapides = saut de plusieurs
     * catégories). Initialisé une fois rv_categories câblé. */
    private var categoryFastScroll: com.nicotv.iptv2.ui.common.CategoryFastScroll? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySeriesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel = ViewModelProvider(this)[SeriesViewModel::class.java]

        adapter = PosterAdapter(onClick = { item ->
            startActivity(Intent(this, SeriesDetailActivity::class.java).apply {
                putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, item.id)
                putExtra(SeriesDetailActivity.EXTRA_SERIES_TITLE, item.displayTitle)
                putExtra(SeriesDetailActivity.EXTRA_POSTER_URL, item.posterUrl)
            })
        })

        val gridLayoutManager = GridLayoutManager(this@SeriesActivity, computeSpanCount())
        binding.rvPosters.apply {
            layoutManager = gridLayoutManager
            adapter = this@SeriesActivity.adapter
            setHasFixedSize(false)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) hideKeyboard()
                }
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    // ⚠️ Pagination (30/08/2026) — cf. MoviesActivity, même
                    // déclenchement (moins de 2 rangées avant la fin), no-op
                    // côté ViewModel si recherche/catégorie/dernière page.
                    if (dy <= 0) return
                    val total = gridLayoutManager.itemCount
                    val lastVisible = gridLayoutManager.findLastVisibleItemPosition()
                    if (total > 0 && lastVisible >= total - gridLayoutManager.spanCount * 2) {
                        viewModel.loadNextPage()
                    }
                }
            })
            setOnTouchListener { v, _ -> v.performClick(); hideKeyboard(); false }
        }

        categoryAdapter = CategorySidebarAdapter(getString(R.string.category_all)) { category -> viewModel.selectedCategory.value = category }
        binding.rvCategories.layoutManager = LinearLayoutManager(this)
        categoryFastScroll = com.nicotv.iptv2.ui.common.CategoryFastScroll(binding.rvCategories)
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

        // ⚠️ Cf. MoviesActivity, même correctif 29/08/2026 ("la première fois
        // que je vais dans Films/Séries il ne charge pas") — le spinner reste
        // affiché tant que isReady n'est pas true, pas juste tant que la liste
        // a émis quelque chose (sa toute première valeur peut être vide alors
        // que la première page n'est pas encore arrivée).
        val render = androidx.lifecycle.MediatorLiveData<Unit>()
        render.addSource(viewModel.series) { render.value = Unit }
        render.addSource(viewModel.isReady) { render.value = Unit }
        render.observe(this) {
            val series = viewModel.series.value ?: emptyList()
            val ready = viewModel.isReady.value == true
            binding.progressLoading.visibility = if (!ready) View.VISIBLE else View.GONE
            adapter.submitList(series)
            binding.tvCount.text = "${series.size}"
            binding.tvEmpty.visibility = if (ready && series.isEmpty()) View.VISIBLE else View.GONE
            binding.rvPosters.visibility = if (!ready || series.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    // ⚠️ Cf. MoviesActivity.onResume — la pagination n'est plus réactive à la
    // table favoris, un favori togglé depuis la fiche série ne se répercute
    // plus tout seul sur la grille déjà chargée.
    override fun onResume() {
        super.onResume()
        viewModel.refreshFavoriteStates()
    }


    // Cf. CategoryFastScroll : intercepté AVANT le traitement normal du focus,
    // sinon la vue bougerait d'une ligne en plus du saut.
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (categoryFastScroll?.handleKeyEvent(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val view = currentFocus ?: binding.root
        imm.hideSoftInputFromWindow(view.windowToken, 0)
        binding.etSearch.clearFocus()
    }

    private fun computeSpanCount(): Int {
        val screenWidthDp = resources.configuration.screenWidthDp
        // Cf. MoviesActivity.computeSpanCount — même sidebar catégories à déduire.
        val sidebarDp = 210
        val posterWidthDp = 112
        return ((screenWidthDp - sidebarDp) / posterWidthDp).coerceIn(4, 10)
    }
}
