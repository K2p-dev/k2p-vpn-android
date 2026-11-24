package space.k2p.data.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import space.k2p.data.api.model.AuthResponse
import space.k2p.data.api.model.EmailRegisterInitRequest
import space.k2p.data.api.model.EmailRegisterInitResponse
import space.k2p.data.api.model.EmailRegisterVerifyRequest
import space.k2p.data.api.model.LoginRequest
import space.k2p.data.api.model.TgCheckResponse
import space.k2p.data.api.model.TgInitResponse
import space.k2p.data.api.model.TgRegisterRequest
import space.k2p.data.api.model.TgRegisterResponse

interface AuthApiService {
    @POST("api/auth/login")
    suspend fun login(
        @Header("X-Device-HWID") hwid: String,
        @Header("X-Device-Name") deviceName: String,
        @Body request: LoginRequest
    ): Response<AuthResponse>

    @POST("api/auth/register/email/init")
    suspend fun registerEmailInit(
        @Body request: EmailRegisterInitRequest
    ): Response<EmailRegisterInitResponse>

    @POST("api/auth/register/email/verify")
    suspend fun registerEmailVerify(
        @Header("X-Device-HWID") hwid: String,
        @Header("X-Device-Name") deviceName: String,
        @Body request: EmailRegisterVerifyRequest
    ): Response<AuthResponse>

    @POST("api/auth/register")
    suspend fun registerWithTelegram(
        @Body request: TgRegisterRequest
    ): Response<TgRegisterResponse>

    @POST("api/auth/tg/init")
    suspend fun initTelegramAuth(): Response<TgInitResponse>

    @GET("api/auth/tg/check")
    suspend fun checkTelegramAuth(
        @Query("token") token: String,
        @Header("X-Device-HWID") hwid: String,
        @Header("X-Device-Name") deviceName: String
    ): Response<TgCheckResponse>

    @POST("api/auth/logout")
    suspend fun logout(): Response<Unit>
}
