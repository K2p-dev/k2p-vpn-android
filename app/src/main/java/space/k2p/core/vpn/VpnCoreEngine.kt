package space.k2p.core.vpn

import kotlinx.coroutines.flow.StateFlow

enum class VpnState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    STOPPING
}

data class VpnTrafficStats(
    val uploadSpeedBps: Long = 0L,
    val downloadSpeedBps: Long = 0L,
    val totalBytesUploaded: Long = 0L,
    val totalBytesDownloaded: Long = 0L
)

interface VpnCoreEngine {
    val state: StateFlow<VpnState>
    val stats: StateFlow<VpnTrafficStats>

    suspend fun start(configJson: String)
    suspend fun stop()
    suspend fun measurePing(serverAddress: String, port: Int): Long?
}
