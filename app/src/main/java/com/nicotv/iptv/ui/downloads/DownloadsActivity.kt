package com.nicotv.iptv.ui.downloads

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import com.nicotv.iptv.R
import com.nicotv.iptv.data.database.entity.DownloadEntity
import com.nicotv.iptv.data.database.entity.EpisodeEntity
import com.nicotv.iptv.databinding.ActivityMoviesBinding
import com.nicotv.iptv.player.PlayerActivity
import com.nicotv.iptv.ui.common.BaseActivity
import com.nicotv.iptv.ui.detail.DetailActivity
import kotlinx.coroutines.launch

/** Films/épisodes téléchargés localement (mode avion). Réutilise le layout de
 * ResumeActivity (mêmes vues : barre + liste), sans le champ de recherche. */
@UnstableApi
class DownloadsActivity : BaseActivity() {

    private lateinit var binding: ActivityMoviesBinding
    private lateinit var viewModel: DownloadsViewModel
    private lateinit var adapter: DownloadAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMoviesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel = ViewModelProvider(this)[DownloadsViewModel::class.java]

        binding.tvSectionTitle.text = getString(R.string.downloads_title)
        binding.searchBox.visibility = View.GONE

        adapter = DownloadAdapter(
            onClick = { d -> if (d.state == DownloadEntity.STATE_COMPLETED) playDownload(d) },
            onDelete = { d -> confirmDelete(d) }
        )
        binding.rvPosters.apply {
            layoutManager = LinearLayoutManager(this@DownloadsActivity)
            adapter = this@DownloadsActivity.adapter
            setHasFixedSize(false)
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnBack.setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) 1.1f else 1f)
                .scaleY(if (hasFocus) 1.1f else 1f)
                .setDuration(150).start()
        }

        viewModel.downloads.observe(this) { list ->
            binding.progressLoading.visibility = View.GONE
            adapter.submitList(list)
            binding.tvCount.text = "${list.size}"
            binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            binding.rvPosters.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun playDownload(d: DownloadEntity) {
        if (d.type == DownloadEntity.TYPE_MOVIE) {
            val movieId = d.key.removePrefix("movie:").toLongOrNull() ?: return
            startActivity(Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_MOVIE_ID, movieId)
                putExtra(PlayerActivity.EXTRA_STREAM_URL, d.sourceUrl)
                putExtra(PlayerActivity.EXTRA_TITLE, d.title)
                putExtra(PlayerActivity.EXTRA_RESUME, true)
            })
        } else {
            startActivity(Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_MOVIE_ID, EpisodeEntity.computeWatchKey(d.key))
                putExtra(PlayerActivity.EXTRA_STREAM_URL, d.sourceUrl)
                putExtra(PlayerActivity.EXTRA_TITLE, "${d.title} — ${d.episodeTitle}")
                putExtra(PlayerActivity.EXTRA_RESUME, true)
                putExtra(PlayerActivity.EXTRA_SERIES_ID, d.seriesId)
                putExtra(PlayerActivity.EXTRA_SERIES_TITLE, d.title)
                putExtra(PlayerActivity.EXTRA_EPISODE_TITLE, d.episodeTitle)
                putExtra(PlayerActivity.EXTRA_EPISODE_NUMBER, d.episodeNumber)
                putExtra(PlayerActivity.EXTRA_SEASON_NUMBER, d.seasonNumber)
                putExtra(PlayerActivity.EXTRA_FILE_KEY, d.key)
            })
        }
    }

    private fun confirmDelete(d: DownloadEntity) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Supprimer le téléchargement ?")
            .setMessage(if (d.type == DownloadEntity.TYPE_EPISODE) "${d.title} — ${d.episodeTitle}" else d.title)
            .setPositiveButton("Supprimer") { _, _ ->
                lifecycleScope.launch { viewModel.downloadRepository.delete(d.key) }
            }
            .setNegativeButton("Annuler", null)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog)
    }
}
