package com.nicotv.iptv2.ui.setup

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.nicotv.iptv2.IptvApplication
import com.nicotv.iptv2.R
import com.nicotv.iptv2.databinding.ActivityAddPlaylistBinding
import com.nicotv.iptv2.ui.common.BaseActivity
import com.nicotv.iptv2.ui.main.MainActivity
import kotlinx.coroutines.launch

/** Page complète "Charger votre playlist" (29/08/2026, demande explicite —
 * remplace l'ancien `AlertDialog` centré affiché par-dessus l'écran Profils
 * dans `SetupActivity`). Lancée par les cartes de Profils (nouveau profil) ou
 * par le crayon d'un profil existant (édition, pré-remplie via les extras
 * `EXTRA_EDIT_*`). Flèche retour = annuler, ramène sur Profils sans rien
 * enregistrer ; le formulaire reste ouvert en cas d'erreur pour corriger et
 * réessayer, seul un chargement réussi quitte la page (vers l'accueil). */
class AddPlaylistActivity : BaseActivity() {

    private lateinit var binding: ActivityAddPlaylistBinding
    private var editingId: Long = 0
    private var editingFileUri: String = ""
    // Uri du fichier M3U choisi, en attente du bouton "Charger".
    private var pickedFileUri: Uri? = null

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onFilePicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddPlaylistBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupHeader()

        editingId = intent.getLongExtra(EXTRA_EDIT_ID, 0)
        editingFileUri = intent.getStringExtra(EXTRA_EDIT_FILE_URI).orEmpty()
        if (editingId != 0L) {
            binding.etPlaylistName.setText(intent.getStringExtra(EXTRA_EDIT_NAME))
            binding.etM3uUrl.setText(intent.getStringExtra(EXTRA_EDIT_URL))
            // Le fichier local n'est pas re-proposé (l'Uri SAF d'origine reste
            // valide tel quel) : modifier ne touche que le nom/l'URL ici — pour
            // changer de fichier, il faut recréer le profil.
        }

        binding.btnPickFile.setOnClickListener { pickFileLauncher.launch(arrayOf("*/*")) }
        binding.btnLoadPlaylist.setOnClickListener { submit() }
    }

    private fun setupHeader() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnBack.setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) 1.2f else 1f).scaleY(if (hasFocus) 1.2f else 1f).setDuration(150).start()
            binding.btnBackRing.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
            if (hasFocus) binding.btnBackRing.startAnim() else binding.btnBackRing.stopAnim()
        }
    }

    private fun onFilePicked(uri: Uri) {
        // Permission persistante : nécessaire pour relire ce fichier après un
        // redémarrage de l'app (pas seulement pendant cette session).
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Certains fournisseurs (rares) ne supportent pas la permission persistante :
            // la lecture immédiate ci-dessous fonctionnera quand même pour cette session.
        }
        pickedFileUri = uri
        binding.tvFileName.visibility = View.VISIBLE
        val fileName = uri.lastPathSegment ?: uri.toString()
        binding.tvFileName.text = fileName
        if (binding.etPlaylistName.text.isNullOrBlank()) {
            binding.etPlaylistName.setText(fileName.substringAfterLast('/').substringBeforeLast('.'))
        }
    }

    private fun submit() {
        val name = binding.etPlaylistName.text.toString().trim()
        val url = binding.etM3uUrl.text.toString().trim()
        val fileUri = pickedFileUri
        if (name.isBlank() || (url.isBlank() && fileUri == null && editingFileUri.isBlank())) {
            if (name.isBlank()) binding.etPlaylistName.error = getString(R.string.setup_error_empty_name)
            Toast.makeText(this, R.string.setup_error_empty_url, Toast.LENGTH_LONG).show()
            return
        }
        setLoading(true)
        lifecycleScope.launch {
            val app = application as IptvApplication
            val id = when {
                fileUri != null -> app.playlistRepository.saveM3uFileProfile(name, fileUri.toString(), editingId)
                url.isNotBlank() -> app.playlistRepository.saveM3uUrlProfile(name, url, editingId)
                else -> app.playlistRepository.saveM3uFileProfile(name, editingFileUri, editingId)
            }
            loadProfile(id)
        }
    }

    private fun loadProfile(profileId: Long) {
        lifecycleScope.launch {
            val result = (application as IptvApplication).playlistRepository.loadProfile(profileId)
            setLoading(false)
            result.onSuccess { count ->
                showStatus(getString(R.string.setup_success, count))
                // Nouveau catalogue chargé : le fond aléatoire de l'accueil ne doit
                // pas garder une jaquette de l'ancienne source.
                MainActivity.resetHomeBg()
                startActivity(Intent(this@AddPlaylistActivity, MainActivity::class.java))
                finish()
            }.onFailure { e ->
                showStatus(e.message ?: getString(R.string.setup_error_generic))
            }
        }
    }

    // Rond de chargement au milieu de l'écran (29/08/2026, demande explicite
    // "au milieu pas en bas... indique chargement en cours") — avant, un petit
    // ProgressBar sans texte, tout en bas du formulaire, sous le clavier ou
    // hors champ de vision le temps de scroller. Même style que le dialogue
    // de mise à jour (UpdateManager.showUpdateProgress).
    private var loadingDialog: AlertDialog? = null

    private fun setLoading(loading: Boolean) {
        if (loading) {
            val view = layoutInflater.inflate(R.layout.dialog_loading, null)
            loadingDialog = AlertDialog.Builder(this).setView(view).setCancelable(false).create().also {
                it.show()
                it.window?.setBackgroundDrawableResource(android.R.color.transparent)
            }
        } else {
            loadingDialog?.dismiss()
            loadingDialog = null
        }
    }

    private fun showStatus(text: String) {
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.text = text
    }

    companion object {
        const val EXTRA_EDIT_ID = "extra_edit_id"
        const val EXTRA_EDIT_NAME = "extra_edit_name"
        const val EXTRA_EDIT_URL = "extra_edit_url"
        const val EXTRA_EDIT_FILE_URI = "extra_edit_file_uri"
    }
}
