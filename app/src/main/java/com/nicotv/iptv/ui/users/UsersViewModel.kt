package com.nicotv.iptv.ui.users

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.nicotv.iptv.IptvApplication
import com.nicotv.iptv.data.network.ApiUser
import com.nicotv.iptv.data.network.ChangePasswordRequest
import com.nicotv.iptv.data.network.CreateUserRequest
import com.nicotv.iptv.data.network.DeleteUserRequest
import com.nicotv.iptv.data.network.ResetPasswordRequest
import kotlinx.coroutines.launch

class UsersViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as IptvApplication
    private val authApi = app.authApi
    private val bearer get() = app.sessionManager.bearer()

    private val _users = MutableLiveData<List<ApiUser>>()
    val users: LiveData<List<ApiUser>> = _users

    private val _message = MutableLiveData<String>()
    val message: LiveData<String> = _message

    fun loadUsers() {
        viewModelScope.launch {
            try {
                val res = authApi.listUsers(bearer = bearer)
                if (res.ok) _users.value = res.users ?: emptyList()
                else _message.value = res.error ?: "Erreur de chargement"
            } catch (e: Exception) {
                _message.value = "Serveur injoignable"
            }
        }
    }

    fun createUser(username: String, password: String, isAdmin: Boolean) {
        viewModelScope.launch {
            try {
                val name = username.trim()
                val res = authApi.createUser(
                    bearer = bearer,
                    body = CreateUserRequest(name, password, isAdmin)
                )
                if (res.ok) {
                    _message.value = "Utilisateur créé"
                    loadUsers()
                } else {
                    _message.value = res.error ?: "Échec de la création"
                }
            } catch (e: Exception) {
                _message.value = "Serveur injoignable"
            }
        }
    }

    fun resetPassword(id: Int, newPassword: String) {
        viewModelScope.launch {
            try {
                val res = authApi.resetPassword(
                    bearer = bearer,
                    body = ResetPasswordRequest(id, newPassword)
                )
                _message.value = if (res.ok) "Mot de passe réinitialisé" else (res.error ?: "Échec")
            } catch (e: Exception) {
                _message.value = "Serveur injoignable"
            }
        }
    }

    fun deleteUser(id: Int) {
        viewModelScope.launch {
            try {
                val res = authApi.deleteUser(bearer = bearer, body = DeleteUserRequest(id))
                if (res.ok) { _message.value = "Utilisateur supprimé"; loadUsers() }
                else _message.value = res.error ?: "Échec de la suppression"
            } catch (e: Exception) {
                _message.value = "Serveur injoignable"
            }
        }
    }

    fun changeMyPassword(old: String, new: String) {
        viewModelScope.launch {
            try {
                val res = authApi.changePassword(
                    bearer = bearer,
                    body = ChangePasswordRequest(old, new)
                )
                _message.value = if (res.ok) "Mot de passe modifié" else (res.error ?: "Échec")
            } catch (e: Exception) {
                _message.value = "Serveur injoignable"
            }
        }
    }
}
