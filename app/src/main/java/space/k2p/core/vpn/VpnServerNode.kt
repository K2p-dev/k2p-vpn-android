package space.k2p.core.vpn

import kotlinx.serialization.Serializable

@Serializable
data class VpnServerNode(
    val id: String,
    val name: String,
    val flagEmoji: String,
    val countryName: String,
    val protocolLabel: String,
    val isLte: Boolean = false,
    val rawUri: String,
    val pingMs: Long? = null,
    // Protocol specifics
    val protocol: String, // "vless" or "hysteria" / "hysteria2"
    val host: String,
    val port: Int,
    val uuidOrPassword: String,
    // VLESS parameters
    val flow: String? = null,
    val sni: String? = null,
    val pbk: String? = null,
    val sid: String? = null,
    val spx: String? = null,
    val path: String? = null,
    val hostHeader: String? = null,
    val xhttpMode: String? = null,
    val xhttpExtra: String? = null,
    val fp: String? = null,
    // Hysteria parameters
    val insecure: Boolean = false,
    val obfs: String? = null,
    val obfsPassword: String? = null
)
