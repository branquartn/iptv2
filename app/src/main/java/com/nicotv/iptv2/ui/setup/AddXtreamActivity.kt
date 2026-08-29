package com.nicotv.iptv2.ui.setup

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.nicotv.iptv2.IptvApplication
import com.nicotv.iptv2.R
import com.nicotv.iptv2.databinding.ActivityAddXtreamBinding
import com.nicotv.iptv2.ui.common.BaseActivity
import com.nicotv.iptv2.ui.common.LoadingDialog
import com.nicotv.iptv2.ui.main.MainActivity
import kotlinx.coroutines.launch

/** Page complète "Xtream Codes" (29/08/2026, demande explicite — remplace
 * l'ancien `AlertDialog` centré affiché par-dessus l'écran Profils dans
 * `SetupActivity`). Lancée par la carte Xtream de Profils (nouveau profil)
 * ou par le crayon d'un profil Xtream existant (édition, pré-remplie via les
 * extras `EXTRA_EDIT_*`). Flèche retour = annuler, ramène sur Profils sans
 * rien enregistrer ; le formulaire reste ouvert en cas d'erreur. */
class AddXtreamActivity : BaseActivity() {

    private lateinit var binding: ActivityAddXtreamBinding
    private var editingId: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddXtreamBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupHeader()

        editingId = intent.getLongExtra(EXTRA_EDIT_ID, 0)
        if (editingId != 0L) {
            binding.etXtreamName.setText(intent.getStringExtra(EXTRA_EDIT_NAME))
            binding.etXtreamHost.setText(intent.getStringExtra(EXTRA_EDIT_HOST))
            binding.etXtreamUser.setText(intent.getStringExtra(EXTRA_EDIT_USER))
            binding.etXtreamPass.setText(intent.getStringExtra(EXTRA_EDIT_PASS))
        }

        binding.btnLoadXtream.setOnClickListener { submit() }
    }

    private fun setupHeader() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnBack.setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) 1.2f else 1f).scaleY(if (hasFocus) 1.2f else 1f).setDuration(150).start()
            binding.btnBackRing.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
            if (hasFocus) binding.btnBackRing.startAnim() else binding.btnBackRing.stopAnim()
        }
    }

    private fun submit() {
        val name = binding.etXtreamName.text.toString().trim()
        val host = binding.etXtreamHost.text.toString().trim()
        val user = binding.etXtreamUser.text.toString().trim()
        val pass = binding.etXtreamPass.text.toString()
        if (name.isBlank() || host.isBlank() || user.isBlank() || pass.isBlank()) {
            if (name.isBlank()) binding.etXtreamName.error = getString(R.string.setup_error_empty_name)
            if (host.isBlank()) binding.etXtreamHost.error = getString(R.string.setup_error_empty_name)
            if (user.isBlank()) binding.etXtreamUser.error = getString(R.string.setup_error_empty_name)
            if (pass.isBlank()) binding.etXtreamPass.error = getString(R.string.setup_error_empty_name)
            Toast.makeText(this, R.string.setup_error_empty_xtream, Toast.LENGTH_LONG).show()
            return
        }
        setLoading(true)
        lifecycleScope.launch {
            val app = application as IptvApplication
            val id = app.playlistRepository.saveXtreamProfile(name, host, user, pass, editingId)
            loadProfile(id)
        }
    }

    private fun loadProfile(profileId: Long) {
        lifecycleScope.launch {
            val result = (application as IptvApplication).playlistRepository.loadProfile(profileId) { pct, msg ->
                loadingDialog?.onProgress(pct, msg)
            }
            setLoading(false)
            result.onSuccess { count ->
                showStatus(getString(R.string.setup_success, count))
                MainActivity.resetHomeBg()
                startActivity(Intent(this@AddXtreamActivity, MainActivity::class.java))
                finish()
            }.onFailure { e ->
                showStatus(e.message ?: getString(R.string.setup_error_generic))
            }
        }
    }

    // Rond de chargement au milieu de l'écran, avec pourcentage (29/08/2026,
    // demande explicite "au milieu pas en bas... indique chargement en
    // cours" puis "avec un pourcentage ça serait bien") — cf. LoadingDialog,
    // même correctif sur AddPlaylistActivity.
    private var loadingDialog: LoadingDialog? = null

    private fun setLoading(loading: Boolean) {
        if (loading) {
            loadingDialog = LoadingDialog(this).also { it.show() }
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
        const val EXTRA_EDIT_HOST = "extra_edit_host"
        const val EXTRA_EDIT_USER = "extra_edit_user"
        const val EXTRA_EDIT_PASS = "extra_edit_pass"
    }
}
