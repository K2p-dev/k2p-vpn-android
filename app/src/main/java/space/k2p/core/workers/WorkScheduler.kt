package space.k2p.core.workers

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object WorkScheduler {

    private const val WORK_GEODATA_UPDATE = "k2p_geodata_update"
    private const val WORK_APP_UPDATE = "k2p_app_update"

    fun schedulePeriodicTasks(context: Context) {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val geodataWorkRequest = PeriodicWorkRequestBuilder<GeodataUpdateWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            val appUpdateWorkRequest = PeriodicWorkRequestBuilder<AppUpdateWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            val workManager = WorkManager.getInstance(context)
            workManager.enqueueUniquePeriodicWork(
                WORK_GEODATA_UPDATE,
                ExistingPeriodicWorkPolicy.KEEP,
                geodataWorkRequest
            )
            workManager.enqueueUniquePeriodicWork(
                WORK_APP_UPDATE,
                ExistingPeriodicWorkPolicy.KEEP,
                appUpdateWorkRequest
            )
        } catch (_: Exception) {
        }
    }
}
