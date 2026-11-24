package space.k2p.data.repository

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import libv2ray.Libv2ray
import okhttp3.OkHttpClient
import okhttp3.Request
import space.k2p.core.config.AppConfig
import space.k2p.core.preferences.SubscriptionPreferences
import space.k2p.core.vpn.SubscriptionParser
import space.k2p.core.vpn.VpnServerNode
import space.k2p.core.vpn.XrayConfigBuilder
import space.k2p.data.api.ClientApiService
import java.util.concurrent.TimeUnit

class SubscriptionRepository(
    private val context: Context,
    private val clientApiService: ClientApiService,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val subscriptionPreferences: SubscriptionPreferences,
    private val externalScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val _nodes = MutableStateFlow<List<VpnServerNode>>(emptyList())
    val nodes: StateFlow<List<VpnServerNode>> = _nodes.asStateFlow()

    private val _selectedNode = MutableStateFlow<VpnServerNode?>(null)
    val selectedNode: StateFlow<VpnServerNode?> = _selectedNode.asStateFlow()

    private val _trafficInfo = MutableStateFlow("30.1 GB / ∞")
    val trafficInfo: StateFlow<String> = _trafficInfo.asStateFlow()

    private val _announceSlogan = MutableStateFlow(AppConfig.APP_SLOGAN)
    val announceSlogan: StateFlow<String> = _announceSlogan.asStateFlow()

    private val _isSubscriptionActive = MutableStateFlow(true)
    val isSubscriptionActive: StateFlow<Boolean> = _isSubscriptionActive.asStateFlow()

    private val _expiryDays = MutableStateFlow(0)
    val expiryDays: StateFlow<Int> = _expiryDays.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        // Load initial state from DataStore
        externalScope.launch {
            loadCachedData()
        }
    }

    private suspend fun loadCachedData() {
        val cachedJson = subscriptionPreferences.nodesJsonFlow.first()
        val selectedId = subscriptionPreferences.selectedNodeIdFlow.first()
        val cachedTraffic = subscriptionPreferences.trafficInfoFlow.first()
        val cachedSlogan = subscriptionPreferences.announceSloganFlow.first()
        val cachedStatus = subscriptionPreferences.subStatusFlow.first()
        val cachedDays = subscriptionPreferences.subExpiryDaysFlow.first()

        _trafficInfo.value = cachedTraffic
        _announceSlogan.value = cachedSlogan
        _expiryDays.value = cachedDays
        _isSubscriptionActive.value = cachedStatus == "premium" || cachedStatus == "active"

        if (!cachedJson.isNullOrBlank()) {
            val list = runCatching {
                json.decodeFromString<List<VpnServerNode>>(cachedJson)
            }.getOrDefault(emptyList())

            if (list.isNotEmpty()) {
                _nodes.value = list
                val found = list.firstOrNull { it.id == selectedId } ?: list.first()
                _selectedNode.value = found
            }
        }
    }

    suspend fun selectNode(node: VpnServerNode) {
        _selectedNode.value = node
        subscriptionPreferences.setSelectedNodeId(node.id)
    }

    suspend fun refreshSubscription(force: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        if (_isRefreshing.value) return@withContext Result.success(Unit)

        val lastUpdated = subscriptionPreferences.lastUpdatedEpochMsFlow.first()
        val updateIntervalHours = subscriptionPreferences.updateIntervalHoursFlow.first()
        val intervalMs = TimeUnit.HOURS.toMillis(updateIntervalHours)
        val now = System.currentTimeMillis()

        if (!force && _nodes.value.isNotEmpty() && (now - lastUpdated < intervalMs)) {
            return@withContext Result.success(Unit)
        }

        _isRefreshing.value = true
        try {
            // 1. Fetch config from backend
            val configResponse = clientApiService.getConfig()
            if (!configResponse.isSuccessful) {
                if (configResponse.code() == 403) {
                    _isSubscriptionActive.value = false
                    subscriptionPreferences.saveSubscriptionData(
                        nodesJson = "[]",
                        trafficInfo = "0 GB / 0 GB",
                        announceSlogan = "Подписка не активна",
                        status = "expired",
                        expiryDays = 0,
                        expiryDate = ""
                    )
                    _nodes.value = emptyList()
                    _selectedNode.value = null
                    return@withContext Result.failure(Exception("NO_SUBSCRIPTION"))
                }
                return@withContext Result.failure(Exception("HTTP ${configResponse.code()}"))
            }

            val configBody = configResponse.body()
            val subLink = configBody?.subLink
            val status = configBody?.status ?: "premium"
            _isSubscriptionActive.value = status == "premium" || status == "active"

            if (subLink.isNullOrBlank()) {
                return@withContext Result.failure(Exception("Empty sub_link"))
            }

            // 2. Also check profile for expiry days
            val profileResponse = runCatching { clientApiService.getProfile() }.getOrNull()
            val profile = profileResponse?.body()
            val daysLeft = profile?.daysLeft ?: 0
            val formattedExpiry = profile?.expiryTimeMs?.let { ms ->
                java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault()).format(java.util.Date(ms))
            }.orEmpty()
            _expiryDays.value = daysLeft

            // 3. Download sub_link with OkHttp
            val request = Request.Builder()
                .url(subLink)
                .header("User-Agent", "K2P-VPN/1.0.0 (Android)")
                .build()

            val callResponse = okHttpClient.newCall(request).execute()
            if (!callResponse.isSuccessful) {
                return@withContext Result.failure(Exception("Sub link download failed: ${callResponse.code}"))
            }

            // 4. Parse headers
            val meta = SubscriptionParser.parseHeaders { key -> callResponse.header(key) }
            _trafficInfo.value = meta.trafficInfo
            _announceSlogan.value = meta.announceSlogan

            // 5. Parse nodes body
            val rawBody = callResponse.body?.string().orEmpty()
            val parsedNodes = SubscriptionParser.parseNodes(rawBody)

            if (parsedNodes.isNotEmpty()) {
                _nodes.value = parsedNodes
                val currentSelectedId = subscriptionPreferences.selectedNodeIdFlow.first()
                val currentSelected = parsedNodes.firstOrNull { it.id == currentSelectedId } ?: parsedNodes.first()
                _selectedNode.value = currentSelected

                val serialized = json.encodeToString(parsedNodes)
                subscriptionPreferences.saveSubscriptionData(
                    nodesJson = serialized,
                    trafficInfo = meta.trafficInfo,
                    announceSlogan = meta.announceSlogan,
                    status = status,
                    expiryDays = daysLeft,
                    expiryDate = formattedExpiry,
                    updateIntervalHours = meta.updateIntervalHours
                )
                subscriptionPreferences.setSelectedNodeId(currentSelected.id)
                space.k2p.core.work.SubscriptionWorkManager.schedulePeriodicWork(context, meta.updateIntervalHours)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _isRefreshing.value = false
        }
    }

    suspend fun measurePing(node: VpnServerNode): Long = withContext(Dispatchers.IO) {
        val delay = try {
            val pingConfig = XrayConfigBuilder.buildForPing(node)
            val d = Libv2ray.measureOutboundDelay(pingConfig, "http://www.gstatic.com/generate_204")
            if (d > 0) d else -1L
        } catch (_: Exception) {
            -1L
        }
        updateNodePing(node.id, delay)
        delay
    }

    suspend fun measureAllPings(): Unit = withContext(Dispatchers.IO) {
        val currentNodes = _nodes.value
        if (currentNodes.isEmpty()) return@withContext

        val semaphore = Semaphore(4)
        coroutineScope {
            currentNodes.map { node ->
                async {
                    semaphore.withPermit {
                        measurePing(node)
                    }
                }
            }.awaitAll()
        }
    }

    private fun updateNodePing(nodeId: String, ping: Long?) {
        val updated = _nodes.value.map {
            if (it.id == nodeId) it.copy(pingMs = ping) else it
        }
        _nodes.value = updated
        if (_selectedNode.value?.id == nodeId) {
            _selectedNode.value = _selectedNode.value?.copy(pingMs = ping)
        }
    }
}
