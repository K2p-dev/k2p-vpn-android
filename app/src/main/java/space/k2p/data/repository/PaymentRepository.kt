package space.k2p.data.repository

import kotlinx.serialization.json.Json
import retrofit2.Response
import space.k2p.data.api.PaymentApiService
import space.k2p.data.api.model.ApiErrorResponse
import space.k2p.data.api.model.CreatePaymentRequest
import space.k2p.data.api.model.CreatePaymentResponse
import space.k2p.data.api.model.PriceListResponse

class PaymentRepository(
    private val apiService: PaymentApiService,
    private val json: Json
) {
    private fun <T> parseError(response: Response<T>): ApiException {
        val errorBody = response.errorBody()?.string().orEmpty()
        val errorDto = runCatching {
            json.decodeFromString<ApiErrorResponse>(errorBody)
        }.getOrNull()

        val rawCode = errorDto?.code ?: errorDto?.error
        val message = when (rawCode) {
            "ACTIVE_INVOICE_EXISTS" -> "У вас уже есть неоплаченный счёт. Оплатите его или подождите несколько минут."
            "TRIAL_USED" -> "Пробный период уже был использован на этом аккаунте."
            else -> errorDto?.message ?: "Ошибка при создании платежа (${response.code()})"
        }

        return ApiException(code = rawCode, statusCode = response.code(), message = message)
    }

    suspend fun getPrices(): Result<PriceListResponse> {
        return try {
            val response = apiService.getPrices()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(parseError(response))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createPayment(months: Int): Result<CreatePaymentResponse> {
        return try {
            val response = apiService.createPayment(CreatePaymentRequest(months = months))
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
