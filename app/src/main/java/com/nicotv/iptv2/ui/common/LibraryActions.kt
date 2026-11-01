package com.nicotv.iptv2.ui.common

import android.app.AlertDialog
import android.net.Uri
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.nicotv.iptv2.IptvApplication
import com.nicotv.iptv2.R
import com.nicotv.iptv2.data.repository.PlaylistRepository
import com.nicotv.iptv2.domain.model.Movie
import kotlinx.coroutines.launch

/** Retrait d'un film/série de la bibliothèque du serveur (appui long sur OK / sur l'affiche), comme l'app iptv. */
object LibraryActions {

    fun confirmRemove(activity: FragmentActivity, item: Movie, isSeries: Boolean) {
        // Seuls les titres de « Mon serveur » se retirent ici ; ceux du Xtream se gèrent depuis l'admin.
        if (item.category != PlaylistRepository.SERVER_CATEGORY) {
            Toast.makeText(activity, "« ${item.displayTitle} » vient de la playlist Xtream : il se gère depuis l'admin.", Toast.LENGTH_LONG).show()
            return
        }
        val app = activity.application as IptvApplication
        val admin = app.sessionManager.isAdmin()
        val msg = if (admin)
            "⚠️ ADMIN : suppression DÉFINITIVE du catalogue pour tous les profils ET du torrent sur la seedbox. Irréversible, il faudra re-télécharger."
        else
            "Le titre disparaît de ta bibliothèque. Les autres profils et les fichiers ne sont pas touchés."
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Retirer « ${item.displayTitle} » ?")
            .setMessage(msg)
            .setPositiveButton(if (admin) "Supprimer définitivement" else "Retirer") { _, _ ->
                activity.lifecycleScope.launch {
                    val file = Uri.decode(item.streamUrl.substringBefore('?').substringAfterLast('/'))
                    val j = if (isSeries) app.libraryApi.deleteMedia("series", name = item.title)
                            else app.libraryApi.deleteMedia("movie", releaseKey = file, strm = (item.title + " " + item.releaseYear).trim())
                    val ok = j?.optBoolean("ok") == true
                    Toast.makeText(activity,
                        if (ok) "Retiré de la bibliothèque" else (j?.optString("error")?.takeIf { it.isNotBlank() } ?: "Impossible de retirer ce titre"),
                        Toast.LENGTH_LONG).show()
                    if (ok && runCatching { app.playlistRepository.refreshServerLibrary() }.getOrDefault(false)) app.libraryRevision.value++
                }
            }
            .setNegativeButton("Annuler", null)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog)
    }
}
