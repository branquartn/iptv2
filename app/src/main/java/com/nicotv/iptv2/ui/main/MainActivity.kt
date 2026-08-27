package com.nicotv.iptv2.ui.main

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.nicotv.iptv2.IptvApplication
import com.nicotv.iptv2.R
import com.nicotv.iptv2.databinding.ActivityMainBinding
import com.nicotv.iptv2.ui.favorites.FavoritesActivity
import com.nicotv.iptv2.ui.live.LiveActivity
import com.nicotv.iptv2.ui.movies.MoviesActivity
import com.nicotv.iptv2.ui.resume.ResumeActivity
import com.nicotv.iptv2.ui.search.SearchActivity
import com.nicotv.iptv2.ui.series.SeriesActivity
import com.nicotv.iptv2.ui.setup.SetupActivity
import com.nicotv.iptv2.update.checkForAppUpdate
import kotlinx.coroutines.launch

class MainActivity : com.nicotv.iptv2.ui.common.BaseActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvVersion.text = "v${com.nicotv.iptv2.BuildConfig.VERSION_NAME}"

        setupNavigation()
        setupFocusAnimations()
        binding.cardLive.requestFocus()
        observeData()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { showQuitDialog() }
        })
    }

    override fun onStart() {
        super.onStart()
        checkForAppUpdate()
    }

    private var quitDialog: AlertDialog? = null

    private fun showQuitDialog() {
        if (quitDialog?.isShowing == true) return
        val view = layoutInflater.inflate(R.layout.dialog_quit, null)
        val dialog = AlertDialog.Builder(this).setView(view).create()
        quitDialog = dialog
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        view.findViewById<Button>(R.id.btn_quit_cancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<Button>(R.id.btn_quit_confirm).setOnClickListener { dialog.dismiss(); finishAffinity() }
        dialog.show()
        view.findViewById<Button>(R.id.btn_quit_cancel).requestFocus()
    }

    private fun setupNavigation() {
        binding.cardLive.setOnClickListener { startActivity(Intent(this, LiveActivity::class.java)) }
        binding.cardFilms.setOnClickListener { startActivity(Intent(this, MoviesActivity::class.java)) }
        binding.cardSeries.setOnClickListener { startActivity(Intent(this, SeriesActivity::class.java)) }
        binding.btnResume.setOnClickListener { startActivity(Intent(this, ResumeActivity::class.java)) }
        binding.btnSearch.setOnClickListener { startActivity(Intent(this, SearchActivity::class.java)) }
        binding.btnFavorites.setOnClickListener { startActivity(Intent(this, FavoritesActivity::class.java)) }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java).apply {
                putExtra(SetupActivity.EXTRA_FORCE_SHOW, true)
            })
        }
    }

    private fun setupFocusAnimations() {
        listOf(
            binding.cardLive to binding.focusRingLive,
            binding.cardFilms to binding.focusRingFilms,
            binding.cardSeries to binding.focusRingSeries
        ).forEach { (card, ring) ->
            card.setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.04f else 1f).scaleY(if (hasFocus) 1.04f else 1f).setDuration(150).start()
                ring.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
                if (hasFocus) ring.startAnim() else ring.stopAnim()
            }
        }

        listOf(
            binding.btnResume to binding.btnResumeRing,
            binding.btnSearch to binding.btnSearchRing,
            binding.btnFavorites to binding.btnFavoritesRing,
            binding.btnSettings to binding.btnSettingsRing
        ).forEach { (view, ring) ->
            view.setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.25f else 1f).scaleY(if (hasFocus) 1.25f else 1f).setDuration(150).start()
                v.z = if (hasFocus) 10f else 0f
                ring.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
                if (hasFocus) ring.startAnim() else ring.stopAnim()
            }
        }
    }

    private fun observeData() {
        val app = application as IptvApplication

        lifecycleScope.launch {
            app.database.channelDao().getAllChannels().collect { list ->
                binding.tvLiveCount.text = formatCount(list.size, "chaîne")
            }
        }
        lifecycleScope.launch {
            app.database.movieDao().getAllMovies().collect { list ->
                binding.tvFilmsCount.text = formatCount(list.size, "titre")
            }
        }
        lifecycleScope.launch {
            app.database.seriesDao().getAllSeries().collect { list ->
                binding.tvSeriesCount.text = formatCount(list.size, "série")
            }
        }

        lifecycleScope.launch {
            app.playlistRepository.getUnifiedHistory().collect { history ->
                binding.btnResume.visibility = if (history.isNotEmpty()) View.VISIBLE else View.GONE
                binding.tvResumeCount.text = history.size.toString()
            }
        }

        lifecycleScope.launch {
            app.playlistRepository.getFavoritesCount().collect { count ->
                if (count > 0) {
                    binding.tvFavoritesCount.visibility = View.VISIBLE
                    binding.tvFavoritesCount.text = count.toString()
                } else {
                    binding.tvFavoritesCount.visibility = View.GONE
                }
            }
        }
    }

    private fun formatCount(count: Int, label: String): String =
        "$count $label${if (count > 1) "s" else ""}"
}
