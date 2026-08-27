package com.nicotv.iptv2.ui.setup

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.nicotv.iptv2.IptvApplication
import com.nicotv.iptv2.R
import com.nicotv.iptv2.data.database.entity.PlaylistProfileEntity
import com.nicotv.iptv2.databinding.ActivitySetupBinding
import com.nicotv.iptv2.ui.common.BaseActivity
import com.nicotv.iptv2.ui.common.RotatingBorderView
import com.nicotv.iptv2.ui.main.MainActivity
import com.nicotv.iptv2.update.checkForAppUpdate
import kotlinx.coroutines.launch

/**
 * Écran de démarrage : pas de compte, pas de login. Affiche les profils déjà
 * sauvegardés (nommés par l'utilisateur, un par source M3U/Xtream) — tap pour
 * recharger l'un d'eux — et une grille "type de source" pour en ajouter un
 * nouveau (fichier M3U local, URL M3U, ou Xtream Codes). Réutilisé pour
 * "Changer de source" depuis MainActivity (EXTRA_FORCE_SHOW).
 */
class SetupActivity : BaseActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var profileAdapter: ProfileAdapter
    // Uri du fichier M3U choisi (formulaire "fichier local"), en attente du nom
    // avant de sauvegarder le profil.
    private var pickedFileUri: Uri? = null

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onFilePicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as IptvApplication
        val forceShow = intent.getBooleanExtra(EXTRA_FORCE_SHOW, false)

        // Un profil est déjà actif (relance normale de l'app) : on saute
        // directement à l'accueil, sauf si l'utilisateur est venu volontairement
        // reconfigurer (bouton "Changer de source" de MainActivity).
        if (!forceShow && app.playlistRepository.hasActiveProfile()) {
            goToMain()
            return
        }

        checkForAppUpdate()
        setupProfilesList()
        setupTypeCards()
        setupForms()
    }

    private fun setupProfilesList() {
        profileAdapter = ProfileAdapter(
            onClick = { profile -> loadProfile(profile.id) },
            onDelete = { profile -> confirmDelete(profile) }
        )
        binding.rvProfiles.adapter = profileAdapter

        lifecycleScope.launch {
            (application as IptvApplication).playlistRepository.getProfiles().collect { profiles ->
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
            Triple(binding.cardTypeUrl, binding.cardTypeUrlRing, binding.formUrl),
            Triple(binding.cardTypeFile, binding.cardTypeFileRing, binding.formFile),
            Triple(binding.cardTypeXtream, binding.cardTypeXtreamRing, binding.formXtream)
        )
        cards.forEach { (card, ring, form) ->
            card.setOnClickListener {
                // Un seul formulaire visible à la fois.
                cards.forEach { (_, _, f) -> f.visibility = if (f === form) View.VISIBLE else View.GONE }
            }
            applyRing(card, ring, 1.04f)
        }
    }

    private fun setupForms() {
        binding.btnLoadUrl.setOnClickListener {
            val name = binding.etUrlName.text.toString().trim()
            val url = binding.etM3uUrl.text.toString().trim()
            if (name.isBlank() || url.isBlank()) {
                showStatus(getString(R.string.setup_error_empty_url)); return@setOnClickListener
            }
            setLoading(true)
            lifecycleScope.launch {
                val app = application as IptvApplication
                val id = app.playlistRepository.saveM3uUrlProfile(name, url)
                loadProfile(id)
            }
        }

        binding.btnPickFile.setOnClickListener { pickFileLauncher.launch(arrayOf("*/*")) }

        binding.btnLoadXtream.setOnClickListener {
            val name = binding.etXtreamName.text.toString().trim()
            val host = binding.etXtreamHost.text.toString().trim()
            val user = binding.etXtreamUser.text.toString().trim()
            val pass = binding.etXtreamPass.text.toString()
            if (name.isBlank() || host.isBlank() || user.isBlank() || pass.isBlank()) {
                showStatus(getString(R.string.setup_error_empty_xtream)); return@setOnClickListener
            }
            setLoading(true)
            lifecycleScope.launch {
                val app = application as IptvApplication
                val id = app.playlistRepository.saveXtreamProfile(name, host, user, pass)
                loadProfile(id)
            }
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
        if (binding.etFileName.text.isNullOrBlank()) {
            binding.etFileName.setText(fileName.substringAfterLast('/').substringBeforeLast('.'))
        }

        val name = binding.etFileName.text.toString().trim()
        if (name.isBlank()) { showStatus(getString(R.string.setup_error_empty_name)); return }
        setLoading(true)
        lifecycleScope.launch {
            val app = application as IptvApplication
            val id = app.playlistRepository.saveM3uFileProfile(name, uri.toString())
            loadProfile(id)
        }
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
        listOf(binding.btnPickFile, binding.btnLoadUrl, binding.btnLoadXtream).forEach { it.isEnabled = !loading }
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
