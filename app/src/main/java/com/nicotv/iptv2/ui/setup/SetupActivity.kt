package com.nicotv.iptv2.ui.setup

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
 * recharger l'un d'eux — et 2 cartes pour en ajouter un nouveau : "Charger
 * votre playlist" (URL M3U ou fichier local, un seul formulaire) et "Xtream
 * Codes". Chaque carte ouvre son formulaire dans une page complète dédiée
 * ([AddPlaylistActivity]/[AddXtreamActivity], 29/08/2026 — remplace l'ancien
 * `AlertDialog` centré, demande explicite) avec sa propre flèche retour vers
 * cet écran.
 *
 * Depuis 1.0.20 : saut automatique vers l'accueil si un profil actif valide
 * existe déjà (cf. maybeAutoLoadLastProfile) — cet écran de choix n'est donc
 * plus TOUJOURS visible au lancement, seulement quand il n'y a rien à
 * charger automatiquement, ou explicitement via Réglages → "Changer de
 * source" (SettingsActivity, EXTRA_FORCE_SHOW).
 */
class SetupActivity : BaseActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var profileAdapter: ProfileAdapter

    // Piloté par maybeAutoLoadLastProfile() : garde le splash affiché tant que
    // la vérification "profil actif valide ?" n'a pas répondu, pour ne jamais
    // laisser apparaître un flash de l'écran de choix avant un saut auto vers
    // l'accueil.
    private var keepSplashOn = true

    override fun onCreate(savedInstanceState: Bundle?) {
        // installSplashScreen() DOIT être appelé avant super.onCreate() — c'est
        // lui qui bascule le thème de la fenêtre de Theme.IPTV.Splash (déclaré
        // dans le manifeste, chrome clair par défaut de Theme.SplashScreen) vers
        // postSplashScreenTheme (Theme.IPTV, sombre). Jamais appelé jusqu'ici :
        // l'activité restait bloquée sur le thème splash toute sa durée de vie
        // → bandeau clair système avec le nom de l'app, visible en permanence
        // sur cet écran (seul point d'entrée MAIN/LAUNCHER de l'app).
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { keepSplashOn }
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupHeader()
        setupProfilesList()
        setupTypeCards()
        maybeAutoLoadLastProfile()
    }

    /** Flèche retour visible seulement si on arrive via Réglages > "Changer de
     * source" (EXTRA_FORCE_SHOW) — au lancement normal (aucun profil actif),
     * il n'y a rien à quoi revenir. Pas d'accès Réglages sur cet écran
     * (retiré 29/08/2026, demande explicite après 2 essais — icône puis
     * texte — tous deux rejetés) : seul `MainActivity` l'ouvre. */
    private fun setupHeader() {
        binding.btnBack.visibility = if (intent.getBooleanExtra(EXTRA_FORCE_SHOW, false)) View.VISIBLE else View.GONE
        binding.btnBack.setOnClickListener { finish() }
        applyRing(binding.btnBack, binding.btnBackRing, 1.2f)
    }

    /** Saut automatique et rapide vers l'accueil si un profil actif valide
     * existe déjà (comme IPTV Smarters Pro) : le catalogue est déjà en cache
     * Room, pas d'attente réseau (le rafraîchissement si périmé se fait en
     * fond, cf. MainActivity.onStart → refreshActiveProfileIfStale). L'écran
     * de choix reste accessible via Réglages → "Changer de source", qui passe
     * EXTRA_FORCE_SHOW pour désactiver ce saut — sans ça, cliquer "Changer de
     * source" rebondirait immédiatement sur l'accueil (même bug que l'ancien
     * raccourci retiré : l'écran de sélection devenait inaccessible).
     *
     * ⚠️ `checkForAppUpdate()` appelé seulement quand on RESTE sur cet écran
     * (corrigé 28/08/2026, régression du jour même) : la vérif Room (quasi
     * instantanée) finissait toujours avant la réponse réseau du check MAJ —
     * `goToMain()`/`finish()` s'exécutait, puis `checkForAppUpdate()` trouvait
     * `isFinishing == true` et abandonnait silencieusement. Pire, le throttle
     * partagé (2 min, `UpdateManager.lastUpdateCheck`) était déjà consommé →
     * `MainActivity.onStart()` sautait aussi son propre appel juste après :
     * plus aucun écran ne proposait la MAJ. En cas de saut, on laisse
     * `MainActivity.onStart()` faire CE check, à froid (throttle intact,
     * activité stable, pas en cours de finish()). */
    private fun maybeAutoLoadLastProfile() {
        if (intent.getBooleanExtra(EXTRA_FORCE_SHOW, false)) {
            keepSplashOn = false
            checkForAppUpdate()
            return
        }
        lifecycleScope.launch {
            val hasActiveProfile = (application as IptvApplication).playlistRepository.hasValidActiveProfile()
            keepSplashOn = false
            if (hasActiveProfile) goToMain() else checkForAppUpdate()
        }
    }

    private fun setupProfilesList() {
        profileAdapter = ProfileAdapter(
            onClick = { profile -> loadProfile(profile.id) },
            onEdit = { profile -> editProfile(profile) },
            onDelete = { profile -> confirmDelete(profile) }
        )
        // Horizontal : cartes façon sélecteur de profil (Netflix-like), pas une
        // liste verticale de lignes — cf. item_profile.xml.
        binding.rvProfiles.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(this, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)
        binding.rvProfiles.clipToPadding = false
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
                // Titre "Profils" masqué tant qu'aucun profil n'existe (29/08/2026,
                // demande explicite) — rien à "Profils" au sens propre avant le tout
                // premier enregistrement.
                binding.tvScreenTitle.visibility = if (profiles.isEmpty()) View.GONE else View.VISIBLE
                // activeProfileId n'est pas un champ de PlaylistProfileEntity : un
                // changement de profil actif seul (sans changement de LISTE) ne
                // serait pas détecté par le DiffUtil de l'adapter → notifyDataSetChanged
                // explicite après l'avoir mis à jour, submitList seul ne suffit pas ici.
                profileAdapter.activeProfileId = (application as IptvApplication).playlistRepository.getActiveProfile()?.id
                profileAdapter.submitList(profiles)
                profileAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun confirmDelete(profile: PlaylistProfileEntity) {
        val isActive = profile.id == profileAdapter.activeProfileId
        val message = buildString {
            append(getString(R.string.setup_delete_confirm_message, profile.name))
            if (isActive) append("\n\n").append(getString(R.string.setup_delete_confirm_active_note))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.setup_delete_confirm_title)
            .setMessage(message)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch { (application as IptvApplication).playlistRepository.deleteProfile(profile.id) }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog)
        dialog.setOnShowListener {
            // Rouge = action destructive/irréversible, pas la même couleur que
            // "Annuler" — sans ça les 2 boutons se ressemblent trop (couleur
            // d'accent par défaut du thème sur les deux).
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(this, R.color.error))
        }
        dialog.show()
    }

    private fun setupTypeCards() {
        val cards = listOf(
            Triple(binding.cardTypePlaylist, binding.cardTypePlaylistRing) {
                startActivity(Intent(this, AddPlaylistActivity::class.java))
            },
            Triple(binding.cardTypeXtream, binding.cardTypeXtreamRing) {
                startActivity(Intent(this, AddXtreamActivity::class.java))
            }
        )
        cards.forEach { (card, ring, onOpen) ->
            card.setOnClickListener { onOpen() }
            applyRing(card, ring, 1.04f)
        }
    }

    /** Ouvre la page correspondante, pré-remplie avec les valeurs actuelles du
     * profil (bouton crayon sur "Mes profils"). */
    private fun editProfile(profile: PlaylistProfileEntity) {
        when (profile.type) {
            "M3U_URL", "M3U_FILE" -> startActivity(
                Intent(this, AddPlaylistActivity::class.java).apply {
                    putExtra(AddPlaylistActivity.EXTRA_EDIT_ID, profile.id)
                    putExtra(AddPlaylistActivity.EXTRA_EDIT_NAME, profile.name)
                    putExtra(AddPlaylistActivity.EXTRA_EDIT_URL, profile.m3uUrl)
                    putExtra(AddPlaylistActivity.EXTRA_EDIT_FILE_URI, profile.m3uFileUri)
                }
            )
            "XTREAM" -> startActivity(
                Intent(this, AddXtreamActivity::class.java).apply {
                    putExtra(AddXtreamActivity.EXTRA_EDIT_ID, profile.id)
                    putExtra(AddXtreamActivity.EXTRA_EDIT_NAME, profile.name)
                    putExtra(AddXtreamActivity.EXTRA_EDIT_HOST, profile.xtreamHost)
                    putExtra(AddXtreamActivity.EXTRA_EDIT_USER, profile.xtreamUsername)
                    putExtra(AddXtreamActivity.EXTRA_EDIT_PASS, profile.xtreamPassword)
                }
            )
        }
    }

    private fun loadProfile(profileId: Long) {
        setLoading(true)
        lifecycleScope.launch {
            val result = (application as IptvApplication).playlistRepository.loadProfile(profileId)
            setLoading(false)
            result.onSuccess { count ->
                showStatus(getString(R.string.setup_success, count))
                // Nouveau catalogue chargé : le fond aléatoire de l'accueil ne doit
                // pas garder une jaquette de l'ancienne source.
                com.nicotv.iptv2.ui.main.MainActivity.resetHomeBg()
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
