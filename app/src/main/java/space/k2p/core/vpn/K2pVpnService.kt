package space.k2p.core.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import org.json.JSONObject
import space.k2p.MainActivity
import space.k2p.R
import space.k2p.core.config.AppConfig
import space.k2p.core.logging.AppLogger
import space.k2p.core.logging.LogTag
import space.k2p.core.network.WhitelistDetectorService
import space.k2p.core.preferences.SplitTunnelingPreferences
import java.io.File
import java.io.RandomAccessFile

class K2pVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "space.k2p.action.CONNECT"
        const val ACTION_DISCONNECT = "space.k2p.action.DISCONNECT"
        const val EXTRA_CONFIG_JSON = "config_json"
        const val EXTRA_SERVER_NAME = "server_name"
        const val EXTRA_EMERGENCY_MODE = "emergency_mode"

        private const val CHANNEL_ID = "k2p_vpn_channel"
        private const val NOTIFICATION_ID = 2001
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var coreController: CoreController? = null
    private var statsJob: Job? = null
    private var healthCheckJob: Job? = null
    private var isEmergencySession = false
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    private var lastUploaded = 0L
    private var lastDownloaded = 0L
    private var sessionRxBytes = 0L
    private var sessionTxBytes = 0L
    private var lastSysRx = 0L
    private var lastSysTx = 0L

    private var xrayLogReadOffset = 0L

    private val callbackHandler = object : CoreCallbackHandler {
        override fun onEmitStatus(status: Long, message: String?): Long {
            if (!message.isNullOrBlank()) {
                AppLogger.i(LogTag.XRAY, "Core status=$status: $message")
            }
            return 0L
        }

        override fun shutdown(): Long {
            AppLogger.w(LogTag.XRAY, "CoreCallbackHandler.shutdown() вызван")
            val currentState = RealVpnCoreEngine.internalState.value
            if (currentState == VpnState.CONNECTED || currentState == VpnState.CONNECTING) {
                AppLogger.e(LogTag.VPN, "Неожиданный обрыв Xray-ядра (state=$currentState)")
                WhitelistDetectorService.triggerAutoCheckOnVpnProblem(
                    applicationContext,
                    "Неожиданный обрыв VPN-туннеля"
                )
            }
            return 0L
        }

        override fun startup(): Long {
            AppLogger.i(LogTag.XRAY, "CoreCallbackHandler.startup() выполнен")
            return 0L
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        WhitelistDetectorService.vpnProtectCallback = { socket -> protect(socket) }
        try {
            space.k2p.core.geodata.GeodataManager.initGeodata(this)
            Libv2ray.initCoreEnv(filesDir.absolutePath, "")
            AppLogger.i(LogTag.VPN, "K2pVpnService создан, initCoreEnv успешно выполнен")
        } catch (e: Exception) {
            AppLogger.e(LogTag.VPN, "Ошибка initCoreEnv в K2pVpnService.onCreate: ${e.message}", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_CONNECT -> {
                val configJson = intent.getStringExtra(EXTRA_CONFIG_JSON).orEmpty()
                val serverName = intent.getStringExtra(EXTRA_SERVER_NAME).orEmpty()
                val emergencyMode = intent.getBooleanExtra(EXTRA_EMERGENCY_MODE, false)
                isEmergencySession = emergencyMode
                RealVpnCoreEngine.isEmergencyMode = emergencyMode
                startVpn(configJson, serverName, emergencyMode)
            }
            ACTION_DISCONNECT -> {
                stopVpn()
            }
        }

        return START_NOT_STICKY
    }

    private fun startVpn(configJson: String, serverName: String, emergencyMode: Boolean) {
        serviceScope.launch {
            try {
                if (!emergencyMode) {
                    OpenFluxEngine.stop("Переключение на стандартный VPN")
                }
                RealVpnCoreEngine.internalState.value = VpnState.CONNECTING
                RealVpnCoreEngine.currentServerName = serverName
                AppLogger.i(LogTag.VPN, "Запуск VPN-подключения к узлу: «$serverName»")

                // 1. Start foreground notification
                val notification = createNotification(serverName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }

                // 2. Stop old core loop and close old interface if reconnecting
                healthCheckJob?.cancel()
                coreController?.stopLoop()
                vpnInterface?.close()
                statsJob?.cancel()

                // 3. Setup VpnService.Builder
                val builder = Builder()
                    .setSession(AppConfig.APP_NAME)
                    .setMtu(1500)
                    .addAddress("10.0.0.2", 30)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .addRoute("0.0.0.0", 0)

                // Protect our own app package from looping
                builder.addDisallowedApplication(packageName)

                // Add split tunneling excluded packages
                val splitPrefs = SplitTunnelingPreferences(applicationContext)
                val isSplitEnabled = splitPrefs.isEnabledFlow.first()
                var excludedCount = 0
                if (isSplitEnabled) {
                    val excludedApps = splitPrefs.excludedPackagesFlow.first()
                    for (pkg in excludedApps) {
                        runCatching {
                            builder.addDisallowedApplication(pkg)
                            excludedCount++
                        }
                    }
                }
                AppLogger.d(LogTag.VPN, "TUN подготовлен (split=$isSplitEnabled, исключено=$excludedCount)")

                val pfd = builder.establish()
                if (pfd == null) {
                    AppLogger.e(LogTag.VPN, "Не удалось создать TUN-интерфейс (builder.establish() вернул null)")
                    WhitelistDetectorService.triggerAutoCheckOnVpnProblem(
                        applicationContext,
                        "Не удалось поднять TUN-интерфейс"
                    )
                    RealVpnCoreEngine.internalState.value = VpnState.DISCONNECTED
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@launch
                }
                vpnInterface = pfd
                AppLogger.i(LogTag.VPN, "TUN-интерфейс установлен (fd=${pfd.fd})")

                // Reset xray_error.log before starting core
                resetXrayLogFile()

                // 4. Initialize CoreController and start loop
                val controller = Libv2ray.newCoreController(callbackHandler)
                coreController = controller

                controller.startLoop(configJson, pfd.fd)
                RealVpnCoreEngine.internalState.value = VpnState.CONNECTED
                AppLogger.i(LogTag.XRAY, "Xray startLoop успешно запущен (сервер=«$serverName»)")

                // 5. Start traffic stats & Xray log polling
                startStatsPolling()

                // 6. Verify real outbound tunnel connectivity after startup
                verifyTunnelConnectivity(controller, serverName, emergencyMode)

            } catch (e: Exception) {
                AppLogger.e(LogTag.VPN, "Ошибка запуска VPN/Xray: ${e.message}", e)
                drainXrayLogs()
                if (!emergencyMode) {
                    WhitelistDetectorService.triggerAutoCheckOnVpnProblem(
                        applicationContext,
                        "Ошибка запуска VPN: ${e.message ?: "неизвестно"}"
                    )
                }
                RealVpnCoreEngine.internalState.value = VpnState.DISCONNECTED
                stopVpn()
            }
        }
    }

    private fun verifyTunnelConnectivity(
        controller: CoreController,
        serverName: String,
        emergencyMode: Boolean
    ) {
        healthCheckJob?.cancel()
        healthCheckJob = serviceScope.launch {
            delay(if (emergencyMode) 4000L else 1800L)
            if (!isActive || RealVpnCoreEngine.internalState.value != VpnState.CONNECTED) return@launch
            try {
                val delayMs = controller.measureDelay("http://www.gstatic.com/generate_204")
                if (delayMs > 0) {
                    AppLogger.i(LogTag.VPN, "Проверка туннеля «$serverName»: OK (${delayMs} мс)")
                } else {
                    AppLogger.w(LogTag.VPN, "Проверка туннеля «$serverName» вернула $delayMs мс")
                    if (!emergencyMode) {
                        WhitelistDetectorService.triggerAutoCheckOnVpnProblem(
                            applicationContext,
                            "Нет ответа через VPN-туннель ($serverName)"
                        )
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(LogTag.VPN, "Туннель «$serverName» не пропускает трафик: ${e.message}")
                drainXrayLogs()
                if (!emergencyMode) {
                    WhitelistDetectorService.triggerAutoCheckOnVpnProblem(
                        applicationContext,
                        "Ошибка трафика через VPN ($serverName): ${e.message}"
                    )
                }
            }
        }
    }

    private fun resetXrayLogFile() {
        try {
            val logFile = File(filesDir, "xray_error.log")
            if (logFile.exists()) {
                logFile.writeText("")
            }
            xrayLogReadOffset = 0L
        } catch (_: Exception) {
        }
    }

    private fun drainXrayLogs() {
        try {
            val logFile = File(filesDir, "xray_error.log")
            if (!logFile.exists()) return
            val len = logFile.length()
            if (len < xrayLogReadOffset) {
                xrayLogReadOffset = 0L
            }
            if (len <= xrayLogReadOffset) return

            RandomAccessFile(logFile, "r").use { raf ->
                raf.seek(xrayLogReadOffset)
                val newText = buildString {
                    var line = raf.readLine()
                    while (line != null) {
                        val decoded = String(line.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8).trim()
                        if (decoded.isNotBlank()) {
                            appendLine(decoded)
                        }
                        line = raf.readLine()
                    }
                }
                xrayLogReadOffset = raf.filePointer

                newText.lines().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotBlank()) {
                        if (trimmed.contains("Error", ignoreCase = true) || trimmed.contains("failed", ignoreCase = true)) {
                            AppLogger.w(LogTag.XRAY, trimmed)
                        } else {
                            AppLogger.i(LogTag.XRAY, trimmed)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun startStatsPolling() {
        statsJob?.cancel()
        lastUploaded = 0L
        lastDownloaded = 0L
        sessionRxBytes = 0L
        sessionTxBytes = 0L

        // Initialize baseline for TrafficStats fallback
        val myUid = Process.myUid()
        val rawRx = TrafficStats.getTotalRxBytes() - TrafficStats.getUidRxBytes(myUid)
        val rawTx = TrafficStats.getTotalTxBytes() - TrafficStats.getUidTxBytes(myUid)
        lastSysRx = (if (rawRx > 0) rawRx else 0L) / 2
        lastSysTx = (if (rawTx > 0) rawTx else 0L) / 2

        statsJob = serviceScope.launch {
            while (isActive && RealVpnCoreEngine.internalState.value == VpnState.CONNECTED) {
                delay(1000)
                drainXrayLogs()
                try {
                    pollTrafficStats()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun pollTrafficStats() {
        var xraySpeedDown = 0L
        var xraySpeedUp = 0L

        // 1. Try querying Xray stats
        try {
            val statsStr = coreController?.queryAllOutboundTrafficStats().orEmpty()
            if (statsStr.isNotBlank()) {
                val (up, down) = parseTrafficStatsString(statsStr)
                if (lastUploaded in 1..up) {
                    xraySpeedUp = up - lastUploaded
                }
                if (lastDownloaded in 1..down) {
                    xraySpeedDown = down - lastDownloaded
                }
                lastUploaded = up
                lastDownloaded = down
            }
        } catch (_: Exception) {
        }

        var speedDown = xraySpeedDown
        var speedUp = xraySpeedUp

        // 2. Fallback to system TrafficStats if Xray reports 0
        val myUid = Process.myUid()
        val rawRx = TrafficStats.getTotalRxBytes() - TrafficStats.getUidRxBytes(myUid)
        val rawTx = TrafficStats.getTotalTxBytes() - TrafficStats.getUidTxBytes(myUid)
        val currentSysRx = (if (rawRx > 0) rawRx else 0L) / 2
        val currentSysTx = (if (rawTx > 0) rawTx else 0L) / 2

        var sysDeltaDown = 0L
        var sysDeltaUp = 0L
        if (lastSysRx > 0L && currentSysRx >= lastSysRx) {
            sysDeltaDown = currentSysRx - lastSysRx
        }
        if (lastSysTx > 0L && currentSysTx >= lastSysTx) {
            sysDeltaUp = currentSysTx - lastSysTx
        }

        lastSysRx = currentSysRx
        lastSysTx = currentSysTx

        if (speedDown <= 0L && sysDeltaDown > 0L) {
            speedDown = sysDeltaDown
        }
        if (speedUp <= 0L && sysDeltaUp > 0L) {
            speedUp = sysDeltaUp
        }

        sessionRxBytes += speedDown
        sessionTxBytes += speedUp

        RealVpnCoreEngine.internalStats.value = VpnTrafficStats(
            uploadSpeedBps = speedUp,
            downloadSpeedBps = speedDown,
            totalBytesUploaded = sessionTxBytes,
            totalBytesDownloaded = sessionRxBytes
        )
    }

    private fun parseTrafficStatsString(statsStr: String): Pair<Long, Long> {
        var totalUp = 0L
        var totalDown = 0L

        if (statsStr.trim().startsWith("{")) {
            val json = JSONObject(statsStr)
            if (json.has("proxy")) {
                val proxyObj = json.optJSONObject("proxy")
                totalUp = proxyObj?.optLong("uplink", 0L) ?: 0L
                totalDown = proxyObj?.optLong("downlink", 0L) ?: 0L
            } else {
                totalUp = json.optLong("uplink", 0L)
                totalDown = json.optLong("downlink", 0L)
            }
        } else {
            for (line in statsStr.lines()) {
                val parts = line.split(":")
                if (parts.size >= 2) {
                    val key = parts[0].trim().lowercase(java.util.Locale.ROOT)
                    val value = parts[1].trim().toLongOrNull() ?: 0L
                    if (key.contains("proxy") || (!key.contains("direct") && !key.contains("block") && !key.contains("api"))) {
                        if (key.contains("uplink")) {
                            totalUp += value
                        } else if (key.contains("downlink")) {
                            totalDown += value
                        }
                    }
                }
            }
        }
        return Pair(totalUp, totalDown)
    }

    private fun stopVpn() {
        AppLogger.i(LogTag.VPN, "Остановка VPN-туннеля")
        RealVpnCoreEngine.internalState.value = VpnState.STOPPING
        isEmergencySession = false
        RealVpnCoreEngine.isEmergencyMode = false
        healthCheckJob?.cancel()
        healthCheckJob = null
        statsJob?.cancel()
        statsJob = null

        sessionRxBytes = 0L
        sessionTxBytes = 0L
        lastSysRx = 0L
        lastSysTx = 0L
        lastUploaded = 0L
        lastDownloaded = 0L

        serviceScope.launch {
            drainXrayLogs()
            try {
                coreController?.stopLoop()
            } catch (e: Exception) {
                AppLogger.w(LogTag.XRAY, "Ошибка stopLoop: ${e.message}")
            }
            runCatching { OpenFluxEngine.stop("Остановка VPN-туннеля") }
            try {
                vpnInterface?.close()
            } catch (e: Exception) {
                AppLogger.w(LogTag.VPN, "Ошибка закрытия TUN: ${e.message}")
            }
            vpnInterface = null
            coreController = null

            RealVpnCoreEngine.internalStats.value = VpnTrafficStats()
            RealVpnCoreEngine.internalState.value = VpnState.DISCONNECTED

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createNotification(serverName: String): Notification {
        val disconnectIntent = Intent(this, K2pVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            1,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val appIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val appPendingIntent = PendingIntent.getActivity(
            this,
            0,
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val displayName = serverName.ifBlank { "Защищённое подключение" }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_k2p_monochrome)
            .setContentTitle("Как 2 пальца: Подключено")
            .setContentText(displayName)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(appPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Отключить",
                disconnectPendingIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN «Как 2 пальца»",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Уведомление активного VPN-подключения «Как 2 пальца»"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        WhitelistDetectorService.vpnProtectCallback = null
        stopVpn()
        super.onDestroy()
    }
}
