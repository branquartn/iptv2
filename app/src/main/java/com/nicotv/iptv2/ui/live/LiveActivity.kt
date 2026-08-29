package com.nicotv.iptv2.ui.live

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.nicotv.iptv2.R
import com.nicotv.iptv2.databinding.ActivityLiveBinding
import com.nicotv.iptv2.player.PlayerActivity
import com.nicotv.iptv2.ui.common.BaseActivity
import com.nicotv.iptv2.ui.common.CategorySidebarAdapter

@UnstableApi
class LiveActivity : BaseActivity() {

    private lateinit var binding: ActivityLiveBinding
    private lateinit var viewModel: LiveViewModel
    private lateinit var channelAdapter: ChannelGridAdapter
    private lateinit var categoryAdapter: CategorySidebarAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLiveBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel = ViewModelProvider(this)[LiveViewModel::class.java]

        binding.btnBack.setOnClickListener { finish() }
        applyRing(binding.btnBack, binding.btnBackRing, 1.25f)
        applyRing(binding.btnFavoritesFilter, binding.btnFavoritesFilterRing, 1.2f)

        // Mosaïque de logos (comme IPTV Smarters Pro), pas une liste — tap = lecture,
        // appui long = favori (pas de bouton dédié sur la tuile, place limitée).
        channelAdapter = ChannelGridAdapter(
            onClick = { channel ->
                startActivity(Intent(this, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_STREAM_URL, channel.streamUrl)
                    putExtra(PlayerActivity.EXTRA_TITLE, channel.name)
                })
            },
            onToggleFavorite = { channel -> viewModel.toggleFavorite(channel) }
        )
        val gridLayoutManager = GridLayoutManager(this, computeSpanCount())
        binding.rvChannels.layoutManager = gridLayoutManager
        binding.rvChannels.adapter = channelAdapter
        binding.rvChannels.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                // ⚠️ Pagination (30/08/2026) — cf. MoviesActivity, même
                // déclenchement (moins de 2 rangées avant la fin), no-op côté
                // ViewModel si recherche/catégorie/dernière page.
                if (dy <= 0) return
                val total = gridLayoutManager.itemCount
                val lastVisible = gridLayoutManager.findLastVisibleItemPosition()
                if (total > 0 && lastVisible >= total - gridLayoutManager.spanCount * 2) {
                    viewModel.loadNextPage()
                }
            }
        })

        categoryAdapter = CategorySidebarAdapter(getString(R.string.category_all)) { category -> viewModel.selectedCategory.value = category }
        binding.rvCategories.layoutManager = LinearLayoutManager(this)
        binding.rvCategories.adapter = categoryAdapter

        binding.btnFavoritesFilter.setOnClickListener {
            val on = viewModel.favoritesOnly.value != true
            viewModel.favoritesOnly.value = on
            binding.ivFavoritesFilter.imageTintList = ContextCompat.getColorStateList(
                this, if (on) R.color.favorite_yellow else R.color.text_primary
            )
        }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { viewModel.searchQuery.value = s?.toString() ?: "" }
        })
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); true } else false
        }

        viewModel.categories.observe(this) { cats -> categoryAdapter.submitList(cats) }
        // ⚠️ Surbrillance de la catégorie ouverte par défaut (30/08/2026, cf.
        // util.pickDefaultCategory) — setSelectedSilently, pas `selected =` :
        // la sélection vient DÉJÀ du ViewModel, la repasser par le setter
        // public rappellerait onSelect et relancerait un chargement en double.
        viewModel.selectedCategory.observe(this) { cat -> categoryAdapter.setSelectedSilently(cat) }

        // ⚠️ Cf. MoviesActivity, même correctif 29/08/2026 ("la première fois
        // que je vais dans Chaînes il ne charge pas") — le spinner reste
        // affiché tant que isReady n'est pas true, pas juste tant que la liste
        // a émis quelque chose (sa toute première valeur peut être vide alors
        // que la première page n'est pas encore arrivée).
        val render = androidx.lifecycle.MediatorLiveData<Unit>()
        render.addSource(viewModel.channels) { render.value = Unit }
        render.addSource(viewModel.isReady) { render.value = Unit }
        render.observe(this) {
            val channels = viewModel.channels.value ?: emptyList()
            val ready = viewModel.isReady.value == true
            binding.progressLoading.visibility = if (!ready) View.VISIBLE else View.GONE
            channelAdapter.submitList(channels)
            binding.tvEmpty.visibility = if (ready && channels.isEmpty()) View.VISIBLE else View.GONE
            binding.rvChannels.visibility = if (!ready || channels.isEmpty()) View.GONE else View.VISIBLE
            // Même astuce que l'écran Favoris (29/08/2026, demande explicite
            // "aussi avoir le tips des favoris dans les favoris des chaînes")
            // — vide à cause du filtre favoris "appui long" = pas découvrable
            // seul, vs. vide à cause d'une recherche/catégorie = message
            // générique.
            binding.tvEmpty.text = if (viewModel.favoritesOnly.value == true) {
                getString(R.string.favorites_channels_tip)
            } else {
                getString(R.string.live_empty_title)
            }
        }
    }

    // ⚠️ Cf. MoviesActivity.onResume — la pagination n'est plus réactive à la
    // table favoris ; en plus, si le filtre "favoris uniquement" est actif, le
    // ViewModel relance un chargement complet (une tuile retirée des favoris
    // doit disparaître, pas seulement perdre son étoile).
    override fun onResume() {
        super.onResume()
        viewModel.refreshFavoriteStates()
    }

    /** Cf. MoviesActivity.computeSpanCount — même sidebar catégories à déduire.
     * Tuile un peu plus large qu'une affiche (logo + nom, pas un poster vertical). */
    private fun computeSpanCount(): Int {
        val screenWidthDp = resources.configuration.screenWidthDp
        val sidebarDp = 210
        val tileWidthDp = 130
        return ((screenWidthDp - sidebarDp) / tileWidthDp).coerceIn(3, 8)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val view = currentFocus ?: binding.root
        imm.hideSoftInputFromWindow(view.windowToken, 0)
        binding.etSearch.clearFocus()
    }

    private fun applyRing(target: View, ring: com.nicotv.iptv2.ui.common.RotatingBorderView, scale: Float) {
        target.setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) scale else 1f).scaleY(if (hasFocus) scale else 1f).setDuration(150).start()
            ring.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
            if (hasFocus) ring.startAnim() else ring.stopAnim()
        }
    }
}
