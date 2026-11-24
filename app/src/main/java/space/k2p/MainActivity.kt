package space.k2p

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import space.k2p.core.events.AuthEvent
import space.k2p.core.events.AuthEventBus
import space.k2p.core.preferences.ThemeConfig
import space.k2p.core.preferences.ThemeMode
import space.k2p.core.preferences.ThemePreferences
import space.k2p.core.vpn.K2pVpnService
import space.k2p.core.vpn.RealVpnCoreEngine
import space.k2p.core.vpn.VpnServerNode
import space.k2p.core.vpn.XrayConfigBuilder
import space.k2p.ui.navigation.K2pNavHost
import space.k2p.ui.navigation.Screen
import space.k2p.ui.theme.K2pVpnTheme

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_QUICK_CONNECT = "space.k2p.action.QUICK_CONNECT"
    }

    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            connectToLastServer()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val themePreferences = ThemePreferences(applicationContext)
        val tokenStorage = K2pApp.instance.tokenStorage

        // Check if user is already logged in on app startup
        val initialToken = runBlocking { tokenStorage.tokenFlow.firstOrNull() }
        val startDestination = if (!initialToken.isNullOrBlank()) {
            space.k2p.core.work.SubscriptionWorkManager.schedulePeriodicWork(applicationContext)
            Screen.Main.route
        } else {
            Screen.Auth.route
        }

        handleQuickConnectIntent(intent)

        setContent {
            val themeConfig by themePreferences.themeConfigFlow
                .collectAsState(initial = ThemeConfig())
            val navController = rememberNavController()

            val isDark = when (themeConfig.themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
            }

            DisposableEffect(isDark) {
                WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = !isDark
                onDispose { }
            }

            LaunchedEffect(Unit) {
                AuthEventBus.events.collect { event ->
                    when (event) {
                        is AuthEvent.DeviceRevoked -> {
                            Toast.makeText(applicationContext, event.message, Toast.LENGTH_LONG).show()
                            navController.navigate(Screen.Auth.route) {
                                popUpTo(Screen.Main.route) { inclusive = true }
                            }
                        }
                        is AuthEvent.LoggedOut -> {
                            navController.navigate(Screen.Auth.route) {
                                popUpTo(Screen.Main.route) { inclusive = true }
                            }
                        }
                    }
                }
            }

            K2pVpnTheme(themeConfig = themeConfig) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    K2pNavHost(
                        navController = navController,
                        startDestination = startDestination
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch(Dispatchers.IO) {
            val token = K2pApp.instance.tokenStorage.tokenFlow.firstOrNull()
            if (!token.isNullOrBlank()) {
                K2pApp.instance.subscriptionRepository.refreshSubscription(force = false)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleQuickConnectIntent(intent)
    }

    private fun handleQuickConnectIntent(intent: Intent?) {
        if (intent?.action == ACTION_QUICK_CONNECT) {
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) {
                vpnPrepareLauncher.launch(prepareIntent)
            } else {
                connectToLastServer()
            }
        }
    }

    private fun connectToLastServer() {
        lifecycleScope.launch(Dispatchers.IO) {
            val prefs = K2pApp.instance.subscriptionPreferences
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
                RealVpnCoreEngine.currentServerName = targetNode.name
                val configJson = XrayConfigBuilder.build(targetNode)
                val intent = Intent(this@MainActivity, K2pVpnService::class.java).apply {
                    action = K2pVpnService.ACTION_CONNECT
                    putExtra(K2pVpnService.EXTRA_CONFIG_JSON, configJson)
                    putExtra(K2pVpnService.EXTRA_SERVER_NAME, targetNode.name)
                }
                ContextCompat.startForegroundService(this@MainActivity, intent)
            }
        }
    }
}
