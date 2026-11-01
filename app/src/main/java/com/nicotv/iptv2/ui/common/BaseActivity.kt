package com.nicotv.iptv2.ui.common

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/** Android TV / Fire TV / Nvidia Shield : sur ces appareils, un badge cliquable
 * séparé du reste de la carte est peu fiable au D-pad (un seul focus par carte)
 * → certains écrans consolident l'ajout dans l'aperçu (cf. DetailActivity). */
fun Context.isTvDevice(): Boolean {
    val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}

/**
 * Activité de base : active le mode plein écran immersif
 * (barre d'état + barre de navigation masquées) sur toutes les pages.
 */
open class BaseActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveMode()
        // Demandé explicitement : l'écran s'éteignait pendant qu'on parcourt les
        // menus (chaînes/films/séries), pas seulement pendant la lecture (déjà
        // géré séparément par PlayerActivity). Redondant mais inoffensif là où
        // PlayerActivity pose déjà le flag.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ── Bandeau « Chargement de la playlist… 42 % » en haut de chaque écran ──────
    // Ajouté dans onStart (après le setContentView de la sous-classe, qui viderait le conteneur
    // sinon). Ne prend jamais le focus ni les touches : on continue de naviguer et de regarder la télé.
    private var loadBanner: android.widget.LinearLayout? = null

    override fun onStart() {
        super.onStart()
        val app = application as? com.nicotv.iptv2.IptvApplication ?: return
        val content = findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
        val dp = resources.displayMetrics.density
        val banner = loadBanner ?: android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(0xE6131F3A.toInt())
            isClickable = false; isFocusable = false
            elevation = 24f * dp
            visibility = android.view.View.GONE
            val text = android.widget.TextView(context).apply {
                id = com.nicotv.iptv2.R.id.load_banner_text
                setTextColor(0xFFFFFFFF.toInt()); textSize = 12f
                setPadding((12 * dp).toInt(), (4 * dp).toInt(), (12 * dp).toInt(), (3 * dp).toInt())
            }
            val bar = android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                id = com.nicotv.iptv2.R.id.load_banner_bar
                max = 100
                layoutParams = android.widget.LinearLayout.LayoutParams(-1, (3 * dp).toInt())
            }
            addView(text); addView(bar)
            loadBanner = this
        }
        if (banner.parent == null) {
            content.addView(banner, android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.TOP))
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.catalogLoader.state.collect { st ->
                    if (st == null) { banner.visibility = android.view.View.GONE; return@collect }
                    banner.visibility = android.view.View.VISIBLE
                    val text = banner.findViewById<android.widget.TextView>(com.nicotv.iptv2.R.id.load_banner_text)
                    val bar = banner.findViewById<android.widget.ProgressBar>(com.nicotv.iptv2.R.id.load_banner_bar)
                    if (st.error != null) {
                        text.text = "Chargement impossible : ${st.error}"
                        bar.visibility = android.view.View.GONE
                    } else {
                        text.text = "Chargement de la playlist ${st.profileName} — ${st.percent} %  ·  ${st.message}"
                        bar.visibility = android.view.View.VISIBLE
                        bar.progress = st.percent
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveMode()
    }

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
