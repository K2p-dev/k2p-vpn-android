package space.k2p.data.repository

import android.content.Context
import kotlinx.serialization.json.Json
import retrofit2.Response
import space.k2p.core.device.DeviceSecurity
import space.k2p.data.api.AuthApiService
import space.k2p.data.api.model.ApiErrorResponse
import space.k2p.data.api.model.AuthResponse
import space.k2p.data.api.model.EmailRegisterInitRequest
import space.k2p.data.api.model.EmailRegisterInitResponse
import space.k2p.data.api.model.EmailRegisterVerifyRequest
import space.k2p.data.api.model.LoginRequest
import space.k2p.data.api.model.TgCheckResponse
import space.k2p.data.api.model.TgInitResponse
import space.k2p.data.api.model.TgRegisterRequest
import space.k2p.data.api.model.TgRegisterResponse
import space.k2p.data.auth.TokenStorage

class ApiException(
    val code: String?,
    val statusCode: Int,
    override val message: String
) : Exception(message)

class AuthRepository(
    private val context: Context,
    private val apiService: AuthApiService,
    private val tokenStorage: TokenStorage,
    private val json: Json
) {
    private val hwid: String get() = DeviceSecurity.getHardwareId(context)
    private val deviceName: String get() = DeviceSecurity.getDeviceName()

    private fun <T> parseError(response: Response<T>): ApiException {
        val errorBody = response.errorBody()?.string().orEmpty()
        val errorDto = runCatching {
            json.decodeFromString<ApiErrorResponse>(errorBody)
        }.getOrNull()

        val rawCode = errorDto?.code ?: errorDto?.error
        val message = when (rawCode) {
            "INVALID_CREDENTIALS" -> "Неверный email или пароль"
            "DEVICE_LIMIT_REACHED" -> "Достигнут лимит устройств (максимум 4)"
            "INVALID_EMAIL" -> "Некорректный адрес электронной почты"
            "WEAK_PASSWORD" -> "Слишком простой пароль"
            "EMAIL_TAKEN" -> "Пользователь с таким email уже зарегистрирован"
            "MAIL_RATE_LIMIT" -> "Слишком много запросов. Подождите пару минут"
            "INVALID_CODE" -> "Неверный 6-значный код подтверждения"
            "EXPIRED" -> "Срок действия кода истек. Запросите новый"
            "TOO_MANY_ATTEMPTS" -> "Слишком много неверных попыток ввода кода"
            "NO_SUBSCRIPTION" -> "Активная подписка отсутствует"
            else -> errorDto?.message ?: "Ошибка сервера (${response.code()})"
        }

        return ApiException(code = rawCode, statusCode = response.code(), message = message)
    }

    suspend fun login(request: LoginRequest): Result<AuthResponse> {
        return try {
            val response = apiService.login(hwid = hwid, deviceName = deviceName, request = request)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val token = body.token
                if (!token.isNullOrBlank()) {
                    tokenStorage.saveAuth(
                        token = token,
                        userId = body.userId ?: -1L,
                        email = request.email,
                        hwid = body.hwid ?: hwid
                    )
                    space.k2p.core.work.SubscriptionWorkManager.schedulePeriodicWork(context)
                }
                Result.success(body)
            } else {
                Result.failure(parseError(response))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun registerEmailInit(request: EmailRegisterInitRequest): Result<EmailRegisterInitResponse> {
        return try {
            val response = apiService.registerEmailInit(request)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(parseError(response))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun registerEmailVerify(request: EmailRegisterVerifyRequest, email: String?): Result<AuthResponse> {
        return try {
            val response = apiService.registerEmailVerify(
                hwid = hwid,
                deviceName = deviceName,
                request = request
            )
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val token = body.token
                if (!token.isNullOrBlank()) {
                    tokenStorage.saveAuth(
                        token = token,
                        userId = body.userId ?: -1L,
                        email = email,
                        hwid = body.hwid ?: hwid
                    )
                    space.k2p.core.work.SubscriptionWorkManager.schedulePeriodicWork(context)
                }
                Result.success(body)
            } else {
                Result.failure(parseError(response))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun registerWithTelegram(request: TgRegisterRequest): Result<TgRegisterResponse> {
        return try {
            val response = apiService.registerWithTelegram(request)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(parseError(response))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun initTelegramAuth(): Result<TgInitResponse> {
        return try {
            val response = apiService.initTelegramAuth()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(parseError(response))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun checkTelegramAuth(token: String): Result<TgCheckResponse> {
        return try {
            val response = apiService.checkTelegramAuth(token = token, hwid = hwid, deviceName = deviceName)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.status.equals("ok", ignoreCase = true) && !body.token.isNullOrBlank()) {
                    tokenStorage.saveAuth(
                        token = body.token,
                        userId = body.userId ?: -1L,
                        email = null,
                        hwid = body.hwid ?: hwid
                    )
                    space.k2p.core.work.SubscriptionWorkManager.schedulePeriodicWork(context)
                }
                Result.success(body)
            } else {
                Result.failure(parseError(response))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun logout(): Result<Unit> {
        space.k2p.core.work.SubscriptionWorkManager.cancelWork(context)
        return try {
            apiService.logout()
            tokenStorage.clear()
            Result.success(Unit)
        } catch (e: Exception) {
            tokenStorage.clear()
            Result.success(Unit)
        }
    }
}
