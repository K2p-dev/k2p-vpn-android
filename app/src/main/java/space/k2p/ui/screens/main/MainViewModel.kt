package space.k2p.ui.screens.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import space.k2p.K2pApp
import space.k2p.core.config.AppConfig
import space.k2p.core.logging.AppLogger
import space.k2p.core.logging.LogTag
import space.k2p.core.network.WhitelistDetectorService
import space.k2p.core.vpn.RealVpnCoreEngine
import space.k2p.core.vpn.VpnCoreEngine
import space.k2p.core.vpn.VpnServerNode
import space.k2p.core.vpn.VpnState
import space.k2p.core.vpn.VpnTrafficStats
import space.k2p.core.vpn.XrayConfigBuilder
import space.k2p.data.api.model.DeviceItemDto
import space.k2p.data.repository.ClientRepository
import space.k2p.data.repository.SubscriptionRepository

data class MainUiState(
    val vpnState: VpnState = VpnState.DISCONNECTED,
    val trafficStats: VpnTrafficStats = VpnTrafficStats(),
    val sessionDurationSeconds: Long = 0L,
    val selectedNode: VpnServerNode? = null,
    val nodes: List<VpnServerNode> = emptyList(),
    val trafficInfo: String = "30.1 GB / ∞",
    val announceSlogan: String = AppConfig.APP_SLOGAN,
    val isSubscriptionActive: Boolean = true,
    val expiryDays: Int = 0,
    val isRefreshing: Boolean = false,
    val isPinging: Boolean = false,
    val devices: List<DeviceItemDto> = emptyList(),
    val maxDevices: Int = AppConfig.MAX_DEVICES,
    val isDevicesLoading: Boolean = false,
    val errorMessage: String? = null
)

class MainViewModel(
    private val subscriptionRepository: SubscriptionRepository,
    private val clientRepository: ClientRepository,
    private val vpnCoreEngine: VpnCoreEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var timerJob: Job? = null

    init {
        // Collect VPN state
        viewModelScope.launch {
            vpnCoreEngine.state.collect { state ->
                _uiState.update { it.copy(vpnState = state) }
                handleVpnStateChange(state)
            }
        }

        // Collect VPN traffic stats
        viewModelScope.launch {
            vpnCoreEngine.stats.collect { stats ->
                _uiState.update { it.copy(trafficStats = stats) }
            }
        }

        // Collect subscription nodes
        viewModelScope.launch {
            subscriptionRepository.nodes.collect { nodes ->
                _uiState.update { it.copy(nodes = nodes) }
            }
        }

        // Collect selected node
        viewModelScope.launch {
            subscriptionRepository.selectedNode.collect { node ->
                _uiState.update { it.copy(selectedNode = node) }
            }
        }

        // Collect traffic info
        viewModelScope.launch {
            subscriptionRepository.trafficInfo.collect { info ->
                _uiState.update { it.copy(trafficInfo = info) }
            }
        }

        // Collect announce slogan
        viewModelScope.launch {
            subscriptionRepository.announceSlogan.collect { slogan ->
                _uiState.update { it.copy(announceSlogan = slogan) }
            }
        }

        // Collect active status
        viewModelScope.launch {
            subscriptionRepository.isSubscriptionActive.collect { active ->
                _uiState.update { it.copy(isSubscriptionActive = active) }
            }
        }

        // Collect expiry days
        viewModelScope.launch {
            subscriptionRepository.expiryDays.collect { days ->
                _uiState.update { it.copy(expiryDays = days) }
            }
        }

        // Collect refreshing state
        viewModelScope.launch {
            subscriptionRepository.isRefreshing.collect { refreshing ->
                _uiState.update { it.copy(isRefreshing = refreshing) }
            }
        }

        // Auto-refresh subscription if needed (6h)
        viewModelScope.launch {
            subscriptionRepository.refreshSubscription(force = false)
        }

        // Initial devices load
        loadDevices()
    }

    private fun handleVpnStateChange(state: VpnState) {
        if (state == VpnState.CONNECTED) {
            startSessionTimer()
        } else if (state == VpnState.DISCONNECTED) {
            stopSessionTimer()
        }
    }

    private fun startSessionTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                _uiState.update { it.copy(sessionDurationSeconds = it.sessionDurationSeconds + 1) }
            }
        }
    }

    private fun stopSessionTimer() {
        timerJob?.cancel()
        timerJob = null
        _uiState.update { it.copy(sessionDurationSeconds = 0L) }
    }

    fun onConnectClicked(prepareVpn: () -> Unit) {
        val currentState = _uiState.value.vpnState
        if (currentState == VpnState.CONNECTED || currentState == VpnState.CONNECTING) {
            disconnect()
        } else {
            prepareVpn()
        }
    }

    fun startVpn() {
        val node = _uiState.value.selectedNode
        if (node == null) {
            _uiState.update { it.copy(errorMessage = "Нет доступных серверов. Обновите подписку.") }
            return
        }

        viewModelScope.launch {
            try {
                RealVpnCoreEngine.currentServerName = node.name
                val configJson = XrayConfigBuilder.build(node)
                vpnCoreEngine.start(configJson)
            } catch (e: Exception) {
                AppLogger.e(LogTag.VPN, "Ошибка запуска VPN: ${e.message}", e)
                WhitelistDetectorService.triggerAutoCheckOnVpnProblem(
                    K2pApp.instance,
                    "Ошибка запуска VPN: ${e.message}"
                )
                _uiState.update { it.copy(errorMessage = "Ошибка запуска VPN: ${e.message}") }
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            try {
                vpnCoreEngine.stop()
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Ошибка остановки VPN: ${e.message}") }
            }
        }
    }

    fun onNodeSelected(node: VpnServerNode) {
        viewModelScope.launch {
            subscriptionRepository.selectNode(node)
            // If already connected, restart tunnel on new node
            if (_uiState.value.vpnState == VpnState.CONNECTED) {
                RealVpnCoreEngine.currentServerName = node.name
                val configJson = XrayConfigBuilder.build(node)
                vpnCoreEngine.start(configJson)
            }
        }
    }

    fun refreshSubscription() {
        viewModelScope.launch {
            val result = subscriptionRepository.refreshSubscription(force = true)
            if (result.isFailure) {
                _uiState.update { it.copy(errorMessage = "Не удалось обновить подписку") }
            }
            loadDevices()
        }
    }

    fun measurePings() {
        viewModelScope.launch {
            _uiState.update { it.copy(isPinging = true) }
            try {
                subscriptionRepository.measureAllPings()
            } finally {
                _uiState.update { it.copy(isPinging = false) }
            }
        }
    }

    fun selectFastestNode() {
        viewModelScope.launch {
            _uiState.update { it.copy(isPinging = true) }
            try {
                subscriptionRepository.measureAllPings()
                val currentNodes = _uiState.value.nodes
                val fastest = currentNodes.filter { it.pingMs != null && it.pingMs > 0 }
                    .minByOrNull { it.pingMs!! }
                if (fastest != null) {
                    onNodeSelected(fastest)
                } else {
                    _uiState.update { it.copy(errorMessage = "Не удалось определить быстрый сервер (таймаут)") }
                }
            } finally {
                _uiState.update { it.copy(isPinging = false) }
            }
        }
    }

    fun loadDevices() {
        viewModelScope.launch {
            _uiState.update { it.copy(isDevicesLoading = true) }
            val result = clientRepository.getDevices()
            result.onSuccess { devList ->
                _uiState.update { it.copy(devices = devList, isDevicesLoading = false) }
            }.onFailure {
                _uiState.update { it.copy(isDevicesLoading = false) }
            }
        }
    }

    fun unbindDevice(hwid: String) {
        viewModelScope.launch {
            val result = clientRepository.deleteDevice(hwid)
            result.onSuccess {
                loadDevices()
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = err.message ?: "Ошибка удаления устройства") }
            }
        }
    }

    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    class Factory(
        private val subscriptionRepository: SubscriptionRepository,
        private val clientRepository: ClientRepository,
        private val vpnCoreEngine: VpnCoreEngine
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(subscriptionRepository, clientRepository, vpnCoreEngine) as T
        }
    }
}
