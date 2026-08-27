package com.nicotv.iptv.ui.users

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.nicotv.iptv.data.network.ApiUser
import com.nicotv.iptv.databinding.ActivityUsersBinding
import com.nicotv.iptv.databinding.DialogCreateUserBinding
import com.nicotv.iptv.databinding.DialogPasswordBinding

/** Écran réservé à l'admin : créer / supprimer des comptes, réinitialiser les mots de passe. */
class UsersActivity : com.nicotv.iptv.ui.common.BaseActivity() {

    private lateinit var binding: ActivityUsersBinding
    private lateinit var viewModel: UsersViewModel
    private lateinit var adapter: UsersAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUsersBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel = ViewModelProvider(this)[UsersViewModel::class.java]

        adapter = UsersAdapter(
            onReset = { showResetPasswordDialog(it) },
            onDelete = { confirmDelete(it) }
        )
        binding.rvUsers.layoutManager = LinearLayoutManager(this)
        binding.rvUsers.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        // Icône + anneau blanc tournant (RotatingBorderView), même pattern que la
        // fiche détail (avant : bouton texte "Retour").
        binding.btnBack.setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) 1.25f else 1f)
                .scaleY(if (hasFocus) 1.25f else 1f)
                .setDuration(150).start()
            v.z = if (hasFocus) 10f else 0f
            binding.btnBackRing.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
            if (hasFocus) binding.btnBackRing.startAnim() else binding.btnBackRing.stopAnim()
        }
        binding.btnAddUser.setOnClickListener { showCreateUserDialog() }
        binding.btnChangeMyPw.setOnClickListener { showChangeMyPasswordDialog() }

        viewModel.users.observe(this) { users ->
            adapter.submitList(users)
            binding.tvEmpty.visibility = if (users.isEmpty()) View.VISIBLE else View.GONE
        }
        viewModel.message.observe(this) { msg ->
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        viewModel.loadUsers()
    }

    private fun showCreateUserDialog() {
        val dialogBinding = DialogCreateUserBinding.inflate(layoutInflater)
        AlertDialog.Builder(this)
            .setTitle(getString(com.nicotv.iptv.R.string.users_new_title))
            .setView(dialogBinding.root)
            .setPositiveButton(getString(com.nicotv.iptv.R.string.users_btn_create)) { _, _ ->
                val username = dialogBinding.etUsername.text.toString().trim()
                val password = dialogBinding.etPassword.text.toString()
                val isAdmin = dialogBinding.cbIsAdmin.isChecked
                if (username.isNotBlank() && password.length >= 4) {
                    viewModel.createUser(username, password, isAdmin)
                } else {
                    Toast.makeText(this, "Identifiant requis, mot de passe 4+ caractères", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(com.nicotv.iptv.R.string.users_btn_cancel), null)
            .show()
    }

    private fun showResetPasswordDialog(user: ApiUser) {
        val dialogBinding = DialogPasswordBinding.inflate(layoutInflater)
        dialogBinding.etOldPassword.visibility = View.GONE
        AlertDialog.Builder(this)
            .setTitle("${getString(com.nicotv.iptv.R.string.users_btn_reset_pw)} — ${user.username}")
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newPw = dialogBinding.etNewPassword.text.toString()
                if (newPw.length >= 4) viewModel.resetPassword(user.id, newPw)
                else Toast.makeText(this, "Mot de passe 4+ caractères", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(com.nicotv.iptv.R.string.users_btn_cancel), null)
            .show()
    }

    private fun showChangeMyPasswordDialog() {
        val dialogBinding = DialogPasswordBinding.inflate(layoutInflater)
        dialogBinding.etOldPassword.visibility = View.VISIBLE
        AlertDialog.Builder(this)
            .setTitle(getString(com.nicotv.iptv.R.string.users_change_my_pw))
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val old = dialogBinding.etOldPassword.text.toString()
                val new = dialogBinding.etNewPassword.text.toString()
                if (new.length >= 4) viewModel.changeMyPassword(old, new)
                else Toast.makeText(this, "Mot de passe 4+ caractères", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(com.nicotv.iptv.R.string.users_btn_cancel), null)
            .show()
    }

    private fun confirmDelete(user: ApiUser) {
        AlertDialog.Builder(this)
            .setTitle(user.username)
            .setMessage(getString(com.nicotv.iptv.R.string.users_confirm_delete))
            .setPositiveButton(getString(com.nicotv.iptv.R.string.users_btn_delete)) { _, _ ->
                viewModel.deleteUser(user.id)
            }
            .setNegativeButton(getString(com.nicotv.iptv.R.string.users_btn_cancel), null)
            .show()
    }
}
