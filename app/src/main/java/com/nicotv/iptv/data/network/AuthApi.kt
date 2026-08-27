package com.nicotv.iptv.data.network

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

data class LoginRequest(val username: String, val password: String)
data class ApiUser(
    val id: Int,
    val username: String,
    val is_admin: Boolean
)
data class LoginResponse(val ok: Boolean, val token: String?, val user: ApiUser?, val error: String?)
data class UsersResponse(val ok: Boolean, val users: List<ApiUser>?, val error: String?)
data class SimpleResponse(val ok: Boolean, val error: String?)
data class CreateUserRequest(val username: String, val password: String, val is_admin: Boolean)
data class CreateUserResponse(val ok: Boolean, val user: ApiUser?, val error: String?)
data class ChangePasswordRequest(val old: String, val new: String)
data class ResetPasswordRequest(val id: Int, val password: String)
data class DeleteUserRequest(val id: Int)

interface AuthApi {

    @POST("index.php")
    suspend fun login(
        @Query("action") action: String = "login",
        @Body body: LoginRequest
    ): LoginResponse

    @POST("index.php")
    suspend fun changePassword(
        @Query("action") action: String = "change_password",
        @Header("Authorization") bearer: String,
        @Body body: ChangePasswordRequest
    ): SimpleResponse

    @GET("index.php")
    suspend fun listUsers(
        @Query("action") action: String = "users",
        @Header("Authorization") bearer: String
    ): UsersResponse

    @POST("index.php")
    suspend fun createUser(
        @Query("action") action: String = "create_user",
        @Header("Authorization") bearer: String,
        @Body body: CreateUserRequest
    ): CreateUserResponse

    @POST("index.php")
    suspend fun resetPassword(
        @Query("action") action: String = "reset_password",
        @Header("Authorization") bearer: String,
        @Body body: ResetPasswordRequest
    ): SimpleResponse

    @POST("index.php")
    suspend fun deleteUser(
        @Query("action") action: String = "delete_user",
        @Header("Authorization") bearer: String,
        @Body body: DeleteUserRequest
    ): SimpleResponse
}
