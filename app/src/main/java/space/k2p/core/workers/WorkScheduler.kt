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
    private const val WORK_EMERGENCY_TRANSPORT_SYNC = "k2p_emergency_transport_sync"

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

            val emergencySyncWorkRequest = PeriodicWorkRequestBuilder<EmergencyTransportSyncWorker>(30, TimeUnit.MINUTES)
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
            workManager.enqueueUniquePeriodicWork(
                WORK_EMERGENCY_TRANSPORT_SYNC,
                ExistingPeriodicWorkPolicy.KEEP,
                emergencySyncWorkRequest
            )
        } catch (_: Exception) {
        }
    }
}
