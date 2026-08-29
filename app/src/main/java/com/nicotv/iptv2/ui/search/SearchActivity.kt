package com.nicotv.iptv2.ui.search

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.nicotv.iptv2.databinding.ActivitySearchBinding
import com.nicotv.iptv2.domain.model.Movie
import com.nicotv.iptv2.player.PlayerActivity
import com.nicotv.iptv2.ui.common.BaseActivity
import com.nicotv.iptv2.ui.common.PosterAdapter
import com.nicotv.iptv2.ui.detail.DetailActivity
import com.nicotv.iptv2.ui.live.ChannelAdapter
import com.nicotv.iptv2.ui.series.SeriesDetailActivity

/** Recherche locale (par titre) dans le catalogue déjà chargé — pas de TMDb, pas
 * de backend, on ne cherche que dans ce que la playlist contient déjà. */
@UnstableApi
class SearchActivity : BaseActivity() {

    private lateinit var binding: ActivitySearchBinding
    private lateinit var viewModel: SearchViewModel
    private lateinit var posterAdapter: PosterAdapter
    private lateinit var channelAdapter: ChannelAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel = ViewModelProvider(this)[SearchViewModel::class.java]

        binding.btnBack.setOnClickListener { finish() }
        binding.btnBack.setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) 1.25f else 1f).scaleY(if (hasFocus) 1.25f else 1f).setDuration(150).start()
            binding.btnBackRing.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
            if (hasFocus) binding.btnBackRing.startAnim() else binding.btnBackRing.stopAnim()
        }

        posterAdapter = PosterAdapter(onClick = { item ->
            if (item.type == Movie.Type.SERIES) {
                startActivity(Intent(this, SeriesDetailActivity::class.java).apply {
                    putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, item.id)
                    putExtra(SeriesDetailActivity.EXTRA_SERIES_TITLE, item.displayTitle)
                    putExtra(SeriesDetailActivity.EXTRA_POSTER_URL, item.posterUrl)
                })
            } else {
                startActivity(Intent(this, DetailActivity::class.java).apply {
                    putExtra(DetailActivity.EXTRA_MOVIE_ID, item.id)
                })
            }
        })
        binding.rvMoviesSeries.layoutManager = GridLayoutManager(this, computeSpanCount())
        binding.rvMoviesSeries.adapter = posterAdapter

        channelAdapter = ChannelAdapter(
            onClick = { channel ->
                startActivity(Intent(this, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_STREAM_URL, channel.streamUrl)
                    putExtra(PlayerActivity.EXTRA_TITLE, channel.name)
                })
            },
            onToggleFavorite = {}
        )
        binding.rvChannels.layoutManager = LinearLayoutManager(this)
        binding.rvChannels.adapter = channelAdapter

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { viewModel.search(s?.toString() ?: "") }
        })
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); true } else false
        }
        binding.etSearch.requestFocus()

        viewModel.results.observe(this) { results ->
            binding.tvEmpty.visibility = if (results.isEmpty) View.VISIBLE else View.GONE

            binding.tvMoviesHeader.visibility = if (results.movies.isEmpty() && results.series.isEmpty()) View.GONE else View.VISIBLE
            posterAdapter.submitList(results.movies + results.series)

            binding.tvChannelsHeader.visibility = if (results.channels.isEmpty()) View.GONE else View.VISIBLE
            channelAdapter.submitList(results.channels)
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
        return (screenWidthDp / 112).coerceIn(6, 10)
    }
}
