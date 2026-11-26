package space.k2p.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import space.k2p.BuildConfig
import space.k2p.core.logging.AppLogger
import space.k2p.core.logging.LogTag
import space.k2p.data.repository.AppUpdateInfo
import space.k2p.data.repository.AppUpdateRepository
import java.io.File
import java.io.FileOutputStream

sealed interface OtaDownloadState {
    data object Idle : OtaDownloadState
    data class Downloading(
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : OtaDownloadState
    data class Downloaded(val filePath: String) : OtaDownloadState
    data class Error(val message: String) : OtaDownloadState
}

data class OtaUpdateUiState(
    val isChecking: Boolean = false,
    val updateInfo: AppUpdateInfo? = null,
    val updateAvailable: Boolean = false,
    val latestVersion: String = "",
    val currentVersion: String = "v${BuildConfig.VERSION_NAME}",
    val changelogItems: List<String> = emptyList(),
    val apkDownloadUrl: String = "",
    val hasDirectApkAsset: Boolean = false,
    val checkError: String? = null,
    val downloadState: OtaDownloadState = OtaDownloadState.Idle
)

enum class InstallLaunchResult {
    LAUNCHED_INSTALLER,
    REQUESTED_UNKNOWN_SOURCES_PERMISSION,
    FILE_NOT_FOUND,
    ERROR
}

class AppUpdateManager(
    private val appContext: Context,
    private val appUpdateRepository: AppUpdateRepository,
    private val githubOkHttpClient: OkHttpClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(OtaUpdateUiState())
    val state: StateFlow<OtaUpdateUiState> = _state.asStateFlow()

    private var downloadJob: Job? = null
    @Volatile
    private var activeCall: Call? = null

    suspend fun checkForUpdate(): Result<AppUpdateInfo> {
        _state.update { it.copy(isChecking = true, checkError = null) }
        val result = appUpdateRepository.checkForUpdate()
        result.onSuccess { info ->
            val items = parseChangelog(info.releaseNotes)
            val normalizedLatest = if (info.latestVersion.startsWith("v", ignoreCase = true)) {
                info.latestVersion
            } else {
                "v${info.latestVersion}"
            }

            // Check if APK for this release was already downloaded
            val cachedApk = getUpdateApkFile()
            val prevState = _state.value
            val existingDownloadState = if (
                prevState.downloadState is OtaDownloadState.Downloaded &&
                prevState.latestVersion == normalizedLatest &&
                cachedApk.exists() && cachedApk.length() > 100_000L
            ) {
                OtaDownloadState.Downloaded(cachedApk.absolutePath)
            } else if (prevState.downloadState is OtaDownloadState.Downloading) {
                prevState.downloadState
            } else {
                OtaDownloadState.Idle
            }

            _state.update {
                it.copy(
                    isChecking = false,
                    updateInfo = info,
                    updateAvailable = info.isUpdateAvailable,
                    latestVersion = normalizedLatest,
                    currentVersion = info.currentVersion,
                    changelogItems = items,
                    apkDownloadUrl = info.downloadUrl,
                    hasDirectApkAsset = info.hasDirectApkAsset,
                    checkError = null,
                    downloadState = existingDownloadState
                )
            }
        }.onFailure { err ->
            _state.update {
                it.copy(
                    isChecking = false,
                    checkError = err.message ?: "Не удалось проверить обновления"
                )
            }
        }
        return result
    }

    fun startDownload() {
        val url = _state.value.apkDownloadUrl
        if (url.isBlank()) {
            AppLogger.e(LogTag.OTA, "Отмена загрузки: ссылка на APK пустая")
            _state.update {
                it.copy(downloadState = OtaDownloadState.Error("Ссылка для скачивания APK не найдена"))
            }
            return
        }

        if (downloadJob?.isActive == true) {
            return
        }

        downloadJob = scope.launch {
            val updatesDir = File(appContext.cacheDir, "updates").apply { mkdirs() }
            // Delete any previously cached files before starting fresh download
            updatesDir.listFiles()?.forEach { old ->
                runCatching { old.delete() }
            }

            val tmpFile = File(updatesDir, "k2p-update.apk.tmp")
            val targetFile = File(updatesDir, "k2p-update.apk")

            AppLogger.i(LogTag.OTA, "Начинаем загрузку обновления: $url")
            _state.update {
                it.copy(downloadState = OtaDownloadState.Downloading(0f, 0L, -1L))
            }

            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "K2P-VPN-Android")
                    .build()

                val call = githubOkHttpClient.newCall(request)
                activeCall = call

                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("HTTP ${response.code}: ${response.message}")
                    }
                    val body = response.body ?: throw Exception("Пустой ответ сервера")
                    val contentLength = body.contentLength()
                    var downloaded = 0L
                    var lastLoggedPercent = -1
                    var lastEmitTime = 0L

                    body.byteStream().use { input ->
                        FileOutputStream(tmpFile).use { output ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                downloaded += read

                                val now = System.currentTimeMillis()
                                val progress = if (contentLength > 0L) {
                                    (downloaded.toFloat() / contentLength.toFloat()).coerceIn(0f, 1f)
                                } else {
                                    0f
                                }

                                val percent = (progress * 100).toInt()
                                if (percent / 25 > lastLoggedPercent / 25 && contentLength > 0L) {
                                    lastLoggedPercent = percent
                                    AppLogger.d(LogTag.OTA, "Загрузка APK: $percent% ($downloaded / $contentLength байт)")
                                }

                                if (now - lastEmitTime >= 80L || downloaded == contentLength) {
                                    lastEmitTime = now
                                    _state.update {
                                        it.copy(
                                            downloadState = OtaDownloadState.Downloading(
                                                progress = progress,
                                                downloadedBytes = downloaded,
                                                totalBytes = contentLength
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (tmpFile.length() < 100_000L) {
                        throw Exception("Загруженный файл повреждён или слишком мал (${tmpFile.length()} байт)")
                    }

                    if (targetFile.exists()) targetFile.delete()
                    if (!tmpFile.renameTo(targetFile)) {
                        tmpFile.copyTo(targetFile, overwrite = true)
                        tmpFile.delete()
                    }

                    AppLogger.i(LogTag.OTA, "APK успешно загружен (${targetFile.length()} байт): ${targetFile.absolutePath}")
                    _state.update {
                        it.copy(downloadState = OtaDownloadState.Downloaded(targetFile.absolutePath))
                    }
                }
            } catch (_: CancellationException) {
                AppLogger.w(LogTag.OTA, "Загрузка обновления отменена пользователем")
                runCatching { tmpFile.delete() }
                runCatching { targetFile.delete() }
                _state.update { it.copy(downloadState = OtaDownloadState.Idle) }
            } catch (e: Exception) {
                runCatching { tmpFile.delete() }
                val msg = e.localizedMessage ?: e.message ?: "Ошибка скачивания"
                AppLogger.e(LogTag.OTA, "Ошибка загрузки обновления: $msg", e)
                _state.update {
                    it.copy(downloadState = OtaDownloadState.Error("Не удалось загрузить обновление: $msg"))
                }
            } finally {
                activeCall = null
            }
        }
    }

    fun cancelDownload() {
        AppLogger.i(LogTag.OTA, "Запрос остановки загрузки APK")
        runCatching { activeCall?.cancel() }
        downloadJob?.cancel()
        downloadJob = null
        val updatesDir = File(appContext.cacheDir, "updates")
        updatesDir.listFiles()?.forEach { runCatching { it.delete() } }
        _state.update { it.copy(downloadState = OtaDownloadState.Idle) }
    }

    fun resetDownloadState() {
        if (_state.value.downloadState !is OtaDownloadState.Downloading) {
            _state.update { it.copy(downloadState = OtaDownloadState.Idle) }
        }
    }

    fun installUpdate(context: Context): InstallLaunchResult {
        val apkFile = getUpdateApkFile()
        if (!apkFile.exists() || apkFile.length() < 100_000L) {
            AppLogger.e(LogTag.OTA, "Файл обновления не найден для установки: ${apkFile.absolutePath}")
            _state.update { it.copy(downloadState = OtaDownloadState.Error("Файл обновления не найден")) }
            return InstallLaunchResult.FILE_NOT_FOUND
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            AppLogger.w(LogTag.OTA, "Нет разрешения REQUEST_INSTALL_PACKAGES, открываем настройки источника")
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(settingsIntent)
            return InstallLaunchResult.REQUESTED_UNKNOWN_SOURCES_PERMISSION
        }

        return try {
            val authority = "${context.packageName}.fileprovider"
            val contentUri = FileProvider.getUriForFile(context, authority, apkFile)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            AppLogger.i(LogTag.OTA, "Запуск системного установщика Android: $contentUri")
            context.startActivity(installIntent)
            InstallLaunchResult.LAUNCHED_INSTALLER
        } catch (e: Exception) {
            AppLogger.e(LogTag.OTA, "Ошибка запуска системного установщика: ${e.message}", e)
            InstallLaunchResult.ERROR
        }
    }

    private fun getUpdateApkFile(): File {
        return File(File(appContext.cacheDir, "updates"), "k2p-update.apk")
    }

    private fun parseChangelog(rawBody: String): List<String> {
        if (rawBody.isBlank()) {
            return listOf("Улучшена стабильность работы и исправлены ошибки")
        }
        val lines = rawBody.lines()
            .map { it.trim() }
            .filter { line ->
                line.isNotBlank() &&
                    !line.startsWith("#") &&
                    !line.startsWith("**Full Changelog**")
            }
            .map { line ->
                line.replaceFirst(Regex("^([-*•]|\\d+[.)])\\s+"), "").trim()
            }
            .filter { it.isNotBlank() }

        return if (lines.isNotEmpty()) {
            lines
        } else {
            listOf("Улучшена стабильность работы и исправлены ошибки")
        }
    }
}
