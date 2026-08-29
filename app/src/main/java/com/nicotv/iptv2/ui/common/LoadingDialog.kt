package com.nicotv.iptv2.ui.common

import android.app.Activity
import android.app.AlertDialog
import android.widget.ProgressBar
import android.widget.TextView
import com.nicotv.iptv2.R

/** Dialogue de chargement générique centré à l'écran (ProgressBar déterminée
 * + pourcentage), réutilisé par SetupActivity/AddPlaylistActivity/
 * AddXtreamActivity (29/08/2026, demande explicite : "je veux aussi la
 * fenêtre de chargement quand le profil charge, avec un pourcentage").
 * Remplace un petit `ProgressBar` indéterminé sans texte, tout en bas du
 * formulaire — cf. `dialog_loading.xml`, même style que
 * `UpdateManager.showUpdateProgress` (fond `bg_dialog` sur la vue, fenêtre
 * du dialogue en transparent).
 *
 * [onProgress] est fait pour être passé tel quel à
 * `PlaylistRepository.loadProfile(profileId, onProgress = dialog::onProgress)`
 * — il peut être appelé depuis n'importe quel thread (l'enrichissement TMDb
 * tourne sur `Dispatchers.IO`), `runOnUiThread` s'occupe du passage au thread
 * principal. */
class LoadingDialog(private val activity: Activity) {
    private val view = activity.layoutInflater.inflate(R.layout.dialog_loading, null)
    private val progressBar = view.findViewById<ProgressBar>(R.id.progress_bar)
    private val tvPercent = view.findViewById<TextView>(R.id.tv_loading_percent)
    private val tvMessage = view.findViewById<TextView>(R.id.tv_loading_message)
    private val dialog = AlertDialog.Builder(activity).setView(view).setCancelable(false).create()

    fun show() {
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    fun onProgress(percent: Int, message: String) {
        activity.runOnUiThread {
            progressBar.progress = percent
            tvPercent.text = "$percent%"
            tvMessage.text = message
        }
    }

    fun dismiss() {
        if (dialog.isShowing) dialog.dismiss()
    }
}
