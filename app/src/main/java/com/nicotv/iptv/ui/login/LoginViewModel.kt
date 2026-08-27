package com.nicotv.iptv.ui.login

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.nicotv.iptv.IptvApplication
import com.nicotv.iptv.data.network.LoginRequest
import com.nicotv.iptv.data.network.LoginResponse
import com.google.gson.Gson
import kotlinx.coroutines.launch
import retrofit2.HttpException

class LoginViewModel(application: Application) : AndroidViewModel(application) {

    private val authApi = (application as IptvApplication).authApi

    private val _loginState = MutableLiveData<LoginState>()
    val loginState: LiveData<LoginState> = _loginState

    fun login(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            _loginState.value = LoginState.Error("Veuillez remplir tous les champs")
            return
        }
        viewModelScope.launch {
            try {
                val res = authApi.login(body = LoginRequest(username, password))
                if (res.ok && res.token != null && res.user != null) {
                    _loginState.value = LoginState.Success(
                        username = res.user.username,
                        token = res.token,
                        isAdmin = res.user.is_admin
                    )
                } else {
                    _loginState.value = LoginState.Error(res.error ?: "Identifiants incorrects")
                }
            } catch (e: HttpException) {
                _loginState.value = LoginState.Error(parseError(e) ?: "Identifiants incorrects")
            } catch (e: Exception) {
                _loginState.value = LoginState.Error(
                    "Serveur injoignable. Vérifiez la connexion réseau."
                )
            }
        }
    }

    private fun parseError(e: HttpException): String? = try {
        e.response()?.errorBody()?.string()?.let { body ->
            Gson().fromJson(body, LoginResponse::class.java)?.error
        }
    } catch (_: Exception) {
        null
    }

    sealed class LoginState {
        data class Success(
            val username: String,
            val token: String,
            val isAdmin: Boolean
        ) : LoginState()
        data class Error(val message: String) : LoginState()
    }
}
