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
