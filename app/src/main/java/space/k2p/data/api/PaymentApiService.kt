package space.k2p.data.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import space.k2p.data.api.model.CreatePaymentRequest
import space.k2p.data.api.model.CreatePaymentResponse
import space.k2p.data.api.model.PriceListResponse

interface PaymentApiService {
    @GET("api/payments/prices")
    suspend fun getPrices(): Response<PriceListResponse>

    @POST("api/payments/create")
    suspend fun createPayment(
        @Body request: CreatePaymentRequest
    ): Response<CreatePaymentResponse>
}
