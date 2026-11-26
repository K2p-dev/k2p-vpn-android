package space.k2p.core.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.firstOrNull
import space.k2p.K2pApp
import space.k2p.core.logging.AppLogger
import space.k2p.core.logging.LogTag

class EmergencyTransportSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val token = K2pApp.instance.tokenStorage.tokenFlow.firstOrNull()
        if (token.isNullOrBlank()) {
            return Result.success()
        }
        return try {
            val result = K2pApp.instance.emergencyTransportRepository.syncFromServer()
            if (result.isSuccess) Result.success() else Result.retry()
        } catch (e: Exception) {
            AppLogger.w(LogTag.OPENFLUX, "Фоновая синхронизация OpenFlux не удалась: ${e.message}")
            Result.retry()
        }
    }
}
