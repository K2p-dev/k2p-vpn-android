package space.k2p.core.geodata

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import space.k2p.K2pApp
import space.k2p.core.vpn.K2pVpnService
import space.k2p.core.vpn.RealVpnCoreEngine
import space.k2p.core.vpn.VpnServerNode
import space.k2p.core.vpn.VpnState
import space.k2p.core.vpn.XrayConfigBuilder
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GeodataManager {

    private const val MIN_FILE_SIZE = 50 * 1024L // 50 KB

    private const val GEOIP_JSDELIVR = "https://cdn.jsdelivr.net/gh/runetfreedom/russia-v2ray-rules-dat@release/geoip.dat"
    private const val GEOSITE_JSDELIVR = "https://cdn.jsdelivr.net/gh/runetfreedom/russia-v2ray-rules-dat@release/geosite.dat"

    private const val GEOIP_GITHUB = "https://github.com/runetfreedom/russia-v2ray-rules-dat/releases/latest/download/geoip.dat"
    private const val GEOSITE_GITHUB = "https://github.com/runetfreedom/russia-v2ray-rules-dat/releases/latest/download/geosite.dat"

    fun initGeodata(context: Context) {
        val filesDir = context.filesDir
        val geoipFile = File(filesDir, "geoip.dat")
        val geositeFile = File(filesDir, "geosite.dat")

        if (!geoipFile.exists() || geoipFile.length() < MIN_FILE_SIZE) {
            copyAssetToFile(context, "geoip.dat", geoipFile)
        }

        if (!geositeFile.exists() || geositeFile.length() < MIN_FILE_SIZE) {
            copyAssetToFile(context, "geosite.dat", geositeFile)
        }
    }

    private fun copyAssetToFile(context: Context, assetName: String, destination: File) {
        try {
            context.assets.open(assetName).use { input ->
                val tmpFile = File(destination.parentFile, "$assetName.asset.tmp")
                FileOutputStream(tmpFile).use { output ->
                    input.copyTo(output)
                }
                if (tmpFile.length() >= MIN_FILE_SIZE) {
                    if (destination.exists()) destination.delete()
                    tmpFile.renameTo(destination)
                } else {
                    tmpFile.delete()
                }
            }
        } catch (_: Exception) {
        }
    }

    suspend fun updateGeodataOta(
        context: Context,
        okHttpClient: OkHttpClient = K2pApp.instance.networkModule.okHttpClient
    ): Result<String> = withContext(Dispatchers.IO) {
        val filesDir = context.filesDir
        val geoipTmp = File(filesDir, "geoip.dat.tmp")
        val geositeTmp = File(filesDir, "geosite.dat.tmp")

        val geoipSuccess = downloadWithFallback(
            client = okHttpClient,
            primaryUrl = GEOIP_JSDELIVR,
            fallbackUrl = GEOIP_GITHUB,
            destination = geoipTmp
        )

        if (!geoipSuccess) {
            geoipTmp.delete()
            return@withContext Result.failure(Exception("Не удалось скачать geoip.dat"))
        }

        val geositeSuccess = downloadWithFallback(
            client = okHttpClient,
            primaryUrl = GEOSITE_JSDELIVR,
            fallbackUrl = GEOSITE_GITHUB,
            destination = geositeTmp
        )

        if (!geositeSuccess) {
            geoipTmp.delete()
            geositeTmp.delete()
            return@withContext Result.failure(Exception("Не удалось скачать geosite.dat"))
        }

        // Both files are downloaded and verified > 50 KB, atomically replace
        val geoipFinal = File(filesDir, "geoip.dat")
        val geositeFinal = File(filesDir, "geosite.dat")

        if (geoipFinal.exists()) geoipFinal.delete()
        if (geositeFinal.exists()) geositeFinal.delete()

        geoipTmp.renameTo(geoipFinal)
        geositeTmp.renameTo(geositeFinal)

        val updatedDateStr = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date())
        K2pApp.instance.geodataPreferences.setLastUpdated(updatedDateStr)

        // If VPN is currently connected, restart it with fresh database
        if (RealVpnCoreEngine.internalState.value == VpnState.CONNECTED) {
            restartVpn(context)
        }

        Result.success("База геоданных обновлена: $updatedDateStr")
    }

    private fun downloadWithFallback(
        client: OkHttpClient,
        primaryUrl: String,
        fallbackUrl: String,
        destination: File
    ): Boolean {
        val urls = listOf(primaryUrl, fallbackUrl)
        for (url in urls) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "K2P-VPN-Android")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful && response.body != null) {
                        FileOutputStream(destination).use { out ->
                            response.body!!.byteStream().copyTo(out)
                        }
                        if (destination.length() >= MIN_FILE_SIZE) {
                            return true
                        } else {
                            destination.delete()
                        }
                    }
                }
            } catch (_: Exception) {
                destination.delete()
            }
        }
        return false
    }

    fun restartVpn(context: Context) {
        try {
            val prefs = K2pApp.instance.subscriptionPreferences
            val json = Json { ignoreUnknownKeys = true }
            val nodesJson = runBlocking { prefs.nodesJsonFlow.first() }
            val selectedId = runBlocking { prefs.selectedNodeIdFlow.first() }

            val nodes = if (!nodesJson.isNullOrBlank()) {
                runCatching { json.decodeFromString<List<VpnServerNode>>(nodesJson) }.getOrDefault(emptyList())
            } else emptyList()

            val targetNode = nodes.firstOrNull { it.id == selectedId } ?: nodes.firstOrNull() ?: return
            RealVpnCoreEngine.currentServerName = targetNode.name
            val configJson = XrayConfigBuilder.build(targetNode)
            val intent = Intent(context, K2pVpnService::class.java).apply {
                action = K2pVpnService.ACTION_CONNECT
                putExtra(K2pVpnService.EXTRA_CONFIG_JSON, configJson)
                putExtra(K2pVpnService.EXTRA_SERVER_NAME, targetNode.name)
            }
            ContextCompat.startForegroundService(context, intent)
        } catch (_: Exception) {
        }
    }
}
