@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package space.k2p.data.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames

enum class EmergencyTransportStatus(val badgeText: String) {
    NOT_CONFIGURED("Не настроен"),
    VALID("Настроен"),
    INVALID("Требует обновления");

    companion object {
        fun fromRaw(raw: String?, docUrl: String?): EmergencyTransportStatus {
            val normalized = raw?.trim()?.uppercase()
            return when (normalized) {
                "VALID", "ACTIVE", "OK" -> VALID
                "INVALID", "EXPIRED", "BROKEN", "ERROR" -> INVALID
                "NOT_CONFIGURED", "UNCONFIGURED", "NONE", "EMPTY" -> NOT_CONFIGURED
                else -> if (!docUrl.isNullOrBlank()) VALID else NOT_CONFIGURED
            }
        }
    }
}

enum class EmergencyTransportServiceType(
    val apiValue: String,
    val openFluxName: String,
    val displayName: String
) {
    YANDEX_DOCS(
        apiValue = "yandex_docs",
        openFluxName = "yandex",
        displayName = "Яндекс.Документы"
    ),
    MAIL_RU(
        apiValue = "mailru",
        openFluxName = "mailru",
        displayName = "Mail.ru"
    );

    companion object {
        fun fromRaw(raw: String?): EmergencyTransportServiceType {
            return when (raw?.trim()?.lowercase()) {
                "mailru", "mail_ru", "mail.ru" -> MAIL_RU
                "yandex_docs", "yandex", "yandexdocs" -> YANDEX_DOCS
                else -> YANDEX_DOCS
            }
        }
    }
}

@Serializable
data class EmergencyTransportState(
    val status: EmergencyTransportStatus = EmergencyTransportStatus.NOT_CONFIGURED,
    val docUrl: String = "",
    val transportType: EmergencyTransportServiceType = EmergencyTransportServiceType.YANDEX_DOCS,
    val sessionSecret: String = "",
    val deleteAtMs: Long? = null,
    val lastSyncedAtMs: Long? = null,
    val invalidNotified: Boolean = false,
    val isSyncing: Boolean = false,
    val lastError: String? = null
)

@Serializable
data class SaveEmergencyTransportRequest(
    @SerialName("doc_url") val docUrl: String,
    @SerialName("transport_type") val transportType: String
)

@Serializable
data class EmergencyTransportResponse(
    @JsonNames("status") val status: String? = null,
    @JsonNames("doc_url", "docUrl", "url") val docUrl: String? = null,
    @JsonNames("transport_type", "transportType", "type") val transportType: String? = null,
    @JsonNames("session_secret", "sessionSecret", "secret") val sessionSecret: String? = null,
    @JsonNames("delete_at", "deleteAt", "delete_at_ms", "deleteAtMs") val deleteAt: JsonElement? = null,
    @JsonNames("message") val message: String? = null,
    @JsonNames("error") val error: String? = null,
    @JsonNames("code") val code: String? = null
)

@Serializable
data class ActivateEmergencyTransportResponse(
    @JsonNames("status") val status: String? = null,
    @JsonNames("doc_url", "docUrl", "url") val docUrl: String? = null,
    @JsonNames("transport_type", "transportType", "type") val transportType: String? = null,
    @JsonNames("session_secret", "sessionSecret", "secret") val sessionSecret: String? = null,
    @JsonNames("worker_status", "workerStatus") val workerStatus: String? = null,
    @JsonNames("message") val message: String? = null,
    @JsonNames("error") val error: String? = null,
    @JsonNames("code") val code: String? = null
)
