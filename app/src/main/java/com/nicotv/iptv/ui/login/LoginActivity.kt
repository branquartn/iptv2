package com.nicotv.iptv.ui.login

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import com.nicotv.iptv.IptvApplication
import com.nicotv.iptv.R
import com.nicotv.iptv.databinding.ActivityLoginBinding
import com.nicotv.iptv.ui.main.MainActivity
import com.nicotv.iptv.update.checkForAppUpdate

class LoginActivity : com.nicotv.iptv.ui.common.BaseActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var viewModel: LoginViewModel
    private val autoSubmitHandler = Handler(Looper.getMainLooper())
    private val app by lazy { application as IptvApplication }
    private var splashStartMs = 0L
    private var passwordVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        splashStartMs = SystemClock.elapsedRealtime()
        installSplashScreen()

        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel = ViewModelProvider(this)[LoginViewModel::class.java]

        if (app.sessionManager.isLoggedIn()) {
            // Empêche le focus auto (premier View focusable de la mise en page,
            // comportement par défaut Android à la création de la fenêtre) d'atterrir
            // sur un champ texte : le clavier système est une fenêtre à part, AU-DESSUS
            // du splash overlay quel que soit ce qui est affiché dessous — sur TV, un
            // clavier visible + la page login qui flashe avant la redirection auto,
            // même quand setupUI() (qui appelait requestFocus) n'est jamais exécuté ici.
            binding.etUsername.isFocusable = false
            binding.etPassword.isFocusable = false
            // PAS de hideSplashAfterDelay() ici : l'overlay et la redirection étaient
            // programmés au MÊME délai (SPLASH_MIN_DURATION_MS) — l'overlay commençait
            // à disparaître (fade 250ms) juste avant/pendant que l'activité se ferme,
            // révélant une frame du vrai formulaire de login en dessous. Déjà connecté :
            // l'overlay reste opaque jusqu'au finish() de navigateToMain(), le formulaire
            // n'est jamais visible.
            goToMain()
        } else {
            hideSplashAfterDelay()
            setupUI()
            observeViewModel()
            // Vérifie l'OTA même sans être connecté : version.json est public, et ça
            // évite qu'un bug bloquant le login (cf. décodeurs bas de gamme) empêche
            // aussi de recevoir le correctif.
            checkForAppUpdate()
        }
    }

    private fun hideSplashAfterDelay() {
        val remaining = SPLASH_MIN_DURATION_MS - (SystemClock.elapsedRealtime() - splashStartMs)
        autoSubmitHandler.postDelayed({
            val overlay = binding.splashOverlay
            if (overlay.isVisible) {
                // Désactivé immédiatement (pas seulement à la fin de l'animation) : sur du
                // hardware lent/custom (ex. décodeurs TV bas de gamme), le withEndAction d'un
                // ViewPropertyAnimator peut ne jamais se déclencher — l'overlay resterait alors
                // invisible mais toujours clickable/focusable, bloquant tout le formulaire de
                // login sans aucun message d'erreur.
                overlay.isClickable = false
                overlay.isFocusable = false
                overlay.animate().alpha(0f).setDuration(250L)
                    .withEndAction { overlay.isVisible = false }
                    .start()
            }
        }, remaining.coerceAtLeast(0L))
    }

    private fun setupUI() {
        binding.etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { attemptLogin(); true } else false
        }
        binding.etPassword.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                attemptLogin(); true
            } else false
        }
        binding.btnLogin.setOnClickListener { attemptLogin() }
        binding.btnTogglePassword.setOnClickListener { togglePasswordVisibility() }

        binding.etUsername.requestFocus()
    }

    private fun togglePasswordVisibility() {
        passwordVisible = !passwordVisible
        val cursor = binding.etPassword.selectionEnd
        binding.etPassword.inputType = InputType.TYPE_CLASS_TEXT or if (passwordVisible) {
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        binding.btnTogglePassword.setImageResource(
            if (passwordVisible) R.drawable.ic_eye else R.drawable.ic_eye_off
        )
        // Changer l'inputType remet le curseur à 0 : on le restaure.
        binding.etPassword.setSelection(cursor.coerceIn(0, binding.etPassword.text?.length ?: 0))
    }

    private fun observeViewModel() {
        viewModel.loginState.observe(this) { state ->
            when (state) {
                is LoginViewModel.LoginState.Success -> {
                    app.sessionManager.saveSession(state.username, state.token, state.isAdmin)
                    goToMain()
                }
                is LoginViewModel.LoginState.Error -> {
                    binding.tvError.text = state.message
                    binding.tvError.visibility = View.VISIBLE
                    binding.etPassword.text?.clear()
                    binding.etUsername.requestFocus()
                }
            }
        }
    }

    private fun attemptLogin() {
        autoSubmitHandler.removeCallbacksAndMessages(null)
        binding.tvError.visibility = View.GONE
        viewModel.login(
            binding.etUsername.text.toString().trim().lowercase(),
            binding.etPassword.text.toString().trim()
        )
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    private fun goToMain() {
        val remaining = SPLASH_MIN_DURATION_MS - (SystemClock.elapsedRealtime() - splashStartMs)
        if (remaining > 0) {
            autoSubmitHandler.postDelayed({ navigateToMain() }, remaining)
        } else {
            navigateToMain()
        }
    }

    private fun navigateToMain() {
        if (isFinishing || isDestroyed) return
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        autoSubmitHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val SPLASH_MIN_DURATION_MS = 2500L
    }
}
