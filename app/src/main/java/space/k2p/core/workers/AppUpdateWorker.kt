package space.k2p.core.workers

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import space.k2p.K2pApp
import space.k2p.R

class AppUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val CHANNEL_ID = "k2p_updates"
        private const val NOTIFICATION_ID = 3001
        private const val PREFS_NAME = "k2p_update_worker_prefs"
        private const val KEY_LAST_NOTIFIED_VERSION = "last_notified_version"
    }

    override suspend fun doWork(): Result {
        return try {
            val repository = K2pApp.instance.appUpdateRepository
            val updateResult = repository.checkForUpdate()

            updateResult.onSuccess { info ->
                if (info.isUpdateAvailable) {
                    val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val lastNotified = prefs.getString(KEY_LAST_NOTIFIED_VERSION, null)

                    if (lastNotified != info.latestVersion) {
                        postUpdateNotification(info.latestVersion, info.downloadUrl)
                        prefs.edit().putString(KEY_LAST_NOTIFIED_VERSION, info.latestVersion).apply()
                    }
                }
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun postUpdateNotification(latestVersion: String, downloadUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_k2p_monochrome)
            .setContentTitle("Доступно обновление К2П: $latestVersion")
            .setContentText("Вышла новая версия с обновлённым ядром/геоданными. Нажмите, чтобы скачать.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Вышла новая версия $latestVersion с обновлённым ядром/геоданными. Нажмите, чтобы скачать установщик."))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }
}
