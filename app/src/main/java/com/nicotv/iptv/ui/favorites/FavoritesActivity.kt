package com.nicotv.iptv.ui.favorites

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import com.nicotv.iptv.IptvApplication
import com.nicotv.iptv.R
import com.nicotv.iptv.databinding.ActivityMoviesBinding
import com.nicotv.iptv.domain.model.Movie
import com.nicotv.iptv.ui.common.BaseActivity
import com.nicotv.iptv.ui.common.PosterAdapter
import com.nicotv.iptv.ui.detail.DetailActivity
import com.nicotv.iptv.ui.series.SeriesDetailActivity

@UnstableApi
class FavoritesActivity : BaseActivity() {

    private lateinit var binding: ActivityMoviesBinding
    private lateinit var viewModel: FavoritesViewModel
    private lateinit var adapter: PosterAdapter

    // Présence admin.nicotv.ovh (« qui regarde quoi ») : écran courant hors lecture.
    override fun onResume() {
        super.onResume()
        (application as IptvApplication).reportScreen("Favoris")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMoviesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel = ViewModelProvider(this)[FavoritesViewModel::class.java]

        binding.tvSectionTitle.text = getString(R.string.nav_favorites)
        // Masque tout le conteneur de recherche (champ + croix) sur l'écran Favoris
        binding.searchBox.visibility = View.GONE

        adapter = PosterAdapter(onClick = { item ->
            if (item.type == Movie.Type.SERIES) {
                startActivity(Intent(this, SeriesDetailActivity::class.java).apply {
                    putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, item.id)
                    putExtra(SeriesDetailActivity.EXTRA_SERIES_TITLE, item.title)
                    putExtra(SeriesDetailActivity.EXTRA_POSTER_URL, item.posterUrl)
                })
            } else {
                startActivity(Intent(this, DetailActivity::class.java).apply {
                    putExtra(DetailActivity.EXTRA_MOVIE_ID, item.id)
                })
            }
        })

        binding.rvPosters.apply {
            layoutManager = GridLayoutManager(this@FavoritesActivity, computeSpanCount())
            adapter = this@FavoritesActivity.adapter
            setHasFixedSize(false)
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnBack.setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) 1.1f else 1f)
                .scaleY(if (hasFocus) 1.1f else 1f)
                .setDuration(150).start()
        }

        viewModel.favorites.observe(this) { movies ->
            binding.progressLoading.visibility = View.GONE
            adapter.submitList(movies)
            binding.tvCount.text = "${movies.size}"
            binding.tvEmpty.visibility = if (movies.isEmpty()) View.VISIBLE else View.GONE
            binding.rvPosters.visibility = if (movies.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun computeSpanCount(): Int {
        val screenWidthDp = resources.configuration.screenWidthDp
        return (screenWidthDp / 112).coerceIn(6, 10)
    }
}
