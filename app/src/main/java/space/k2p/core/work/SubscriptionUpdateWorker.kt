package space.k2p.core.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import space.k2p.K2pApp
import java.util.concurrent.TimeUnit

class SubscriptionUpdateWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val tokenStorage = K2pApp.instance.tokenStorage
        val token = tokenStorage.tokenFlow.first()
        if (token.isNullOrBlank()) {
            return Result.success()
        }

        return try {
            val repo = K2pApp.instance.subscriptionRepository
            val result = repo.refreshSubscription(force = true)
            if (result.isSuccess) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

object SubscriptionWorkManager {
    private const val UNIQUE_WORK_NAME = "k2p_subscription_periodic_update"

    fun schedulePeriodicWork(context: Context, intervalHours: Long = 6L) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val hours = intervalHours.coerceAtLeast(1L)
        val periodicRequest = PeriodicWorkRequestBuilder<SubscriptionUpdateWorker>(
            hours, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicRequest
        )
    }

    fun cancelWork(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}
