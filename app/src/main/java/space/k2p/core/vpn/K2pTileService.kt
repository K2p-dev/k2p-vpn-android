package space.k2p.core.vpn

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import space.k2p.MainActivity
import space.k2p.R
import space.k2p.core.preferences.SubscriptionPreferences

class K2pTileService : TileService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_k2p_monochrome)
        when (RealVpnCoreEngine.internalState.value) {
            VpnState.CONNECTED -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "Как 2 пальца"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = RealVpnCoreEngine.currentServerName.ifBlank { "Подключен" }
                }
            }
            VpnState.CONNECTING -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "Как 2 пальца"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Подключение..."
                }
            }
            VpnState.STOPPING -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "Как 2 пальца"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Отключение..."
                }
            }
            VpnState.DISCONNECTED -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "Как 2 пальца"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Отключен"
                }
            }
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()

        // 1. Check VPN permission
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            val appIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    appIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(appIntent)
            }
            return
        }

        // 2. Toggle VPN state
        when (RealVpnCoreEngine.internalState.value) {
            VpnState.CONNECTED, VpnState.CONNECTING -> {
                val intent = Intent(this, K2pVpnService::class.java).apply {
                    action = K2pVpnService.ACTION_DISCONNECT
                }
                startService(intent)
                updateTileState()
            }
            VpnState.DISCONNECTED, VpnState.STOPPING -> {
                serviceScope.launch {
                    val prefs = SubscriptionPreferences(applicationContext)
                    val json = Json { ignoreUnknownKeys = true }
                    val nodesJson = prefs.nodesJsonFlow.first()
                    val selectedId = prefs.selectedNodeIdFlow.first()

                    val nodes = if (!nodesJson.isNullOrBlank()) {
                        runCatching {
                            json.decodeFromString<List<VpnServerNode>>(nodesJson)
                        }.getOrDefault(emptyList())
                    } else emptyList()

                    val targetNode = nodes.firstOrNull { it.id == selectedId } ?: nodes.firstOrNull()

                    if (targetNode != null) {
                        val configJson = XrayConfigBuilder.build(targetNode)
                        val intent = Intent(this@K2pTileService, K2pVpnService::class.java).apply {
                            action = K2pVpnService.ACTION_CONNECT
                            putExtra(K2pVpnService.EXTRA_CONFIG_JSON, configJson)
                            putExtra(K2pVpnService.EXTRA_SERVER_NAME, targetNode.name)
                        }
                        ContextCompat.startForegroundService(this@K2pTileService, intent)
                    } else {
                        // Open app to configure subscription
                        val appIntent = Intent(this@K2pTileService, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        startActivity(appIntent)
                    }
                    updateTileState()
                }
            }
        }
    }
}
