package com.nicotv.iptv2.account

import com.nicotv.iptv2.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Un code Xtream attribué à l'utilisateur par l'admin (admin.nicotv.ovh). */
data class RemoteAccount(val id: Long, val label: String, val host: String, val username: String, val password: String)

/** Appels vers api.nicotv.ovh : login (mêmes comptes que NicoTV), renouvellement du jeton, codes Xtream. */
class AccountApi(private val client: OkHttpClient, private val session: SessionManager) {

    private val endpoint = AppConfig.Account.BASE_URL + "index.php"
    private val json = "application/json; charset=utf-8".toMediaType()

    sealed class LoginResult {
        data class Ok(val token: String, val username: String, val isAdmin: Boolean) : LoginResult()
        data class Error(val message: String) : LoginResult()
    }

    sealed class Fetch<out T> {
        data class Ok<T>(val value: T) : Fetch<T>()
        object Unauthorized : Fetch<Nothing>()
        object Failure : Fetch<Nothing>()
    }

    suspend fun login(username: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("username", username).put("password", password).toString().toRequestBody(json)
            val req = Request.Builder().url("$endpoint?action=login").post(body).build()
            client.newCall(req).execute().use { r ->
                val j = runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrNull()
                if (j != null && j.optBoolean("ok")) {
                    val u = j.optJSONObject("user")
                    LoginResult.Ok(j.optString("token"), u?.optString("username").takeUnless { it.isNullOrBlank() } ?: username,
                        u?.optBoolean("is_admin") ?: false)
                } else {
                    LoginResult.Error(j?.optString("error")?.takeIf { it.isNotBlank() } ?: "Connexion impossible (code ${r.code})")
                }
            }
        } catch (e: Exception) {
            LoginResult.Error("Pas de connexion au serveur")
        }
    }

    /** Renouvelle le jeton (valable 30 jours) : à appeler régulièrement tant que l'app est utilisée. */
    suspend fun refreshToken(): Fetch<Unit> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$endpoint?action=refresh_token").header("Authorization", session.bearer()).get().build()
            client.newCall(req).execute().use { r ->
                if (r.code == 401) return@withContext Fetch.Unauthorized
                val j = runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrNull()
                if (j != null && j.optBoolean("ok") && j.optString("token").isNotBlank()) {
                    session.updateToken(j.optString("token")); Fetch.Ok(Unit)
                } else Fetch.Failure
            }
        } catch (e: Exception) { Fetch.Failure }
    }

    /** Codes Xtream ACTIFS de l'utilisateur, dans l'ordre et sous les noms choisis par l'admin. */
    suspend fun xtreamAccounts(): Fetch<List<RemoteAccount>> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$endpoint?action=xtream_list").header("Authorization", session.bearer()).get().build()
            client.newCall(req).execute().use { r ->
                if (r.code == 401) return@withContext Fetch.Unauthorized
                val j = runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrNull()
                if (j == null || !j.optBoolean("ok")) return@withContext Fetch.Failure
                val arr = j.optJSONArray("accounts")
                val list = (0 until (arr?.length() ?: 0)).map {
                    val o = arr!!.getJSONObject(it)
                    RemoteAccount(o.optLong("id"), o.optString("label"), o.optString("host"), o.optString("username"), o.optString("password"))
                }
                Fetch.Ok(list)
            }
        } catch (e: Exception) { Fetch.Failure }
    }
}
