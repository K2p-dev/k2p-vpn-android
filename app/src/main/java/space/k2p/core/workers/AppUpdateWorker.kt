package space.k2p.core.workers

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import space.k2p.K2pApp
import space.k2p.MainActivity
import space.k2p.R
import space.k2p.core.logging.AppLogger
import space.k2p.core.logging.LogTag

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
            val updateResult = K2pApp.instance.appUpdateManager.checkForUpdate()

            updateResult.onSuccess { info ->
                if (info.isUpdateAvailable) {
                    val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val lastNotified = prefs.getString(KEY_LAST_NOTIFIED_VERSION, null)

                    if (lastNotified != info.latestVersion) {
                        postUpdateNotification(info.latestVersion)
                        prefs.edit().putString(KEY_LAST_NOTIFIED_VERSION, info.latestVersion).apply()
                    }
                }
            }.onFailure { error ->
                Log.e("AppUpdateWorker", "Update check failed: ${error.message}", error)
                AppLogger.e(LogTag.OTA, "Фоновая проверка обновлений завершилась ошибкой: ${error.message}", error)
            }
            Result.success()
        } catch (e: Exception) {
            Log.e("AppUpdateWorker", "Unexpected error during update check: ${e.message}", e)
            AppLogger.e(LogTag.OTA, "Неожиданная ошибка AppUpdateWorker: ${e.message}", e)
            Result.retry()
        }
    }

    private fun postUpdateNotification(latestVersion: String) {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
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
            .setContentText("Вышла новая версия. Нажмите, чтобы обновить приложение.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Вышла новая версия $latestVersion с улучшениями и обновлениями. Нажмите, чтобы установить внутри приложения."))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }
}
