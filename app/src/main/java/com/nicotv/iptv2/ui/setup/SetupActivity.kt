package com.nicotv.iptv2.ui.setup

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.nicotv.iptv2.IptvApplication
import com.nicotv.iptv2.data.SourceType
import com.nicotv.iptv2.databinding.ActivitySetupBinding
import com.nicotv.iptv2.ui.common.BaseActivity
import com.nicotv.iptv2.ui.main.MainActivity
import com.nicotv.iptv2.update.checkForAppUpdate
import kotlinx.coroutines.launch

/**
 * Écran de démarrage : pas de compte, pas de login — juste le choix d'une
 * source à charger (fichier M3U local, URL M3U, ou identifiants Xtream Codes).
 * Lanceur de l'app tant qu'aucune source n'est configurée ; réutilisé ensuite
 * pour "Changer de source" depuis MainActivity (EXTRA_FORCE_SHOW).
 */
class SetupActivity : BaseActivity() {

    private lateinit var binding: ActivitySetupBinding

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onFilePicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as IptvApplication
        val forceShow = intent.getBooleanExtra(EXTRA_FORCE_SHOW, false)

        // Une source est déjà configurée (relance normale de l'app) : on saute
        // directement à l'accueil, sauf si l'utilisateur est venu volontairement
        // reconfigurer (bouton "Changer de source" de MainActivity).
        if (!forceShow && app.sourcePrefs.get().isConfigured) {
            goToMain()
            return
        }

        checkForAppUpdate()
        prefillExisting()
        setupListeners()
    }

    private fun prefillExisting() {
        val source = (application as IptvApplication).sourcePrefs.get()
        when (source.type) {
            SourceType.M3U_URL -> binding.etM3uUrl.setText(source.m3uUrl)
            SourceType.XTREAM -> {
                binding.etXtreamHost.setText(source.xtreamHost)
                binding.etXtreamUser.setText(source.xtreamUsername)
                binding.etXtreamPass.setText(source.xtreamPassword)
            }
            else -> Unit
        }
    }

    private fun setupListeners() {
        binding.btnPickFile.setOnClickListener {
            pickFileLauncher.launch(arrayOf("*/*"))
        }
        binding.btnLoadUrl.setOnClickListener {
            val url = binding.etM3uUrl.text.toString().trim()
            if (url.isBlank()) { showStatus(getString(com.nicotv.iptv2.R.string.setup_error_empty_url)); return@setOnClickListener }
            (application as IptvApplication).sourcePrefs.saveM3uUrl(url)
            loadCurrentSource()
        }
        binding.btnLoadXtream.setOnClickListener {
            val host = binding.etXtreamHost.text.toString().trim()
            val user = binding.etXtreamUser.text.toString().trim()
            val pass = binding.etXtreamPass.text.toString()
            if (host.isBlank() || user.isBlank() || pass.isBlank()) {
                showStatus(getString(com.nicotv.iptv2.R.string.setup_error_empty_xtream)); return@setOnClickListener
            }
            (application as IptvApplication).sourcePrefs.saveXtream(host, user, pass)
            loadCurrentSource()
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
        binding.tvFileName.visibility = View.VISIBLE
        binding.tvFileName.text = uri.lastPathSegment ?: uri.toString()
        (application as IptvApplication).sourcePrefs.saveM3uFile(uri.toString())
        loadCurrentSource()
    }

    private fun loadCurrentSource() {
        setLoading(true)
        lifecycleScope.launch {
            val result = (application as IptvApplication).playlistRepository.loadFromCurrentSource()
            setLoading(false)
            result.onSuccess { count ->
                showStatus(getString(com.nicotv.iptv2.R.string.setup_success, count))
                goToMain()
            }.onFailure { e ->
                showStatus(e.message ?: getString(com.nicotv.iptv2.R.string.setup_error_generic))
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


    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        const val EXTRA_FORCE_SHOW = "extra_force_show"
    }
}
