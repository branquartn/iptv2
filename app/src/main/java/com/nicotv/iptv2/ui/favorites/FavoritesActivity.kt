package com.nicotv.iptv2.ui.favorites

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nicotv.iptv2.R
import com.nicotv.iptv2.domain.model.Movie
import com.nicotv.iptv2.player.PlayerActivity
import com.nicotv.iptv2.ui.common.BaseActivity
import com.nicotv.iptv2.ui.common.PosterAdapter
import com.nicotv.iptv2.ui.detail.DetailActivity
import com.nicotv.iptv2.ui.live.ChannelGridAdapter
import com.nicotv.iptv2.ui.series.SeriesDetailActivity

/** Écran "Favoris" — films/séries (mur d'affiches, `PosterAdapter`) **et**
 * chaînes (mosaïque, `ChannelGridAdapter` — 29/08/2026, bug corrigé : une
 * chaîne mise en favori sur l'écran Chaînes n'apparaissait nulle part ici
 * jusqu'ici, `FavoritesViewModel` n'interrogeait que
 * `getFavoriteMoviesAndSeries()`). Layout dédié (`activity_favorites.xml`),
 * plus le partage avec `activity_movies.xml`. */
@UnstableApi
class FavoritesActivity : BaseActivity() {

    private lateinit var viewModel: FavoritesViewModel
    private lateinit var moviesAdapter: PosterAdapter
    private lateinit var channelsAdapter: ChannelGridAdapter

    // Les deux LiveData répondent indépendamment (favoris films/séries vs
    // chaînes) — "vide" ne doit s'afficher qu'une fois que LES DEUX ont
    // répondu au moins une fois, sinon un flash "vide" apparaît le temps que
    // le second réponde (l'un peut émettre avant l'autre).
    private var movieFavoritesLoaded = false
    private var channelFavoritesLoaded = false
    private var hasMovieFavorites = false
    private var hasChannelFavorites = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_favorites)
        viewModel = ViewModelProvider(this)[FavoritesViewModel::class.java]

        val rvPosters = findViewById<RecyclerView>(R.id.rv_posters)
        val rvChannels = findViewById<RecyclerView>(R.id.rv_channels)
        val tvChannelsTip = findViewById<TextView>(R.id.tv_channels_tip)
        val tvMoviesSectionTitle = findViewById<TextView>(R.id.tv_movies_section_title)
        val tvEmpty = findViewById<TextView>(R.id.tv_empty)
        val tvCount = findViewById<TextView>(R.id.tv_count)
        val progressLoading = findViewById<ProgressBar>(R.id.progress_loading)
        val btnBack = findViewById<FrameLayout>(R.id.btn_back)
        val btnBackRing = findViewById<com.nicotv.iptv2.ui.common.RotatingBorderView>(R.id.btn_back_ring)

        moviesAdapter = PosterAdapter(onClick = { item ->
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
        rvPosters.layoutManager = GridLayoutManager(this, computeSpanCount())
        rvPosters.adapter = moviesAdapter

        // Retirer un favori depuis cet écran (appui long) fait disparaître la
        // chaîne de la liste au prochain tick — même comportement que
        // l'écran Chaînes, cohérent pour l'utilisateur.
        channelsAdapter = ChannelGridAdapter(
            onClick = { channel ->
                startActivity(Intent(this, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_STREAM_URL, channel.streamUrl)
                    putExtra(PlayerActivity.EXTRA_TITLE, channel.name)
                })
            },
            onToggleFavorite = { channel -> viewModel.toggleChannelFavorite(channel) }
        )
        rvChannels.layoutManager = GridLayoutManager(this, computeChannelSpanCount())
        rvChannels.adapter = channelsAdapter

        btnBack.setOnClickListener { finish() }
        btnBack.setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) 1.1f else 1f).scaleY(if (hasFocus) 1.1f else 1f).setDuration(150).start()
            btnBackRing.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
            if (hasFocus) btnBackRing.startAnim() else btnBackRing.stopAnim()
        }

        viewModel.favorites.observe(this) { items ->
            progressLoading.visibility = View.GONE
            movieFavoritesLoaded = true
            hasMovieFavorites = items.isNotEmpty()
            moviesAdapter.submitList(items)
            tvMoviesSectionTitle.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
            updateCountAndEmptyState(tvCount, tvEmpty)
        }

        viewModel.favoriteChannels.observe(this) { channels ->
            channelFavoritesLoaded = true
            hasChannelFavorites = channels.isNotEmpty()
            channelsAdapter.submitList(channels)
            rvChannels.visibility = if (channels.isEmpty()) View.GONE else View.VISIBLE
            // Astuce affichée à la place de la mosaïque tant qu'aucune chaîne
            // n'est en favori (29/08/2026, demande explicite : "un texte ou
            // tips qui explique comment mettre en favoris les chaînes") — le
            // geste (appui long) n'est pas découvrable de lui-même.
            tvChannelsTip.visibility = if (channels.isEmpty()) View.VISIBLE else View.GONE
            updateCountAndEmptyState(tvCount, tvEmpty)
        }
    }

    /** [tv_empty] (rien du tout, ni chaîne ni film/série) ne doit s'afficher
     * que si LES DEUX flux ont déjà répondu — sinon un flash "vide" apparaît
     * systématiquement le temps que le second flux (chaînes ou films/séries)
     * émette sa première valeur. */
    private fun updateCountAndEmptyState(tvCount: TextView, tvEmpty: TextView) {
        val moviesCount = moviesAdapter.itemCount
        val channelsCount = channelsAdapter.itemCount
        tvCount.text = "${moviesCount + channelsCount}"
        tvEmpty.visibility = if (movieFavoritesLoaded && channelFavoritesLoaded &&
            !hasMovieFavorites && !hasChannelFavorites
        ) View.VISIBLE else View.GONE
    }

    private fun computeSpanCount(): Int {
        val screenWidthDp = resources.configuration.screenWidthDp
        return (screenWidthDp / 112).coerceIn(6, 10)
    }

    /** Cf. LiveActivity.computeSpanCount — même tuile chaîne, pas de sidebar
     * catégories à déduire ici. */
    private fun computeChannelSpanCount(): Int {
        val screenWidthDp = resources.configuration.screenWidthDp
        return (screenWidthDp / 130).coerceIn(3, 8)
    }
}
