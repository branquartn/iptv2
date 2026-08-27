package com.nicotv.iptv2.ui.setup

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.nicotv.iptv2.IptvApplication
import com.nicotv.iptv2.R
import com.nicotv.iptv2.data.database.entity.PlaylistProfileEntity
import com.nicotv.iptv2.databinding.ActivitySetupBinding
import com.nicotv.iptv2.databinding.DialogFormPlaylistBinding
import com.nicotv.iptv2.databinding.DialogFormXtreamBinding
import com.nicotv.iptv2.ui.common.BaseActivity
import com.nicotv.iptv2.ui.common.RotatingBorderView
import com.nicotv.iptv2.ui.main.MainActivity
import com.nicotv.iptv2.update.checkForAppUpdate
import kotlinx.coroutines.launch

/**
 * Écran de démarrage : pas de compte, pas de login. Affiche les profils déjà
 * sauvegardés (nommés par l'utilisateur, un par source M3U/Xtream) — tap pour
 * recharger l'un d'eux — et 2 cartes pour en ajouter un nouveau : "Charger
 * votre playlist" (URL M3U ou fichier local, un seul formulaire) et "Xtream
 * Codes". Chaque carte ouvre son formulaire dans un dialogue centré
 * (AlertDialog) plutôt que de l'étaler sous les cartes — avant, le formulaire
 * apparaissait en dessous et poussait le reste de l'écran, peu lisible.
 * Réutilisé pour "Changer de source" depuis MainActivity (EXTRA_FORCE_SHOW).
 */
class SetupActivity : BaseActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var profileAdapter: ProfileAdapter
    // Uri du fichier M3U choisi (formulaire "fichier local"), en attente du nom
    // avant de sauvegarder le profil.
    private var pickedFileUri: Uri? = null
    // Binding du dialogue playlist actuellement affiché — nécessaire pour que
    // le retour du sélecteur de fichier (callback enregistré une seule fois à
    // onCreate) puisse mettre à jour le nom de fichier dans CE dialogue.
    private var playlistDialogBinding: DialogFormPlaylistBinding? = null

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onFilePicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // installSplashScreen() DOIT être appelé avant super.onCreate() — c'est
        // lui qui bascule le thème de la fenêtre de Theme.IPTV.Splash (déclaré
        // dans le manifeste, chrome clair par défaut de Theme.SplashScreen) vers
        // postSplashScreenTheme (Theme.IPTV, sombre). Jamais appelé jusqu'ici :
        // l'activité restait bloquée sur le thème splash toute sa durée de vie
        // → bandeau clair système avec le nom de l'app, visible en permanence
        // sur cet écran (seul point d'entrée MAIN/LAUNCHER de l'app).
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Écran de choix TOUJOURS affiché au démarrage (comme IPTV Smarters Pro) :
        // les profils déjà enregistrés sont listés juste au-dessus des 2 cartes,
        // un tap dessus recharge la source sans rien retaper. Avant : on sautait
        // directement à l'accueil dès qu'un profil était actif, donc l'écran de
        // sélection restait invisible une fois le premier profil créé.
        checkForAppUpdate()
        setupProfilesList()
        setupTypeCards()
    }

    private fun setupProfilesList() {
        profileAdapter = ProfileAdapter(
            onClick = { profile -> loadProfile(profile.id) },
            onEdit = { profile -> editProfile(profile) },
            onDelete = { profile -> confirmDelete(profile) }
        )
        binding.rvProfiles.adapter = profileAdapter

        lifecycleScope.launch {
            // Filet : si Room a perdu les profils (montée de schéma destructive,
            // incident), on les réinjecte depuis la copie SharedPreferences avant
            // d'afficher la liste — cf. ProfileBackupPrefs.
            (application as IptvApplication).playlistRepository.restoreProfilesIfEmpty()
        }

        lifecycleScope.launch {
            (application as IptvApplication).playlistRepository.getProfiles().collect { profiles ->
                // Diagnostic profils "disparus" signalés en test — grep logcat
                // "SetupActivity" pour voir si la table playlist_profiles est
                // vraiment vide à l'ouverture ou si le souci est ailleurs.
                android.util.Log.i("SetupActivity", "Profils en base : ${profiles.size} (${profiles.joinToString { "${it.id}:${it.name}" }})")
                binding.sectionProfiles.visibility = if (profiles.isEmpty()) View.GONE else View.VISIBLE
                profileAdapter.submitList(profiles)
            }
        }
    }

    private fun confirmDelete(profile: PlaylistProfileEntity) {
        AlertDialog.Builder(this)
            .setTitle("Supprimer ce profil ?")
            .setMessage(profile.name)
            .setPositiveButton("Supprimer") { _, _ ->
                lifecycleScope.launch { (application as IptvApplication).playlistRepository.deleteProfile(profile.id) }
            }
            .setNegativeButton("Annuler", null)
            .create()
            .also { it.window?.setBackgroundDrawableResource(R.drawable.bg_dialog) }
            .show()
    }

    private fun setupTypeCards() {
        val cards = listOf(
            Triple(binding.cardTypePlaylist, binding.cardTypePlaylistRing) { showPlaylistDialog(null) },
            Triple(binding.cardTypeXtream, binding.cardTypeXtreamRing) { showXtreamDialog(null) }
        )
        cards.forEach { (card, ring, onOpen) ->
            card.setOnClickListener { onOpen() }
            applyRing(card, ring, 1.04f)
        }
    }

    /** Ouvre le dialogue correspondant, pré-rempli avec les valeurs actuelles
     * du profil (bouton crayon sur "Mes profils"). */
    private fun editProfile(profile: PlaylistProfileEntity) {
        when (profile.type) {
            "M3U_URL", "M3U_FILE" -> showPlaylistDialog(profile)
            "XTREAM" -> showXtreamDialog(profile)
        }
    }

    // ── Dialogue "Charger votre playlist" (M3U url/fichier) ────────────────
    // [editing] non-null : dialogue ouvert en modification (pré-rempli, la
    // sauvegarde réutilise son id — cf. PlaylistRepository.saveM3u*Profile).

    private fun showPlaylistDialog(editing: PlaylistProfileEntity?) {
        val dialogBinding = DialogFormPlaylistBinding.inflate(layoutInflater)
        playlistDialogBinding = dialogBinding
        pickedFileUri = null
        if (editing != null) {
            dialogBinding.etPlaylistName.setText(editing.name)
            dialogBinding.etM3uUrl.setText(editing.m3uUrl)
            // Le fichier local n'est pas re-proposé (l'Uri SAF d'origine reste
            // valide tel quel) : modifier ne touche que le nom/l'URL ici — pour
            // changer de fichier, il faut recréer le profil.
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.setup_type_playlist)
            .setView(dialogBinding.root)
            .setNegativeButton("Annuler", null)
            .create()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog)
        dialog.setOnDismissListener { playlistDialogBinding = null }

        dialogBinding.btnPickFile.setOnClickListener { pickFileLauncher.launch(arrayOf("*/*")) }
        dialogBinding.btnLoadPlaylist.setOnClickListener {
            val name = dialogBinding.etPlaylistName.text.toString().trim()
            val url = dialogBinding.etM3uUrl.text.toString().trim()
            val fileUri = pickedFileUri
            if (name.isBlank() || (url.isBlank() && fileUri == null && editing?.m3uFileUri.isNullOrBlank())) {
                // Toast, pas showStatus() : ce texte-là est sur l'écran principal,
                // invisible derrière le dialogue encore ouvert — l'utilisateur ne
                // voyait jamais pourquoi rien ne se passait (champ "Nom" vide,
                // facile à zapper), pensait avoir validé alors que rien n'était
                // enregistré.
                if (name.isBlank()) dialogBinding.etPlaylistName.error = getString(R.string.setup_error_empty_name)
                android.widget.Toast.makeText(this@SetupActivity, R.string.setup_error_empty_url, android.widget.Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            setLoading(true)
            lifecycleScope.launch {
                val app = application as IptvApplication
                val existingId = editing?.id ?: 0
                val id = when {
                    fileUri != null -> app.playlistRepository.saveM3uFileProfile(name, fileUri.toString(), existingId)
                    url.isNotBlank() -> app.playlistRepository.saveM3uUrlProfile(name, url, existingId)
                    else -> app.playlistRepository.saveM3uFileProfile(name, editing!!.m3uFileUri, existingId)
                }
                loadProfile(id)
            }
        }

        dialog.show()
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
        val dialogBinding = playlistDialogBinding ?: return
        dialogBinding.tvFileName.visibility = View.VISIBLE
        val fileName = uri.lastPathSegment ?: uri.toString()
        dialogBinding.tvFileName.text = fileName
        if (dialogBinding.etPlaylistName.text.isNullOrBlank()) {
            dialogBinding.etPlaylistName.setText(fileName.substringAfterLast('/').substringBeforeLast('.'))
        }
    }

    // ── Dialogue "Xtream Codes" ──────────────────────────────────────────────
    // [editing] non-null : dialogue ouvert en modification (pré-rempli, id
    // réutilisé à la sauvegarde — cf. PlaylistRepository.saveXtreamProfile).

    private fun showXtreamDialog(editing: PlaylistProfileEntity?) {
        val dialogBinding = DialogFormXtreamBinding.inflate(layoutInflater)
        if (editing != null) {
            dialogBinding.etXtreamName.setText(editing.name)
            dialogBinding.etXtreamHost.setText(editing.xtreamHost)
            dialogBinding.etXtreamUser.setText(editing.xtreamUsername)
            dialogBinding.etXtreamPass.setText(editing.xtreamPassword)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.setup_type_xtream)
            .setView(dialogBinding.root)
            .setNegativeButton("Annuler", null)
            .create()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog)

        dialogBinding.btnLoadXtream.setOnClickListener {
            val name = dialogBinding.etXtreamName.text.toString().trim()
            val host = dialogBinding.etXtreamHost.text.toString().trim()
            val user = dialogBinding.etXtreamUser.text.toString().trim()
            val pass = dialogBinding.etXtreamPass.text.toString()
            if (name.isBlank() || host.isBlank() || user.isBlank() || pass.isBlank()) {
                // Toast, pas showStatus() : cf. commentaire équivalent dans
                // showPlaylistDialog — le texte sur l'écran principal est
                // invisible derrière ce dialogue encore ouvert.
                if (name.isBlank()) dialogBinding.etXtreamName.error = getString(R.string.setup_error_empty_name)
                if (host.isBlank()) dialogBinding.etXtreamHost.error = getString(R.string.setup_error_empty_name)
                if (user.isBlank()) dialogBinding.etXtreamUser.error = getString(R.string.setup_error_empty_name)
                if (pass.isBlank()) dialogBinding.etXtreamPass.error = getString(R.string.setup_error_empty_name)
                android.widget.Toast.makeText(this@SetupActivity, R.string.setup_error_empty_xtream, android.widget.Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            setLoading(true)
            lifecycleScope.launch {
                val app = application as IptvApplication
                val id = app.playlistRepository.saveXtreamProfile(name, host, user, pass, editing?.id ?: 0)
                loadProfile(id)
            }
        }

        dialog.show()
    }

    private fun loadProfile(profileId: Long) {
        setLoading(true)
        lifecycleScope.launch {
            val result = (application as IptvApplication).playlistRepository.loadProfile(profileId)
            setLoading(false)
            result.onSuccess { count ->
                showStatus(getString(R.string.setup_success, count))
                goToMain()
            }.onFailure { e ->
                showStatus(e.message ?: getString(R.string.setup_error_generic))
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressLoading.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun showStatus(text: String) {
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.text = text
    }

    private fun applyRing(target: View, ring: RotatingBorderView, scale: Float) {
        target.setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) scale else 1f).scaleY(if (hasFocus) scale else 1f).setDuration(150).start()
            ring.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
            if (hasFocus) ring.startAnim() else ring.stopAnim()
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        const val EXTRA_FORCE_SHOW = "extra_force_show"
    }
}
