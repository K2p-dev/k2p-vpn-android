package space.k2p.data.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserProfileResponse(
    @SerialName("user_id") val userId: Long? = null,
    @SerialName("tg_linked") val tgLinked: Boolean = false,
    @SerialName("username") val username: String? = null,
    @SerialName("email") val email: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("days_left") val daysLeft: Int = 0,
    @SerialName("expiry_time_ms") val expiryTimeMs: Long? = null,
    @SerialName("discount_percent") val discountPercent: Int = 0,
    @SerialName("trial_used") val trialUsed: Boolean = false,
    @SerialName("devices_count") val devicesCount: Int = 0,
    @SerialName("max_devices") val maxDevices: Int = 4,
    @SerialName("sub_link") val subLink: String? = null
)

@Serializable
data class DeviceItemDto(
    @SerialName("id") val id: Long? = null,
    @SerialName("hwid") val hwid: String,
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("last_seen") val lastSeen: Long? = null,
    @SerialName("is_current") val isCurrent: Boolean = false
)

@Serializable
data class DevicesListResponse(
    @SerialName("devices") val devices: List<DeviceItemDto> = emptyList(),
    @SerialName("max_devices") val maxDevices: Int = 4
)

@Serializable
data class DeleteDeviceResponse(
    @SerialName("status") val status: String? = null,
    @SerialName("hwid") val hwid: String? = null
)

@Serializable
data class ClientConfigResponse(
    @SerialName("status") val status: String? = null,
    @SerialName("sub_link") val subLink: String? = null,
    @SerialName("expiry_time_ms") val expiryTimeMs: Long? = null
)
