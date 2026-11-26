package space.k2p.data.repository

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import retrofit2.Response
import space.k2p.MainActivity
import space.k2p.R
import space.k2p.core.logging.AppLogger
import space.k2p.core.logging.LogTag
import space.k2p.core.preferences.EmergencyTransportStorage
import space.k2p.data.api.ClientApiService
import space.k2p.data.api.model.ApiErrorResponse
import space.k2p.data.api.model.EmergencyTransportResponse
import space.k2p.data.api.model.EmergencyTransportServiceType
import space.k2p.data.api.model.EmergencyTransportState
import space.k2p.data.api.model.EmergencyTransportStatus
import space.k2p.data.api.model.SaveEmergencyTransportRequest
import space.k2p.data.auth.TokenStorage
import java.time.Instant

class EmergencyTransportRepository(
    private val context: Context,
    private val apiService: ClientApiService,
    private val tokenStorage: TokenStorage,
    private val storage: EmergencyTransportStorage,
    private val json: Json
) {
    companion object {
        private const val CHANNEL_ID = "k2p_emergency_transport"
        private const val NOTIFICATION_ID = 3003
    }

    private val syncMutex = Mutex()
    private var loadedFromDisk = false

    private val _state = MutableStateFlow(EmergencyTransportState())
    val state: StateFlow<EmergencyTransportState> = _state.asStateFlow()

    suspend fun initFromStorage() {
        syncMutex.withLock {
            if (!loadedFromDisk) {
                _state.value = storage.loadState()
                loadedFromDisk = true
            }
        }
    }

    suspend fun syncFromServer(): Result<EmergencyTransportState> = syncMutex.withLock {
        if (!loadedFromDisk) {
            _state.value = storage.loadState()
            loadedFromDisk = true
        }

        val token = tokenStorage.tokenFlow.firstOrNull()
        if (token.isNullOrBlank()) {
            return Result.success(_state.value)
        }

        val previousState = _state.value
        _state.update { it.copy(isSyncing = true, lastError = null) }

        return try {
            val response = apiService.getEmergencyTransport()
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val newStatus = EmergencyTransportStatus.fromRaw(body.status, body.docUrl)
                val serviceType = body.transportType?.let { EmergencyTransportServiceType.fromRaw(it) }
                    ?: previousState.transportType
                val docUrl = body.docUrl?.trim().orEmpty()
                val sessionSecret = body.sessionSecret?.trim().orEmpty()
                val deleteAtMs = parseDeleteAtMs(body.deleteAt)

                val transitionedValidToInvalid =
                    previousState.status == EmergencyTransportStatus.VALID &&
                        newStatus == EmergencyTransportStatus.INVALID

                var invalidNotified = when (newStatus) {
                    EmergencyTransportStatus.INVALID -> previousState.invalidNotified
                    else -> false
                }

                if (transitionedValidToInvalid && !invalidNotified) {
                    sendInvalidLinkNotification()
                    invalidNotified = true
                }

                val updated = EmergencyTransportState(
                    status = newStatus,
                    docUrl = docUrl,
                    transportType = serviceType,
                    sessionSecret = sessionSecret,
                    deleteAtMs = if (newStatus == EmergencyTransportStatus.INVALID) {
                        deleteAtMs ?: previousState.deleteAtMs ?: (System.currentTimeMillis() + 3 * 3600_000L)
                    } else {
                        null
                    },
                    lastSyncedAtMs = System.currentTimeMillis(),
                    invalidNotified = invalidNotified,
                    isSyncing = false,
                    lastError = null
                )

                storage.saveState(updated)
                _state.value = updated
                AppLogger.i(
                    LogTag.OPENFLUX,
                    "Синхронизация OpenFlux с аккаунтом: status=${updated.status}, type=${updated.transportType.apiValue}, hasSecret=${updated.sessionSecret.isNotBlank()}"
                )
                Result.success(updated)
            } else {
                val err = parseError(response)
                _state.update { it.copy(isSyncing = false, lastError = err.message) }
                AppLogger.w(LogTag.OPENFLUX, "Не удалось синхронизировать OpenFlux: ${err.message}")
                Result.failure(err)
            }
        } catch (e: Exception) {
            val msg = e.localizedMessage ?: "Ошибка сети"
            _state.update { it.copy(isSyncing = false, lastError = msg) }
            AppLogger.w(LogTag.OPENFLUX, "Ошибка синхронизации OpenFlux: $msg")
            Result.failure(e)
        }
    }

    suspend fun bindAndVerify(
        docUrl: String,
        transportType: EmergencyTransportServiceType
    ): Result<EmergencyTransportState> = syncMutex.withLock {
        if (!loadedFromDisk) {
            _state.value = storage.loadState()
            loadedFromDisk = true
        }

        val trimmedUrl = docUrl.trim()
        _state.update { it.copy(isSyncing = true, lastError = null) }

        return try {
            val response = apiService.saveEmergencyTransport(
                SaveEmergencyTransportRequest(
                    docUrl = trimmedUrl,
                    transportType = transportType.apiValue
                )
            )
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val resolvedStatus = EmergencyTransportStatus.fromRaw(body.status, body.docUrl ?: trimmedUrl)
                val resolvedUrl = body.docUrl?.trim()?.takeIf { it.isNotBlank() } ?: trimmedUrl
                val resolvedType = body.transportType?.let { EmergencyTransportServiceType.fromRaw(it) }
                    ?: transportType
                val resolvedSecret = body.sessionSecret?.trim().orEmpty()
                val deleteAtMs = parseDeleteAtMs(body.deleteAt)

                val updated = EmergencyTransportState(
                    status = resolvedStatus,
                    docUrl = resolvedUrl,
                    transportType = resolvedType,
                    sessionSecret = resolvedSecret,
                    deleteAtMs = if (resolvedStatus == EmergencyTransportStatus.INVALID) deleteAtMs else null,
                    lastSyncedAtMs = System.currentTimeMillis(),
                    invalidNotified = false,
                    isSyncing = false,
                    lastError = null
                )

                storage.saveState(updated)
                _state.value = updated
                AppLogger.i(
                    LogTag.OPENFLUX,
                    "Привязка документа OpenFlux завершена: status=${updated.status}, type=${updated.transportType.apiValue}"
                )
                Result.success(updated)
            } else {
                val err = parseError(response)
                _state.update { it.copy(isSyncing = false, lastError = err.message) }
                AppLogger.w(LogTag.OPENFLUX, "Ошибка проверки/привязки документа: ${err.message}")
                Result.failure(err)
            }
        } catch (e: Exception) {
            val msg = e.localizedMessage ?: "Ошибка сети"
            _state.update { it.copy(isSyncing = false, lastError = msg) }
            AppLogger.e(LogTag.OPENFLUX, "Сбой при привязке документа OpenFlux: $msg", e)
            Result.failure(e)
        }
    }

    suspend fun deleteBinding(): Result<EmergencyTransportState> = syncMutex.withLock {
        _state.update { it.copy(isSyncing = true, lastError = null) }
        return try {
            val response = apiService.deleteEmergencyTransport()
            if (response.isSuccessful) {
                val cleared = EmergencyTransportState(
                    status = EmergencyTransportStatus.NOT_CONFIGURED,
                    lastSyncedAtMs = System.currentTimeMillis()
                )
                storage.saveState(cleared)
                _state.value = cleared
                AppLogger.i(LogTag.OPENFLUX, "Привязка OpenFlux удалена из аккаунта")
                Result.success(cleared)
            } else {
                val err = parseError(response)
                _state.update { it.copy(isSyncing = false, lastError = err.message) }
                Result.failure(err)
            }
        } catch (e: Exception) {
            val msg = e.localizedMessage ?: "Ошибка сети"
            _state.update { it.copy(isSyncing = false, lastError = msg) }
            Result.failure(e)
        }
    }

    suspend fun activateOnServer(): Result<EmergencyTransportState> = syncMutex.withLock {
        if (!loadedFromDisk) {
            _state.value = storage.loadState()
            loadedFromDisk = true
        }

        val current = _state.value
        AppLogger.i(LogTag.OPENFLUX, "Запрос активации персонального воркера OpenFlux в Хельсинки...")
        return try {
            val response = apiService.activateEmergencyTransport()
            if (response.isSuccessful) {
                val body = response.body()
                val updated = current.copy(
                    status = body?.status?.let { EmergencyTransportStatus.fromRaw(it, body.docUrl ?: current.docUrl) }
                        ?: current.status,
                    docUrl = body?.docUrl?.trim()?.takeIf { it.isNotBlank() } ?: current.docUrl,
                    transportType = body?.transportType?.let { EmergencyTransportServiceType.fromRaw(it) }
                        ?: current.transportType,
                    sessionSecret = body?.sessionSecret?.trim()?.takeIf { it.isNotBlank() }
                        ?: current.sessionSecret,
                    lastSyncedAtMs = System.currentTimeMillis()
                )
                storage.saveState(updated)
                _state.value = updated
                AppLogger.i(
                    LogTag.OPENFLUX,
                    "Воркер OpenFlux активирован на сервере (workerStatus=${body?.workerStatus ?: "ready"})"
                )
                Result.success(updated)
            } else {
                val err = parseError(response)
                AppLogger.e(LogTag.OPENFLUX, "Ошибка активации воркера OpenFlux: ${err.message}")
                Result.failure(err)
            }
        } catch (e: Exception) {
            AppLogger.e(LogTag.OPENFLUX, "Сбой сети при активации OpenFlux: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun clearLocal() = syncMutex.withLock {
        storage.clear()
        _state.value = EmergencyTransportState()
    }

    fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Экстренный обход (OpenFlux)",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Уведомления о состоянии привязанного документа для экстренного обхода"
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.createNotificationChannel(channel)
    }

    private fun sendInvalidLinkNotification() {
        ensureNotificationChannel()
        val intent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_EMERGENCY_BYPASS
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_k2p_monochrome)
            .setContentTitle("Экстренный обход: ссылка не работает")
            .setContentText("Документ недоступен и будет удалён с сервера через 3 часа. Нажмите, чтобы обновить ссылку.")
            .setStyle(
                androidx.core.app.NotificationCompat.BigTextStyle().bigText(
                    "Документ недоступен и будет удалён с сервера через 3 часа. Нажмите, чтобы обновить ссылку."
                )
            )
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, notification)
        AppLogger.w(LogTag.OPENFLUX, "Отправлено системное уведомление: ссылка OpenFlux стала невалидной")
    }

    private fun parseDeleteAtMs(element: JsonElement?): Long? {
        val primitive = element as? JsonPrimitive ?: return null
        primitive.longOrNull?.let { num ->
            return if (num in 1L..9_999_999_999L) num * 1000L else num
        }
        val str = primitive.content.trim()
        if (str.isBlank() || str.equals("null", ignoreCase = true)) return null
        str.toLongOrNull()?.let { num ->
            return if (num in 1L..9_999_999_999L) num * 1000L else num
        }
        return runCatching { Instant.parse(str).toEpochMilli() }.getOrNull()
    }

    private fun <T> parseError(response: Response<T>): ApiException {
        val errorBody = response.errorBody()?.string().orEmpty()
        val errorDto = runCatching {
            json.decodeFromString<ApiErrorResponse>(errorBody)
        }.getOrNull()
        val rawCode = errorDto?.code ?: errorDto?.error
        val message = errorDto?.message
            ?: runCatching { json.decodeFromString<EmergencyTransportResponse>(errorBody).message }.getOrNull()
            ?: "Ошибка сервера (${response.code()})"
        return ApiException(code = rawCode, statusCode = response.code(), message = message)
    }
}
