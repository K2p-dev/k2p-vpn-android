package space.k2p.data.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TariffDto(
    @SerialName("months") val months: Int,
    @SerialName("title") val title: String,
    @SerialName("base_price") val basePrice: Int,
    @SerialName("final_price") val finalPrice: Int
)

@Serializable
data class PriceListResponse(
    @SerialName("discount_percent") val discountPercent: Int = 0,
    @SerialName("tariffs") val tariffs: List<TariffDto> = emptyList()
)

@Serializable
data class CreatePaymentRequest(
    @SerialName("months") val months: Int
)

@Serializable
data class CreatePaymentResponse(
    @SerialName("order_id") val orderId: String? = null,
    @SerialName("amount") val amount: Int? = null,
    @SerialName("pay_url") val payUrl: String? = null,
    @SerialName("error") val error: String? = null,
    @SerialName("code") val code: String? = null,
    @SerialName("message") val message: String? = null
)
