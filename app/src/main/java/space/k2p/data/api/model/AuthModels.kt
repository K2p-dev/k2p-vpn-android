package space.k2p.data.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    @SerialName("email") val email: String,
    @SerialName("password") val password: String
)

@Serializable
data class AuthResponse(
    @SerialName("status") val status: String? = null,
    @SerialName("token") val token: String? = null,
    @SerialName("user_id") val userId: Long? = null,
    @SerialName("hwid") val hwid: String? = null,
    @SerialName("error") val error: String? = null,
    @SerialName("code") val code: String? = null,
    @SerialName("message") val message: String? = null
)

@Serializable
data class EmailRegisterInitRequest(
    @SerialName("email") val email: String,
    @SerialName("password") val password: String
)

@Serializable
data class EmailRegisterInitResponse(
    @SerialName("verify_token") val verifyToken: String? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("expires_in") val expiresIn: Int? = null,
    @SerialName("error") val error: String? = null,
    @SerialName("code") val code: String? = null
)

@Serializable
data class EmailRegisterVerifyRequest(
    @SerialName("verify_token") val verifyToken: String,
    @SerialName("code") val code: String
)

@Serializable
data class TgRegisterRequest(
    @SerialName("email") val email: String,
    @SerialName("password") val password: String
)

@Serializable
data class TgRegisterResponse(
    @SerialName("token") val token: String? = null,
    @SerialName("bot_url") val botUrl: String? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("expires_in") val expiresIn: Int? = null,
    @SerialName("error") val error: String? = null,
    @SerialName("code") val code: String? = null
)

@Serializable
data class TgInitResponse(
    @SerialName("token") val token: String? = null,
    @SerialName("bot_url") val botUrl: String? = null,
    @SerialName("expires_in") val expiresIn: Int? = null,
    @SerialName("error") val error: String? = null,
    @SerialName("code") val code: String? = null
)

@Serializable
data class TgCheckResponse(
    @SerialName("status") val status: String? = null,
    @SerialName("token") val token: String? = null,
    @SerialName("user_id") val userId: Long? = null,
    @SerialName("hwid") val hwid: String? = null,
    @SerialName("error") val error: String? = null,
    @SerialName("code") val code: String? = null,
    @SerialName("message") val message: String? = null
)

@Serializable
data class ApiErrorResponse(
    @SerialName("status") val status: String? = null,
    @SerialName("error") val error: String? = null,
    @SerialName("code") val code: String? = null,
    @SerialName("message") val message: String? = null
)
