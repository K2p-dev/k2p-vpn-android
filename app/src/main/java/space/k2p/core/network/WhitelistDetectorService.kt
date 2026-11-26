package space.k2p.core.network

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import space.k2p.MainActivity
import space.k2p.R
import space.k2p.core.logging.AppLogger
import space.k2p.core.logging.LogTag
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

enum class WhitelistVerdict(val title: String) {
    IDLE("Не проверялось"),
    NO_WHITELISTS("Белых списков нет"),
    WHITELISTS_ACTIVE("Белые списки активны"),
    NO_NETWORK("Нет сети / никто не ответил"),
    ERROR("Ошибка")
}

data class HostProbeResult(
    val url: String,
    val host: String,
    val isWhitelistGroup: Boolean,
    val isReachable: Boolean,
    val httpCode: Int? = null,
    val latencyMs: Long = 0L,
    val error: String? = null
)

data class WhitelistDiagnosticState(
    val isChecking: Boolean = false,
    val verdict: WhitelistVerdict = WhitelistVerdict.IDLE,
    val summary: String = "",
    val networkLabel: String = "",
    val results: List<HostProbeResult> = emptyList(),
    val lastCheckedAtMs: Long? = null
)

object WhitelistDetectorService {

    private const val PREFS_NAME = "k2p_whitelist_prefs"
    private const val KEY_LAST_AUTO_CHECK_TS = "last_auto_check_timestamp"
    private const val AUTO_CHECK_COOLDOWN_MS = 600_000L // 10 minutes
    private const val TIMEOUT_MS = 3500L

    private const val DIAGNOSTICS_CHANNEL_ID = "k2p_diagnostics"
    private const val DIAGNOSTICS_NOTIFICATION_ID = 3002

    private val WHITELIST_HOSTS = listOf(
        "https://max.ru",
        "https://yandex.ru",
        "https://vk.ru"
    )

    private val EXTERNAL_HOSTS = listOf(
        "https://google.com",
        "https://github.com",
        "https://cloudflare.com"
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checkMutex = Mutex()
    private val autoCooldownLock = Any()

    @Volatile
    var vpnProtectCallback: ((Socket) -> Boolean)? = null

    private val _state = MutableStateFlow(WhitelistDiagnosticState())
    val state: StateFlow<WhitelistDiagnosticState> = _state.asStateFlow()

    fun runManualCheck(context: Context) {
        serviceScope.launch {
            runDiagnosticCheck(
                context = context.applicationContext,
                isAutomatic = false,
                reason = "Ручная проверка пользователем"
            )
        }
    }

    fun triggerAutoCheckOnVpnProblem(context: Context, reason: String) {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        val remainingSec: Long
        synchronized(autoCooldownLock) {
            val lastTs = prefs.getLong(KEY_LAST_AUTO_CHECK_TS, 0L)
            val elapsed = now - lastTs
            if (elapsed in 0 until AUTO_CHECK_COOLDOWN_MS) {
                remainingSec = (AUTO_CHECK_COOLDOWN_MS - elapsed) / 1000L
                AppLogger.d(
                    LogTag.WHITELIST,
                    "Автопроверка пропущена (причина: $reason): кулдаун 10 мин, осталось ${remainingSec}с"
                )
                return
            }
            prefs.edit().putLong(KEY_LAST_AUTO_CHECK_TS, now).apply()
        }

        AppLogger.i(LogTag.WHITELIST, "Запуск автодиагностики белых списков (причина: $reason)")
        serviceScope.launch {
            runDiagnosticCheck(
                context = appCtx,
                isAutomatic = true,
                reason = reason
            )
        }
    }

    private suspend fun runDiagnosticCheck(
        context: Context,
        isAutomatic: Boolean,
        reason: String
    ): WhitelistDiagnosticState = checkMutex.withLock {
        _state.update { it.copy(isChecking = true) }
        AppLogger.i(
            LogTag.WHITELIST,
            "Старт проверки белых списков (режим=${if (isAutomatic) "авто" else "ручной"}, причина=$reason)"
        )

        return try {
            val physicalNetworkInfo = findPhysicalNetwork(context)
            if (physicalNetworkInfo == null) {
                AppLogger.w(LogTag.WHITELIST, "Активный физический интерфейс (Wi-Fi/Cellular) не найден")
                val noNetState = WhitelistDiagnosticState(
                    isChecking = false,
                    verdict = WhitelistVerdict.NO_NETWORK,
                    summary = "Нет активного подключения к Wi-Fi или мобильной сети",
                    networkLabel = "Нет сети",
                    results = emptyList(),
                    lastCheckedAtMs = System.currentTimeMillis()
                )
                _state.value = noNetState
                if (isAutomatic) {
                    sendDiagnosticNotification(context, WhitelistVerdict.NO_NETWORK)
                }
                return@withLock noNetState
            }

            val (network, networkDesc) = physicalNetworkInfo
            AppLogger.i(LogTag.WHITELIST, "Выбран физический интерфейс в обход VPN: $networkDesc")

            val client = buildBypassClient(network)

            val allResults = coroutineScope {
                val ruJobs = WHITELIST_HOSTS.map { url ->
                    async(Dispatchers.IO) { probeHost(client, url, isWhitelistGroup = true) }
                }
                val extJobs = EXTERNAL_HOSTS.map { url ->
                    async(Dispatchers.IO) { probeHost(client, url, isWhitelistGroup = false) }
                }
                (ruJobs + extJobs).awaitAll()
            }

            val ruOk = allResults.count { it.isWhitelistGroup && it.isReachable }
            val extOk = allResults.count { !it.isWhitelistGroup && it.isReachable }

            val (verdict, summary) = when {
                ruOk == 0 && extOk == 0 -> {
                    WhitelistVerdict.NO_NETWORK to "Ни один сервер не ответил (РФ: 0/3, Внешние: 0/3)"
                }
                ruOk >= 2 && extOk == 0 -> {
                    WhitelistVerdict.WHITELISTS_ACTIVE to "Доступны только сайты РФ ($ruOk/3), внешний интернет заблокирован (0/3)"
                }
                ruOk >= 2 && extOk >= 2 -> {
                    WhitelistVerdict.NO_WHITELISTS to "Оба сегмента сети доступны (РФ: $ruOk/3, Внешние: $extOk/3)"
                }
                extOk == 0 && ruOk >= 1 -> {
                    WhitelistVerdict.WHITELISTS_ACTIVE to "Отвечают только отдельные РФ-ресурсы ($ruOk/3), внешние недоступны (0/3)"
                }
                else -> {
                    WhitelistVerdict.NO_WHITELISTS to "Частичная доступность (РФ: $ruOk/3, Внешние: $extOk/3)"
                }
            }

            AppLogger.i(LogTag.WHITELIST, "Вердикт проверки: ${verdict.title} — $summary")

            val resultState = WhitelistDiagnosticState(
                isChecking = false,
                verdict = verdict,
                summary = summary,
                networkLabel = networkDesc,
                results = allResults,
                lastCheckedAtMs = System.currentTimeMillis()
            )
            _state.value = resultState

            if (isAutomatic && (verdict == WhitelistVerdict.WHITELISTS_ACTIVE || verdict == WhitelistVerdict.NO_NETWORK)) {
                sendDiagnosticNotification(context, verdict)
            }

            resultState
        } catch (e: Exception) {
            AppLogger.e(LogTag.WHITELIST, "Ошибка при проверке белых списков: ${e.message}", e)
            val errState = WhitelistDiagnosticState(
                isChecking = false,
                verdict = WhitelistVerdict.ERROR,
                summary = "Ошибка: ${e.localizedMessage ?: e.javaClass.simpleName}",
                networkLabel = "",
                results = emptyList(),
                lastCheckedAtMs = System.currentTimeMillis()
            )
            _state.value = errState
            errState
        }
    }

    @Suppress("DEPRECATION")
    private fun findPhysicalNetwork(context: Context): Pair<Network, String>? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null

        val candidates = mutableListOf<Network>()
        cm.activeNetwork?.let { candidates.add(it) }
        cm.allNetworks.forEach { net ->
            if (!candidates.contains(net)) {
                candidates.add(net)
            }
        }

        // Prefer Wi-Fi or Cellular/Ethernet that explicitly has NET_CAPABILITY_NOT_VPN and NET_CAPABILITY_INTERNET
        var fallbackCandidate: Pair<Network, String>? = null
        for (network in candidates) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val notVpn = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

            if (!hasInternet || !notVpn) continue

            val transportName = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Network"
            }
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val desc = "$transportName (handle=${network.networkHandle}, validated=$validated)"

            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            ) {
                if (validated) {
                    return network to desc
                }
                if (fallbackCandidate == null) {
                    fallbackCandidate = network to desc
                }
            } else if (fallbackCandidate == null) {
                fallbackCandidate = network to desc
            }
        }
        return fallbackCandidate
    }

    private fun buildBypassClient(network: Network): OkHttpClient {
        val baseFactory = network.socketFactory
        val protectedSocketFactory = object : SocketFactory() {
            private fun wrap(socket: Socket): Socket {
                vpnProtectCallback?.invoke(socket)
                return socket
            }

            override fun createSocket(): Socket = wrap(baseFactory.createSocket())
            override fun createSocket(host: String?, port: Int): Socket =
                wrap(baseFactory.createSocket(host, port))
            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
                wrap(baseFactory.createSocket(host, port, localHost, localPort))
            override fun createSocket(host: InetAddress?, port: Int): Socket =
                wrap(baseFactory.createSocket(host, port))
            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
                wrap(baseFactory.createSocket(address, port, localAddress, localPort))
        }

        val networkDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                return network.getAllByName(hostname).toList()
            }
        }

        return OkHttpClient.Builder()
            .socketFactory(protectedSocketFactory)
            .dns(networkDns)
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private suspend fun probeHost(
        client: OkHttpClient,
        url: String,
        isWhitelistGroup: Boolean
    ): HostProbeResult = withContext(Dispatchers.IO) {
        val host = url.removePrefix("https://").removePrefix("http://")
        val groupLabel = if (isWhitelistGroup) "РФ" else "Внешний"
        val startMs = System.currentTimeMillis()

        try {
            val headRequest = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", "Mozilla/5.0 (Android) K2P-Diagnostic")
                .build()

            var httpCode: Int
            client.newCall(headRequest).execute().use { response ->
                httpCode = response.code
            }

            // Fallback to lightweight GET if server rejects HEAD (405 Method Not Allowed)
            if (httpCode == 405) {
                val getRequest = Request.Builder()
                    .url(url)
                    .get()
                    .header("User-Agent", "Mozilla/5.0 (Android) K2P-Diagnostic")
                    .header("Range", "bytes=0-0")
                    .build()
                client.newCall(getRequest).execute().use { getResp ->
                    httpCode = getResp.code
                }
            }

            val elapsed = System.currentTimeMillis() - startMs
            AppLogger.d(LogTag.WHITELIST, "[$groupLabel] $host -> HTTP $httpCode (${elapsed} мс)")
            HostProbeResult(
                url = url,
                host = host,
                isWhitelistGroup = isWhitelistGroup,
                isReachable = true,
                httpCode = httpCode,
                latencyMs = elapsed
            )
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startMs
            val errMsg = e.localizedMessage ?: e.javaClass.simpleName
            AppLogger.w(LogTag.WHITELIST, "[$groupLabel] $host -> НЕДОСТУПЕН (${elapsed} мс): $errMsg")
            HostProbeResult(
                url = url,
                host = host,
                isWhitelistGroup = isWhitelistGroup,
                isReachable = false,
                httpCode = null,
                latencyMs = elapsed,
                error = errMsg
            )
        }
    }

    private fun sendDiagnosticNotification(context: Context, verdict: WhitelistVerdict) {
        val text = when (verdict) {
            WhitelistVerdict.WHITELISTS_ACTIVE -> "Ошибка подключения: Белые списки"
            WhitelistVerdict.NO_NETWORK -> "Ошибка подключения: нет интернета"
            else -> return
        }

        ensureNotificationChannel(context)

        val appIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            DIAGNOSTICS_NOTIFICATION_ID,
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, DIAGNOSTICS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_k2p_monochrome)
            .setContentTitle("Как 2 пальца • Диагностика")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(DIAGNOSTICS_NOTIFICATION_ID, notification)
        AppLogger.i(LogTag.WHITELIST, "Отправлено уведомление: $text")
    }

    fun ensureNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            DIAGNOSTICS_CHANNEL_ID,
            "Диагностика подключения",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Уведомления о проблемах с интернетом и белыми списками"
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.createNotificationChannel(channel)
    }
}
