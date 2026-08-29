package com.nicotv.iptv2.ui.settings

import android.app.AlertDialog
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
 * cf. son en-tête) et des caches de l'app (images Coil, catalogue playlist).
 * Pas de compte, rien d'autre à régler. */
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
        binding.btnContentLanguage.setOnClickListener { showContentLanguageDialog() }
        updateContentLanguageLabel()
        binding.btnClearImageCache.setOnClickListener {
            ImageCacheUtil.clear(this)
            Toast.makeText(this, R.string.settings_cache_cleared, Toast.LENGTH_SHORT).show()
            updateImageCacheSizeLabel()
        }

        updateLastUpdateLabel()
        updateImageCacheSizeLabel()
    }

    override fun onResume() {
        super.onResume()
        // Le profil actif a pu changer entre-temps (retour de SetupActivity
        // après "Changer de source").
        updateLastUpdateLabel()
        // La taille du cache a pu changer en naviguant dans l'app entre-temps
        // (jaquettes chargées sur d'autres écrans).
        updateImageCacheSizeLabel()
    }

    /** Taille actuelle du cache disque affichée sous "Vider le cache images"
     * (29/08/2026, demande explicite "voir aussi la taille") — avant, le
     * texte affichait seulement le plafond configuré (300 Mo) en dur, jamais
     * l'usage réel. */
    private fun updateImageCacheSizeLabel() {
        binding.tvImageCacheSize.text = getString(
            R.string.settings_clear_image_cache_sub_sized,
            ImageCacheUtil.diskCacheSizeLabel(this)
        )
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

    /** Chaque ViewModel (Movies/Series/Live) lit ce réglage une seule fois à sa
     * création (cf. leur commentaire "contentLanguage") — pas besoin d'être
     * réactif ici, un écran déjà ouvert n'est de toute façon jamais mis à jour
     * en direct par un changement de Réglages ailleurs dans l'app. */
    private fun updateContentLanguageLabel() {
        val code = (application as IptvApplication).contentLanguagePrefs.getLanguage()
        binding.tvContentLanguage.text = if (code == null) {
            getString(R.string.settings_content_language_all)
        } else {
            languageLabel(code)
        }
    }

    /** Liste dynamique (pas de "FR" figé) — demande explicite 28/08/2026 :
     * "regarde toutes les langues qu'il y a" plutôt qu'un choix Toutes/FR
     * câblé en dur. `repository.getAvailableContentLanguages()` scanne le
     * catalogue chargé (noms de chaîne + catégories films/séries, cf.
     * util.LanguageCode) — pas de liste connue à l'avance, chaque panel a ses
     * propres codes. */
    private fun showContentLanguageDialog() {
        lifecycleScope.launch {
            val prefs = (application as IptvApplication).contentLanguagePrefs
            val codes = (application as IptvApplication).playlistRepository.getAvailableContentLanguages()
            val options = (listOf(getString(R.string.settings_content_language_all)) + codes.map { languageLabel(it) })
                .toTypedArray()
            val current = codes.indexOf(prefs.getLanguage()).let { if (it < 0) 0 else it + 1 }
            val dialog = AlertDialog.Builder(this@SettingsActivity)
                .setTitle(R.string.settings_content_language)
                .setSingleChoiceItems(options, current) { d, which ->
                    prefs.setLanguage(if (which == 0) null else codes[which - 1])
                    updateContentLanguageLabel()
                    d.dismiss()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .create()
            dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog)
            dialog.show()
        }
    }

    /** "FR" → "FR — Français" pour les codes langue courants reconnus, sinon
     * le code brut tel quel (bouquet/pays pas forcément une langue — "AF",
     * "CA" côté panel réel, cf. util.LanguageCode). */
    private fun languageLabel(code: String): String =
        KNOWN_LANGUAGE_NAMES[code]?.let { "$code — $it" } ?: code

    companion object {
        private val KNOWN_LANGUAGE_NAMES = mapOf(
            "FR" to "Français", "EN" to "Anglais", "DE" to "Allemand",
            "ES" to "Espagnol", "IT" to "Italien", "PT" to "Portugais",
            "NL" to "Néerlandais", "PL" to "Polonais", "RU" to "Russe",
            "AR" to "Arabe", "TR" to "Turc"
        )
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
