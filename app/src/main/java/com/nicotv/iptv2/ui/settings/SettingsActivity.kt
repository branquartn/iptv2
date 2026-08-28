package com.nicotv.iptv2.ui.settings

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.nicotv.iptv2.IptvApplication
import com.nicotv.iptv2.R
import com.nicotv.iptv2.data.ImageCacheUtil
import com.nicotv.iptv2.databinding.ActivitySettingsBinding
import com.nicotv.iptv2.ui.common.BaseActivity
import com.nicotv.iptv2.ui.setup.SetupActivity
import kotlinx.coroutines.launch

/** Écran "Réglages", ouvert depuis l'icône engrenage de l'accueil — gestion
 * du profil actif (renvoie vers SetupActivity pour ajouter/éditer/supprimer,
 * cf. son en-tête) et des 3 caches de l'app (images Coil, catalogue playlist,
 * mini-guide EPG). Pas de compte, rien d'autre à régler. */
class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnBack.setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) 1.25f else 1f).scaleY(if (hasFocus) 1.25f else 1f).setDuration(150).start()
            binding.btnBackRing.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
            if (hasFocus) binding.btnBackRing.startAnim() else binding.btnBackRing.stopAnim()
        }

        binding.btnChangeSource.setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java).apply {
                putExtra(SetupActivity.EXTRA_FORCE_SHOW, true)
            })
        }

        binding.btnRefreshCatalog.setOnClickListener { refreshCatalog() }
        binding.btnClearImageCache.setOnClickListener {
            ImageCacheUtil.clear(this)
            Toast.makeText(this, R.string.settings_cache_cleared, Toast.LENGTH_SHORT).show()
        }
        binding.btnClearEpgCache.setOnClickListener {
            lifecycleScope.launch {
                (application as IptvApplication).playlistRepository.clearEpgCache()
                Toast.makeText(this@SettingsActivity, R.string.settings_cache_cleared, Toast.LENGTH_SHORT).show()
            }
        }

        updateLastUpdateLabel()
    }

    override fun onResume() {
        super.onResume()
        // Le profil actif a pu changer entre-temps (retour de SetupActivity
        // après "Changer de source").
        updateLastUpdateLabel()
    }

    private fun updateLastUpdateLabel() {
        lifecycleScope.launch {
            val profile = (application as IptvApplication).playlistRepository.getActiveProfile()
            binding.tvLastUpdate.text = if (profile == null) {
                getString(R.string.settings_no_active_profile)
            } else {
                val relative = DateUtils.getRelativeTimeSpanString(
                    profile.lastUsedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
                )
                getString(R.string.settings_last_update, relative)
            }
        }
    }

    private fun refreshCatalog() {
        setLoading(true)
        lifecycleScope.launch {
            val app = application as IptvApplication
            val profile = app.playlistRepository.getActiveProfile()
            if (profile == null) {
                setLoading(false)
                Toast.makeText(this@SettingsActivity, R.string.settings_no_active_profile, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val result = app.playlistRepository.loadProfile(profile.id)
            setLoading(false)
            updateLastUpdateLabel()
            result.onSuccess {
                Toast.makeText(this@SettingsActivity, R.string.settings_refresh_success, Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                Toast.makeText(this@SettingsActivity, e.message ?: getString(R.string.setup_error_generic), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressLoading.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnRefreshCatalog.isEnabled = !loading
    }
}
