package space.k2p.data.repository

import space.k2p.BuildConfig
import space.k2p.core.config.AppConfig
import space.k2p.core.logging.AppLogger
import space.k2p.core.logging.LogTag
import space.k2p.core.update.AppUpdateManager
import space.k2p.data.api.GithubApiService

data class AppUpdateInfo(
    val isUpdateAvailable: Boolean,
    val latestVersion: String,
    val currentVersion: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val hasDirectApkAsset: Boolean = false
)

class AppUpdateRepository(
    private val githubApiService: GithubApiService
) {
    suspend fun checkForUpdate(): Result<AppUpdateInfo> {
        AppLogger.i(LogTag.OTA, "Проверка обновлений GitHub: ${AppConfig.GITHUB_RELEASES_API}")
        return try {
            val response = githubApiService.getReleases(AppConfig.GITHUB_RELEASES_API)
            if (response.isSuccessful) {
                val releases = response.body().orEmpty()
                val release = releases.firstOrNull { !it.draft }
                if (release != null) {
                    val cleanLatest = release.tagName.removePrefix("v").trim()
                    val currentVersion = BuildConfig.VERSION_NAME

                    val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                    val hasApk = apkAsset != null && apkAsset.browserDownloadUrl.isNotBlank()
                    val directDownloadUrl = apkAsset?.browserDownloadUrl?.takeIf { it.isNotBlank() }
                        ?: release.htmlUrl
                        ?: AppConfig.GITHUB_RELEASES_API

                    val isNewer = AppUpdateManager.isVersionNewer(cleanLatest, currentVersion)
                    AppLogger.i(
                        LogTag.OTA,
                        "Релиз найден: ${release.tagName} (текущая v$currentVersion), обновление=$isNewer, apkAsset=${apkAsset?.name ?: "нет"}"
                    )
                    Result.success(
                        AppUpdateInfo(
                            isUpdateAvailable = isNewer,
                            latestVersion = release.tagName,
                            currentVersion = "v$currentVersion",
                            releaseTitle = release.name ?: release.tagName,
                            releaseNotes = release.body.orEmpty(),
                            downloadUrl = directDownloadUrl,
                            hasDirectApkAsset = hasApk
                        )
                    )
                } else {
                    AppLogger.w(LogTag.OTA, "В ответе GitHub нет опубликованных релизов")
                    Result.failure(Exception("Релизы не найдены в репозитории"))
                }
            } else {
                val code = response.code()
                val errorMsg = response.message().ifBlank { "HTTP error" }
                AppLogger.e(LogTag.OTA, "Ошибка ответа GitHub API: HTTP $code ($errorMsg)")
                Result.failure(Exception("Ошибка сервера GitHub (код $code: $errorMsg)"))
            }
        } catch (e: Exception) {
            val detail = e.localizedMessage ?: e.message ?: e.javaClass.simpleName
            AppLogger.e(LogTag.OTA, "Сетевая ошибка при проверке обновлений: $detail", e)
            Result.failure(Exception("Ошибка сети при проверке обновлений: $detail", e))
        }
    }
}
