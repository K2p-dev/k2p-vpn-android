package space.k2p.data.repository

import android.content.Context
import kotlinx.serialization.json.Json
import retrofit2.Response
import space.k2p.core.device.DeviceSecurity
import space.k2p.data.api.ClientApiService
import space.k2p.data.api.model.ApiErrorResponse
import space.k2p.data.api.model.ClientConfigResponse
import space.k2p.data.api.model.DeleteDeviceResponse
import space.k2p.data.api.model.DeviceItemDto
import space.k2p.data.api.model.UserProfileResponse

class ClientRepository(
    private val context: Context,
    private val apiService: ClientApiService,
    private val json: Json
) {
    private val currentHwid: String get() = DeviceSecurity.getHardwareId(context)

    private fun <T> parseError(response: Response<T>): ApiException {
        val errorBody = response.errorBody()?.string().orEmpty()
        val errorDto = runCatching {
            json.decodeFromString<ApiErrorResponse>(errorBody)
        }.getOrNull()

        val rawCode = errorDto?.code ?: errorDto?.error
        val message = when (rawCode) {
            "DEVICE_NOT_FOUND" -> "Устройство не найдено"
            "CANNOT_DELETE_CURRENT" -> "Нельзя отвязать текущее устройство. Используйте выход из аккаунта."
            "NO_SUBSCRIPTION" -> "Активная подписка отсутствует"
            else -> errorDto?.message ?: "Ошибка сервера (${response.code()})"
        }

        return ApiException(code = rawCode, statusCode = response.code(), message = message)
    }

    suspend fun getProfile(): Result<UserProfileResponse> {
        return try {
            val response = apiService.getProfile()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(parseError(response))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getDevices(): Result<List<DeviceItemDto>> {
        return try {
            val response = apiService.getDevices()
            if (response.isSuccessful && response.body() != null) {
                val list = response.body()!!.devices.map { device ->
                    if (device.hwid.equals(currentHwid, ignoreCase = true)) {
                        device.copy(isCurrent = true)
                    } else {
                        device
                    }
                }
                Result.success(list)
            } else {
                Result.failure(parseError(response))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteDevice(hwid: String): Result<DeleteDeviceResponse> {
        return try {
            val response = apiService.deleteDevice(hwid)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(parseError(response))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getConfig(): Result<ClientConfigResponse> {
        return try {
            val response = apiService.getConfig()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(parseError(response))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
