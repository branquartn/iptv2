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
import androidx.recyclerview.widget.RecyclerView
import com.nicotv.iptv2.IptvApplication
import com.nicotv.iptv2.R
import com.nicotv.iptv2.databinding.ActivitySeriesBinding
import com.nicotv.iptv2.ui.common.PosterAdapter

@UnstableApi
class SeriesActivity : com.nicotv.iptv2.ui.common.BaseActivity() {

    private lateinit var binding: ActivitySeriesBinding
    private lateinit var viewModel: SeriesViewModel
    private lateinit var adapter: PosterAdapter

    // Présence admin.nicotv.ovh (« qui regarde quoi ») : écran courant hors lecture.
    override fun onResume() {
        super.onResume()
        (application as IptvApplication).reportScreen("Séries")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySeriesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel = ViewModelProvider(this)[SeriesViewModel::class.java]

        // Pastille « N nouveautés » de l'accueil : ouvre cette même liste mais
        // filtrée sur les nouveautés (comme new-series côté PWA).
        if (intent.getBooleanExtra(EXTRA_NEW_ONLY, false)) {
            viewModel.newOnly.value = true
            binding.tvSectionTitle.text = getString(R.string.title_new_series)
        }

        adapter = PosterAdapter(onClick = { item ->
            startActivity(Intent(this, SeriesDetailActivity::class.java).apply {
                putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, item.id)
                putExtra(SeriesDetailActivity.EXTRA_SERIES_TITLE, item.title)
                putExtra(SeriesDetailActivity.EXTRA_POSTER_URL, item.posterUrl)
            })
        })

        binding.rvPosters.apply {
            layoutManager = GridLayoutManager(this@SeriesActivity, computeSpanCount())
            adapter = this@SeriesActivity.adapter
            setHasFixedSize(false)
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

        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); true } else false
        }

        binding.progressLoading.visibility = View.VISIBLE
        viewModel.filteredSeries.observe(this) { series ->
            binding.progressLoading.visibility = View.GONE
            adapter.submitList(series)
            binding.tvCount.text = "${series.size}"
            binding.tvEmpty.visibility = if (series.isEmpty()) View.VISIBLE else View.GONE
            binding.rvPosters.visibility = if (series.isEmpty()) View.GONE else View.VISIBLE
        }

        // Hors-ligne (mode avion) : Séries n'affiche que les séries ayant au moins un
        // épisode téléchargé (cf. SeriesViewModel.filteredSeries).
        (application as IptvApplication).isOnline.observe(this) { online ->
            binding.tvEmpty.text = getString(if (online) R.string.series_empty_title else R.string.offline_empty_series)
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
        val posterWidthDp = 112
        return (screenWidthDp / posterWidthDp).coerceIn(6, 10)
    }

    companion object {
        const val EXTRA_NEW_ONLY = "extra_new_only"
    }
}
