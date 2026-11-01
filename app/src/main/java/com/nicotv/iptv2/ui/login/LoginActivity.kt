package com.nicotv.iptv2.ui.login

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.lifecycle.lifecycleScope
import com.nicotv.iptv2.IptvApplication
import com.nicotv.iptv2.account.AccountApi
import com.nicotv.iptv2.databinding.ActivityLoginBinding
import com.nicotv.iptv2.ui.common.BaseActivity
import com.nicotv.iptv2.ui.setup.SetupActivity
import kotlinx.coroutines.launch

/** Login (mêmes comptes que l'app iptv). Après connexion : écran de démarrage, qui récupère les codes Xtream. */
class LoginActivity : BaseActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val app get() = application as IptvApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnLogin.setOnClickListener { submit() }
        binding.etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        binding.etUsername.setText(app.sessionManager.getUsername())
    }

    private fun submit() {
        val user = binding.etUsername.text.toString().trim()
        val pass = binding.etPassword.text.toString()
        if (user.isEmpty() || pass.isEmpty()) { showError("Identifiant et mot de passe requis"); return }
        setBusy(true)
        lifecycleScope.launch {
            when (val r = app.accountApi.login(user, pass)) {
                is AccountApi.LoginResult.Ok -> {
                    app.sessionManager.save(r.username, r.token, r.isAdmin)
                    startActivity(Intent(this@LoginActivity, SetupActivity::class.java))
                    finish()
                }
                is AccountApi.LoginResult.Error -> { setBusy(false); showError(r.message) }
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.btnLogin.isEnabled = !busy
        binding.progressLogin.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) binding.tvLoginError.visibility = View.GONE
    }

    private fun showError(msg: String) {
        binding.tvLoginError.text = msg
        binding.tvLoginError.visibility = View.VISIBLE
    }
}
