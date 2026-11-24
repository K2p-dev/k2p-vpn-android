package space.k2p.core.vpn

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MockVpnCoreEngine : VpnCoreEngine {
    private val _state = MutableStateFlow(VpnState.DISCONNECTED)
    override val state: StateFlow<VpnState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(VpnTrafficStats())
    override val stats: StateFlow<VpnTrafficStats> = _stats.asStateFlow()

    override suspend fun start(configJson: String) {
        _state.value = VpnState.CONNECTING
        delay(600)
        _state.value = VpnState.CONNECTED
    }

    override suspend fun stop() {
        _state.value = VpnState.STOPPING
        delay(300)
        _state.value = VpnState.DISCONNECTED
    }

    override suspend fun measurePing(serverAddress: String, port: Int): Long? {
        delay(200)
        return 45L
    }
}
