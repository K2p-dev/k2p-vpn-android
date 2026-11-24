package space.k2p.core.device

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val isSystemApp: Boolean
)

object AppListManager {
    suspend fun getInstalledApps(
        context: Context,
        includeSystemApps: Boolean = false
    ): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val selfPackage = context.packageName
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        installedApps.mapNotNull { appInfo ->
            if (appInfo.packageName == selfPackage) return@mapNotNull null

            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

            if (!includeSystemApps && isSystem) return@mapNotNull null

            val appName = runCatching { pm.getApplicationLabel(appInfo).toString() }
                .getOrDefault(appInfo.packageName)
            val icon = runCatching { pm.getApplicationIcon(appInfo) }.getOrNull()

            InstalledAppInfo(
                packageName = appInfo.packageName,
                appName = appName,
                icon = icon,
                isSystemApp = isSystem
            )
        }.sortedBy { it.appName.lowercase() }
    }
}
