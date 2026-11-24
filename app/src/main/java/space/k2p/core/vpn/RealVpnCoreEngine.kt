package space.k2p.core.vpn

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class RealVpnCoreEngine(private val context: Context) : VpnCoreEngine {

    companion object {
        val internalState = MutableStateFlow(VpnState.DISCONNECTED)
        val internalStats = MutableStateFlow(VpnTrafficStats())
        var currentServerName: String = ""
    }

    override val state: StateFlow<VpnState> = internalState.asStateFlow()
    override val stats: StateFlow<VpnTrafficStats> = internalStats.asStateFlow()

    override suspend fun start(configJson: String) {
        internalState.value = VpnState.CONNECTING
        val intent = Intent(context, K2pVpnService::class.java).apply {
            action = K2pVpnService.ACTION_CONNECT
            putExtra(K2pVpnService.EXTRA_CONFIG_JSON, configJson)
            putExtra(K2pVpnService.EXTRA_SERVER_NAME, currentServerName)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    override suspend fun stop() {
        internalState.value = VpnState.STOPPING
        val intent = Intent(context, K2pVpnService::class.java).apply {
            action = K2pVpnService.ACTION_DISCONNECT
        }
        context.startService(intent)
    }

    override suspend fun measurePing(serverAddress: String, port: Int): Long? = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(serverAddress, port), 3000)
            }
            System.currentTimeMillis() - start
        } catch (_: Exception) {
            null
        }
    }
}
