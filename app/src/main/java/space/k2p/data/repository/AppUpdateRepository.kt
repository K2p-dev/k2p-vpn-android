package space.k2p.data.repository

import space.k2p.BuildConfig
import space.k2p.core.config.AppConfig
import space.k2p.data.api.GithubApiService

data class AppUpdateInfo(
    val isUpdateAvailable: Boolean,
    val latestVersion: String,
    val currentVersion: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val downloadUrl: String
)

class AppUpdateRepository(
    private val githubApiService: GithubApiService
) {
    suspend fun checkForUpdate(): Result<AppUpdateInfo> {
        return try {
            val response = githubApiService.getLatestRelease(AppConfig.GITHUB_RELEASES_API)
            if (response.isSuccessful && response.body() != null) {
                val release = response.body()!!
                val cleanLatest = release.tagName.removePrefix("v").trim()
                val currentVersion = BuildConfig.VERSION_NAME

                val isNewer = isVersionNewer(cleanLatest, currentVersion)
                Result.success(
                    AppUpdateInfo(
                        isUpdateAvailable = isNewer,
                        latestVersion = release.tagName,
                        currentVersion = "v$currentVersion",
                        releaseTitle = release.name ?: release.tagName,
                        releaseNotes = release.body.orEmpty(),
                        downloadUrl = release.htmlUrl ?: AppConfig.GITHUB_RELEASES_API
                    )
                )
            } else {
                Result.failure(Exception("Не удалось получить информацию о релизе"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun isVersionNewer(latest: String, current: String): Boolean {
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }
}
