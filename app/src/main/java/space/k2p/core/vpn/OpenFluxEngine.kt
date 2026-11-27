package space.k2p.core.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import space.k2p.core.logging.AppLogger
import space.k2p.core.logging.LogTag
import space.k2p.data.api.model.EmergencyTransportState
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

data class OpenFluxProcessState(
    val isRunning: Boolean = false,
    val socksPort: Int? = null,
    val transportDisplayName: String = "",
    val lastError: String? = null
)

object OpenFluxEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private var process: Process? = null
    private var outputReaderJob: Job? = null
    private var workDirRef: File? = null

    private val _state = MutableStateFlow(OpenFluxProcessState())
    val state: StateFlow<OpenFluxProcessState> = _state.asStateFlow()

    suspend fun start(
        context: Context,
        emergencyState: EmergencyTransportState
    ): Result<Int> = mutex.withLock {
        stopLocked("Перезапуск OpenFlux")

        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val binaryFile = File(nativeLibDir, "libopenflux.so")
        if (!binaryFile.exists()) {
            val msg = "Бинарник libopenflux.so не найден в $nativeLibDir"
            AppLogger.e(LogTag.OPENFLUX, msg)
            _state.value = OpenFluxProcessState(isRunning = false, lastError = msg)
            return Result.failure(IllegalStateException(msg))
        }
        if (!binaryFile.canExecute()) {
            runCatching { binaryFile.setExecutable(true, true) }
        }

        val docUrl = emergencyState.docUrl.trim()
        if (docUrl.isBlank() || docUrl.contains('\n') || docUrl.contains('\r')) {
            val msg = "URL документа не задан или содержит недопустимые символы"
            AppLogger.e(LogTag.OPENFLUX, msg)
            return Result.failure(IllegalArgumentException(msg))
        }

        val freePort = try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        } catch (e: Exception) {
            AppLogger.e(LogTag.OPENFLUX, "Не удалось выделить локальный порт: ${e.message}", e)
            return Result.failure(e)
        }

        val workDir = File(context.cacheDir, "openflux").apply { mkdirs() }
        workDirRef = workDir

        val secret = emergencyState.sessionSecret.trim().replace("\r", "").replace("\n", "")
        var secretFile: File? = null
        if (secret.length >= 16) {
            secretFile = File(workDir, "session.key").apply {
                if (exists()) delete()
                createNewFile()
                setReadable(false, false)
                setReadable(true, true)
                setWritable(false, false)
                setWritable(true, true)
                writeText(secret, Charsets.UTF_8)
            }
        } else if (secret.isNotBlank()) {
            AppLogger.w(
                LogTag.OPENFLUX,
                "sessionSecret короче 16 символов (len=${secret.length}), запуск без EncryptionKeyFile"
            )
        }

        val cookieStoreFile = File(workDir, "cookies-${emergencyState.transportType.openFluxName}.json")
        val confFile = File(workDir, "openflux.conf")

        val confContent = buildString {
            appendLine("[Interface]")
            appendLine("Role = client")
            appendLine("Inbound = socks5")
            appendLine("Transport = ${emergencyState.transportType.openFluxName}")
            appendLine("Socks5 = 127.0.0.1:$freePort")
            appendLine("CookieStore = ${cookieStoreFile.absolutePath}")
            appendLine("URL = $docUrl")
            if (secretFile != null) {
                appendLine("EncryptionKeyFile = ${secretFile.absolutePath}")
            }
            appendLine("Debug = 2")
        }
        confFile.apply {
            if (exists()) delete()
            createNewFile()
            setReadable(false, false)
            setReadable(true, true)
            setWritable(false, false)
            setWritable(true, true)
            writeText(confContent, Charsets.UTF_8)
        }

        val dnsServer = resolvePhysicalDns(context)
        AppLogger.i(
            LogTag.OPENFLUX,
            "Запуск изолированного процесса libopenflux.so: transport=${emergencyState.transportType.openFluxName}, socks5=127.0.0.1:$freePort, dns=$dnsServer, encrypted=${secretFile != null}"
        )

        val command = mutableListOf(
            binaryFile.absolutePath,
            "--role=client",
            "--inbound=socks5",
            "--socks5=127.0.0.1:$freePort",
            "--config=${confFile.absolutePath}"
        )

        return try {
            val pb = ProcessBuilder(command).apply {
                directory(workDir)
                redirectErrorStream(true)
                environment()["OPENFLUX_DNS"] = dnsServer
                environment()["HOME"] = workDir.absolutePath
            }

            val proc = pb.start()
            process = proc

            outputReaderJob = scope.launch {
                try {
                    BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { reader ->
                        while (isActive) {
                            val line = reader.readLine() ?: break
                            val trimmed = line.trim()
                            if (trimmed.isNotBlank()) {
                                when {
                                    trimmed.contains("fatal", ignoreCase = true) ||
                                        trimmed.contains("error", ignoreCase = true) ||
                                        trimmed.contains("failed", ignoreCase = true) ->
                                        AppLogger.w(LogTag.OPENFLUX, trimmed)
                                    else ->
                                        AppLogger.i(LogTag.OPENFLUX, trimmed)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        AppLogger.d(LogTag.OPENFLUX, "Поток чтения stdout/stderr завершён: ${e.message}")
                    }
                } finally {
                    val exitCode = runCatching { proc.exitValue() }.getOrNull()
                    if (exitCode != null) {
                        AppLogger.w(LogTag.OPENFLUX, "Процесс libopenflux.so завершился с кодом $exitCode")
                    }
                    if (process === proc) {
                        process = null
                        workDirRef?.let { dir ->
                            runCatching { File(dir, "openflux.conf").delete() }
                            runCatching { File(dir, "session.key").delete() }
                        }
                        _state.value = OpenFluxProcessState(isRunning = false)
                    }
                }
            }

            // Wait until local SOCKS5 port starts accepting TCP connections (up to 10s)
            val ready = waitForSocksReady(proc, freePort, timeoutMs = 10_000L)
            if (!ready) {
                val exitCode = runCatching { proc.exitValue() }.getOrNull()
                val errMsg = if (exitCode != null) {
                    "Процесс OpenFlux завершился до готовности SOCKS5 (код $exitCode)"
                } else {
                    "Таймаут ожидания локального SOCKS5 OpenFlux на 127.0.0.1:$freePort"
                }
                AppLogger.e(LogTag.OPENFLUX, errMsg)
                stopLocked(errMsg)
                _state.value = OpenFluxProcessState(isRunning = false, lastError = errMsg)
                return Result.failure(IllegalStateException(errMsg))
            }

            _state.value = OpenFluxProcessState(
                isRunning = true,
                socksPort = freePort,
                transportDisplayName = emergencyState.transportType.displayName,
                lastError = null
            )
            AppLogger.i(
                LogTag.OPENFLUX,
                "Локальный SOCKS5-прокси OpenFlux готов к приёму трафика на 127.0.0.1:$freePort"
            )
            Result.success(freePort)
        } catch (e: Exception) {
            AppLogger.e(LogTag.OPENFLUX, "Не удалось запустить libopenflux.so: ${e.message}", e)
            stopLocked("Ошибка запуска процесса")
            _state.value = OpenFluxProcessState(isRunning = false, lastError = e.message)
            Result.failure(e)
        }
    }

    suspend fun stop(reason: String = "Остановка по запросу") = mutex.withLock {
        stopLocked(reason)
    }

    fun stopAsync(reason: String = "Остановка по запросу") {
        scope.launch {
            stop(reason)
        }
    }

    private fun stopLocked(reason: String) {
        val currentProc = process
        if (currentProc != null) {
            AppLogger.i(LogTag.OPENFLUX, "Остановка процесса libopenflux.so ($reason)...")
            runCatching { currentProc.destroy() }
            val exited = runCatching {
                currentProc.waitFor(800, java.util.concurrent.TimeUnit.MILLISECONDS)
            }.getOrDefault(false)
            if (!exited) {
                runCatching { currentProc.destroyForcibly() }
            }
        }
        process = null
        outputReaderJob?.cancel()
        outputReaderJob = null

        // Clean sensitive temp config & key files
        workDirRef?.let { dir ->
            runCatching { File(dir, "openflux.conf").delete() }
            runCatching { File(dir, "session.key").delete() }
        }
        _state.value = OpenFluxProcessState(isRunning = false)
    }

    private suspend fun waitForSocksReady(proc: Process, port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive) {
                return false
            }
            val reachable = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 250)
                }
                true
            }.getOrDefault(false)
            if (reachable) {
                return true
            }
            delay(150)
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun resolvePhysicalDns(context: Context): String {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                for (network in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(network) ?: continue
                    val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    val notVpn = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                        !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    if (!hasInternet || !notVpn) continue

                    val linkProps = cm.getLinkProperties(network) ?: continue
                    val ipv4Dns = linkProps.dnsServers
                        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                        ?.hostAddress
                    if (!ipv4Dns.isNullOrBlank()) {
                        return "$ipv4Dns:53"
                    }
                }
            }
        } catch (_: Exception) {
        }
        // Fallback to Yandex DNS (accessible inside whitelist networks)
        return "77.88.8.8:53"
    }
}
