package com.nicotv.iptv2.ui.resume

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import com.nicotv.iptv2.IptvApplication
import com.nicotv.iptv2.R
import com.nicotv.iptv2.databinding.ActivityMoviesBinding
import com.nicotv.iptv2.domain.model.Movie
import com.nicotv.iptv2.player.PlayerActivity
import com.nicotv.iptv2.ui.common.BaseActivity
import com.nicotv.iptv2.ui.common.PosterAdapter
import com.nicotv.iptv2.ui.detail.DetailActivity

@UnstableApi
class ResumeActivity : BaseActivity() {

    private lateinit var binding: ActivityMoviesBinding
    private lateinit var viewModel: ResumeViewModel
    private lateinit var adapter: PosterAdapter

    // Présence admin.nicotv.ovh (« qui regarde quoi ») : écran courant hors lecture.
    override fun onResume() {
        super.onResume()
        (application as IptvApplication).reportScreen("En cours")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMoviesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel = ViewModelProvider(this)[ResumeViewModel::class.java]

        binding.tvSectionTitle.text = getString(R.string.resume_playback)
        binding.searchBox.visibility = View.GONE

        adapter = PosterAdapter(onClick = { movie ->
            if (movie.type == Movie.Type.MOVIE) {
                startActivity(Intent(this, DetailActivity::class.java).apply {
                    putExtra(DetailActivity.EXTRA_MOVIE_ID, movie.id)
                })
            } else {
                startActivity(Intent(this, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_MOVIE_ID, movie.id)
                    putExtra(PlayerActivity.EXTRA_STREAM_URL, movie.streamUrl)
                    putExtra(PlayerActivity.EXTRA_TITLE, movie.title)
                    putExtra(PlayerActivity.EXTRA_RESUME, true)
                    // Sans ça, PlayerActivity.seriesId reste -1L (valeur par défaut de
                    // l'extra manquant) → le prompt/enchaînement épisode suivant ne se
                    // déclenche jamais quand la lecture démarre depuis "Reprendre la
                    // lecture" (marchait déjà depuis la fiche série, qui les passe).
                    putExtra(PlayerActivity.EXTRA_SERIES_ID, movie.seriesId)
                    putExtra(PlayerActivity.EXTRA_SERIES_TITLE, movie.seriesTitle)
                })
            }
        })

        binding.rvPosters.apply {
            layoutManager = GridLayoutManager(this@ResumeActivity, computeSpanCount())
            adapter = this@ResumeActivity.adapter
            setHasFixedSize(false)
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnBack.setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) 1.1f else 1f)
                .scaleY(if (hasFocus) 1.1f else 1f)
                .setDuration(150).start()
        }

        viewModel.resumeMovies.observe(this) { movies ->
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
