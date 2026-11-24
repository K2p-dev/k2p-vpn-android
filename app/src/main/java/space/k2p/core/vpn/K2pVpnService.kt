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
import space.k2p.core.preferences.SplitTunnelingPreferences

class K2pVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "space.k2p.action.CONNECT"
        const val ACTION_DISCONNECT = "space.k2p.action.DISCONNECT"
        const val EXTRA_CONFIG_JSON = "config_json"
        const val EXTRA_SERVER_NAME = "server_name"

        private const val CHANNEL_ID = "k2p_vpn_channel"
        private const val NOTIFICATION_ID = 2001
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var coreController: CoreController? = null
    private var statsJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    private var lastUploaded = 0L
    private var lastDownloaded = 0L
    private var sessionRxBytes = 0L
    private var sessionTxBytes = 0L
    private var lastSysRx = 0L
    private var lastSysTx = 0L

    private val callbackHandler = object : CoreCallbackHandler {
        override fun onEmitStatus(status: Long, message: String?): Long {
            return 0L
        }

        override fun shutdown(): Long {
            return 0L
        }

        override fun startup(): Long {
            return 0L
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            space.k2p.core.geodata.GeodataManager.initGeodata(this)
            Libv2ray.initCoreEnv(filesDir.absolutePath, "")
        } catch (_: Exception) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_CONNECT -> {
                val configJson = intent.getStringExtra(EXTRA_CONFIG_JSON).orEmpty()
                val serverName = intent.getStringExtra(EXTRA_SERVER_NAME).orEmpty()
                startVpn(configJson, serverName)
            }
            ACTION_DISCONNECT -> {
                stopVpn()
            }
        }

        return START_NOT_STICKY
    }

    private fun startVpn(configJson: String, serverName: String) {
        serviceScope.launch {
            try {
                RealVpnCoreEngine.internalState.value = VpnState.CONNECTING
                RealVpnCoreEngine.currentServerName = serverName

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
                if (isSplitEnabled) {
                    val excludedApps = splitPrefs.excludedPackagesFlow.first()
                    for (pkg in excludedApps) {
                        runCatching {
                            builder.addDisallowedApplication(pkg)
                        }
                    }
                }

                val pfd = builder.establish()
                if (pfd == null) {
                    RealVpnCoreEngine.internalState.value = VpnState.DISCONNECTED
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@launch
                }
                vpnInterface = pfd

                // 4. Initialize CoreController and start loop
                val controller = Libv2ray.newCoreController(callbackHandler)
                coreController = controller

                controller.startLoop(configJson, pfd.fd)
                RealVpnCoreEngine.internalState.value = VpnState.CONNECTED

                // 5. Start traffic stats polling
                startStatsPolling()

            } catch (e: Exception) {
                RealVpnCoreEngine.internalState.value = VpnState.DISCONNECTED
                stopVpn()
            }
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
        RealVpnCoreEngine.internalState.value = VpnState.STOPPING
        statsJob?.cancel()
        statsJob = null

        sessionRxBytes = 0L
        sessionTxBytes = 0L
        lastSysRx = 0L
        lastSysTx = 0L
        lastUploaded = 0L
        lastDownloaded = 0L

        serviceScope.launch {
            try {
                coreController?.stopLoop()
            } catch (_: Exception) {
            }
            try {
                vpnInterface?.close()
            } catch (_: Exception) {
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
        stopVpn()
        super.onDestroy()
    }
}
