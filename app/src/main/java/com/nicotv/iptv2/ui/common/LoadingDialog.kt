package com.nicotv.iptv2.ui.common

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import com.nicotv.iptv2.R

/** Dialogue de chargement générique centré à l'écran (cercle de progression +
 * pourcentage dedans), réutilisé par SetupActivity/AddPlaylistActivity/
 * AddXtreamActivity/SettingsActivity (29/08/2026, demande explicite : "je
 * veux aussi la fenêtre de chargement quand le profil charge, avec un
 * pourcentage"). Remplace un petit `ProgressBar` indéterminé sans texte —
 * cf. `dialog_loading.xml`, même style que `UpdateManager.showUpdateProgress`
 * (fond `bg_dialog` sur la vue, fenêtre du dialogue en transparent).
 *
 * ⚠️ **Valeur affichée lissée, pas un simple miroir de [onProgress]** (même
 * jour, demande explicite après une première version qui sautait tel quel
 * d'un palier à l'autre : "ne correspond pas à la réalité... 0 à 30 en 2
 * secondes et après 60 elle met 5 minutes... je voudrais voir la
 * progression... qui avance de pourcentage en pourcentage... en temps réel")
 * — [PlaylistRepository.loadProfile] ne fournit que quelques paliers réels
 * (connexion, catégories, flux...), sans granularité entre eux. Un ticker
 * (`Handler`, ~150ms) fait deux choses : (1) rattrape la valeur affichée vers
 * la dernière valeur réelle reçue par petits pas visibles (jamais un saut
 * instantané), (2) si aucune nouvelle valeur réelle n'arrive pendant un
 * moment (palier long, ex. "récupération des flux" sur un gros panel),
 * avance lentement d'elle-même (~1%/2,5s) jusqu'à un plafond raisonnable
 * (dernière valeur réelle + 20, jamais au-delà de 96) — donne l'impression
 * de mouvement continu plutôt qu'un chiffre figé, sans jamais prétendre être
 * plus avancé que ce qui est su. Un vrai palier suivant fait reprendre le
 * rattrapage normalement.
 *
 * [onProgress] est fait pour être passé tel quel à
 * `PlaylistRepository.loadProfile(profileId, onProgress = dialog::onProgress)`
 * — il peut être appelé depuis n'importe quel thread (l'enrichissement TMDb
 * tourne sur `Dispatchers.IO`), `runOnUiThread` s'occupe du passage au thread
 * principal. */
class LoadingDialog(private val activity: Activity) {
    private val view = activity.layoutInflater.inflate(R.layout.dialog_loading, null)
    private val circularProgress = view.findViewById<CircularProgressView>(R.id.circular_progress)
    private val tvPercent = view.findViewById<TextView>(R.id.tv_loading_percent)
    private val tvMessage = view.findViewById<TextView>(R.id.tv_loading_message)
    private val dialog = AlertDialog.Builder(activity).setView(view).setCancelable(false).create()

    private val handler = Handler(Looper.getMainLooper())
    private var targetPercent = 0
    private var displayedPercent = 0
    private var lastRealUpdateAtMs = 0L
    private var creepTickCounter = 0
    private var running = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()
            if (displayedPercent < targetPercent) {
                val gap = targetPercent - displayedPercent
                // Rattrapage proportionnel au retard — visible (plusieurs
                // pas comptés), jamais un bond instantané, plus rapide sur
                // un grand écart que sur un petit.
                displayedPercent = (displayedPercent + maxOf(1, gap / 6)).coerceAtMost(targetPercent)
            } else if (now - lastRealUpdateAtMs > IDLE_BEFORE_CREEP_MS) {
                val creepCap = (targetPercent + CREEP_MAX_AHEAD).coerceAtMost(CREEP_HARD_CAP)
                if (displayedPercent < creepCap) {
                    creepTickCounter++
                    if (creepTickCounter >= CREEP_EVERY_N_TICKS) {
                        creepTickCounter = 0
                        displayedPercent++
                    }
                }
            }
            circularProgress.progress = displayedPercent
            tvPercent.text = "$displayedPercent%"
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun show() {
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        running = true
        handler.post(ticker)
    }

    fun onProgress(percent: Int, message: String) {
        activity.runOnUiThread {
            targetPercent = percent.coerceIn(0, 100)
            lastRealUpdateAtMs = System.currentTimeMillis()
            creepTickCounter = 0
            tvMessage.text = message
        }
    }

    fun dismiss() {
        running = false
        handler.removeCallbacks(ticker)
        if (dialog.isShowing) dialog.dismiss()
    }

    companion object {
        private const val TICK_MS = 150L
        private const val IDLE_BEFORE_CREEP_MS = 1500L
        // ~1% toutes les CREEP_EVERY_N_TICKS * TICK_MS ms (17 * 150ms ≈ 2,5s).
        private const val CREEP_EVERY_N_TICKS = 17
        private const val CREEP_MAX_AHEAD = 20
        private const val CREEP_HARD_CAP = 96
    }
}
