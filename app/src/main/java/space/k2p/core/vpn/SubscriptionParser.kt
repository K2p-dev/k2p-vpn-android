package space.k2p.core.vpn

import android.net.Uri
import android.util.Base64
import space.k2p.core.config.AppConfig
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

object SubscriptionParser {

    data class SubscriptionMeta(
        val trafficInfo: String,
        val announceSlogan: String,
        val updateIntervalHours: Long = 6L
    )

    fun parseHeaders(getHeader: (String) -> String?): SubscriptionMeta {
        // 1. User info header
        val userInfo = getHeader("subscription-userinfo")
            ?: getHeader("Subscription-Userinfo")
            ?: getHeader("SUBSCRIPTION-USERINFO")

        var trafficInfo = "30.1 GB / ∞"
        if (!userInfo.isNullOrBlank()) {
            var upload = 0L
            var download = 0L
            var total = 0L

            val parts = userInfo.split(";")
            for (part in parts) {
                val kv = part.trim().split("=")
                if (kv.size == 2) {
                    val key = kv[0].trim().lowercase(Locale.ROOT)
                    val value = kv[1].trim().toLongOrNull() ?: 0L
                    when (key) {
                        "upload" -> upload = value
                        "download" -> download = value
                        "total" -> total = value
                    }
                }
            }

            val usedBytes = upload + download
            val usedGb = formatBytesToGb(usedBytes)
            trafficInfo = if (total <= 0L) {
                "$usedGb / ∞"
            } else {
                val totalGb = formatBytesToGb(total)
                "$usedGb / $totalGb"
            }
        }

        // 2. Announce or profile-title
        val rawAnnounce = getHeader("announce")
            ?: getHeader("profile-title")
            ?: getHeader("Profile-Title")
            ?: getHeader("Announce")

        val slogan = if (!rawAnnounce.isNullOrBlank()) {
            decodeAnnounce(rawAnnounce)
        } else {
            AppConfig.APP_SLOGAN
        }

        // 3. Profile update interval header (in hours)
        val rawInterval = getHeader("profile-update-interval")
            ?: getHeader("Profile-Update-Interval")
            ?: getHeader("PROFILE-UPDATE-INTERVAL")
        val updateIntervalHours = rawInterval?.trim()?.toLongOrNull()?.coerceAtLeast(1L) ?: 6L

        return SubscriptionMeta(
            trafficInfo = trafficInfo,
            announceSlogan = slogan,
            updateIntervalHours = updateIntervalHours
        )
    }

    fun parseHeaders(headersMap: Map<String, String>): SubscriptionMeta = parseHeaders { key ->
        headersMap[key]
            ?: headersMap[key.lowercase(Locale.ROOT)]
            ?: headersMap.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
    }

    private fun decodeAnnounce(raw: String): String {
        val trimmed = raw.trim()
        return try {
            if (trimmed.startsWith("base64:", ignoreCase = true)) {
                val b64 = trimmed.substring(7).trim()
                    .replace("\r", "")
                    .replace("\n", "")
                    .replace(" ", "")
                    .replace("\t", "")
                    .replace("-", "+")
                    .replace("_", "/")
                val remainder = b64.length % 4
                val padded = if (remainder != 0) b64 + "=".repeat(4 - remainder) else b64
                val decodedBytes = Base64.decode(padded, Base64.DEFAULT)
                String(decodedBytes, Charsets.UTF_8).trim()
            } else {
                decodeSafeUrl(trimmed)
            }
        } catch (_: Exception) {
            trimmed.ifEmpty { AppConfig.APP_SLOGAN }
        }
    }

    fun parseNodes(body: String): List<VpnServerNode> {
        val decodedText = decodeBase64Safe(body)
        val lines = decodedText.lines().map { it.trim() }.filter { it.isNotBlank() }

        val nodes = mutableListOf<VpnServerNode>()
        for (line in lines) {
            val node = parseUri(line)
            if (node != null) {
                nodes.add(node)
            }
        }
        return nodes
    }

    private fun decodeBase64Safe(input: String): String {
        val trimmed = input.trim()
        // If already plaintext URI
        if (trimmed.startsWith("vless://") || trimmed.startsWith("hysteria2://") || trimmed.startsWith("hy2://")) {
            return trimmed
        }

        return try {
            var clean = trimmed
                .replace("\r", "")
                .replace("\n", "")
                .replace(" ", "")
                .replace("\t", "")
                .replace("-", "+")
                .replace("_", "/")
            val remainder = clean.length % 4
            if (remainder != 0) {
                clean += "=".repeat(4 - remainder)
            }
            val bytes = Base64.decode(clean, Base64.DEFAULT)
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            trimmed
        }
    }

    private fun parseUri(rawUri: String): VpnServerNode? {
        return try {
            val uri = Uri.parse(rawUri)
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null

            when {
                scheme == "vless" -> parseVless(rawUri, uri)
                scheme == "hysteria2" || scheme == "hy2" -> parseHysteria2(rawUri, uri)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseVless(rawUri: String, uri: Uri): VpnServerNode {
        val uuid = uri.userInfo.orEmpty()
        val host = uri.host.orEmpty()
        val port = if (uri.port > 0) uri.port else 443

        val rawFragment = if (rawUri.contains("#")) {
            rawUri.substringAfter("#")
        } else {
            uri.fragment.orEmpty()
        }
        val decodedRawName = decodeSafeUrl(rawFragment).ifBlank { "$host:$port" }

        val flow = uri.getQueryParameter("flow")
        val sni = uri.getQueryParameter("sni")
        val pbk = uri.getQueryParameter("pbk")
        val sid = uri.getQueryParameter("sid")
        val spx = uri.getQueryParameter("spx")
        val path = uri.getQueryParameter("path")
        val hostHeader = uri.getQueryParameter("host")
        val xhttpMode = uri.getQueryParameter("mode")
        val xhttpExtra = uri.getQueryParameter("extra")
        val fp = uri.getQueryParameter("fp")

        val info = processServerNodeName(decodedRawName, host)

        return VpnServerNode(
            id = rawUri.hashCode().toString(),
            name = info.finalName,
            flagEmoji = info.flagEmoji,
            countryName = info.country,
            protocolLabel = "VLESS • XHTTP • REALITY",
            isLte = info.isLte,
            rawUri = rawUri,
            protocol = "vless",
            host = host,
            port = port,
            uuidOrPassword = uuid,
            flow = flow,
            sni = sni,
            pbk = pbk,
            sid = sid,
            spx = spx,
            path = path,
            hostHeader = hostHeader,
            xhttpMode = xhttpMode,
            xhttpExtra = xhttpExtra,
            fp = fp
        )
    }

    private fun parseHysteria2(rawUri: String, uri: Uri): VpnServerNode {
        val password = uri.userInfo.orEmpty()
        val host = uri.host.orEmpty()
        val port = if (uri.port > 0) uri.port else 443

        val rawFragment = if (rawUri.contains("#")) {
            rawUri.substringAfter("#")
        } else {
            uri.fragment.orEmpty()
        }
        val decodedRawName = decodeSafeUrl(rawFragment).ifBlank { "$host:$port" }

        val sni = uri.getQueryParameter("sni")
        val insecureParam = uri.getQueryParameter("insecure")
        val insecure = insecureParam == "1" || insecureParam.equals("true", ignoreCase = true)
        val obfs = uri.getQueryParameter("obfs")
        val obfsPassword = uri.getQueryParameter("obfs-password") ?: uri.getQueryParameter("obfs_password")

        val info = processServerNodeName(decodedRawName, host)

        return VpnServerNode(
            id = rawUri.hashCode().toString(),
            name = info.finalName,
            flagEmoji = info.flagEmoji,
            countryName = info.country,
            protocolLabel = "Hysteria2 • TLS",
            isLte = info.isLte,
            rawUri = rawUri,
            protocol = "hysteria",
            host = host,
            port = port,
            uuidOrPassword = password,
            sni = sni,
            insecure = insecure,
            obfs = obfs,
            obfsPassword = obfsPassword
        )
    }

    // Matches regional indicator flag symbols (e.g. 🇫🇮, 🇺🇸, 🇷🇺)
    private val REGIONAL_FLAG_REGEX = Regex("^[\\uD83C][\\uDDE6-\\uDDFF][\\uD83C][\\uDDE6-\\uDDFF]")

    private data class ProcessedNodeInfo(
        val finalName: String,
        val flagEmoji: String,
        val country: String,
        val isLte: Boolean
    )

    private fun processServerNodeName(rawName: String, host: String): ProcessedNodeInfo {
        var text = rawName.trim()

        // 1. Detect and strip leading flag emoji
        val flagMatch = REGIONAL_FLAG_REGEX.find(text)
        val leadingFlag = flagMatch?.value
        if (leadingFlag != null) {
            text = text.removePrefix(leadingFlag).trim().removePrefix("-").trim()
        }

        // 2. Detect LTE
        val isLte = text.contains("LTE", ignoreCase = true)

        // 3. Strip "- LTE", "– LTE", "— LTE", "LTE"
        if (isLte) {
            text = text
                .replace(Regex("[-–—]?\\s*LTE\\b", RegexOption.IGNORE_CASE), "")
                .trim()
                .removeSuffix("-")
                .removeSuffix("–")
                .removeSuffix("—")
                .trim()
        }

        val (detectedFlag, country) = detectCountry(rawName, host)
        val finalFlag = leadingFlag ?: detectedFlag
        val finalName = if (text.isNotBlank()) text else rawName.trim()

        return ProcessedNodeInfo(
            finalName = finalName,
            flagEmoji = finalFlag,
            country = country,
            isLte = isLte
        )
    }

    private fun detectCountry(name: String, host: String): Pair<String, String> {
        val combined = "$name $host".lowercase(Locale.ROOT)
        return when {
            combined.contains("хельсинки") || combined.contains("helsinki") || combined.contains("финлянд") || combined.contains("finland") || combined.contains(".fi") ->
                Pair("🇫🇮", "Финляндия")
            combined.contains("сша") || combined.contains("us") || combined.contains("usa") || combined.contains("united states") || combined.contains("америк") ->
                Pair("🇺🇸", "США")
            combined.contains("нидерланд") || combined.contains("netherlands") || combined.contains("амстердам") || combined.contains("amsterdam") || combined.contains(".nl") ->
                Pair("🇳🇱", "Нидерланды")
            combined.contains("росси") || combined.contains("russia") || combined.contains("москв") || combined.contains("moscow") || combined.contains("спб") || combined.contains(".ru") ->
                Pair("🇷🇺", "Россия")
            combined.contains("герман") || combined.contains("germany") || combined.contains("франкфурт") || combined.contains("frankfurt") || combined.contains("берлин") || combined.contains("berlin") || combined.contains(".de") ->
                Pair("🇩🇪", "Германия")
            combined.contains("великобритан") || combined.contains("uk") || combined.contains("london") || combined.contains("лондон") || combined.contains(".uk") ->
                Pair("🇬🇧", "Великобритания")
            combined.contains("турци") || combined.contains("turkey") || combined.contains("стамбул") || combined.contains("istanbul") || combined.contains(".tr") ->
                Pair("🇹🇷", "Турция")
            combined.contains("казахстан") || combined.contains("kazakhstan") || combined.contains("алматы") || combined.contains(".kz") ->
                Pair("🇰🇿", "Казахстан")
            combined.contains("швеци") || combined.contains("sweden") || combined.contains("стокгольм") || combined.contains(".se") ->
                Pair("🇸🇪", "Швеция")
            else ->
                Pair("🌐", "Сервер")
        }
    }

    private fun decodeSafeUrl(value: String): String {
        return try {
            val safe = value.replace("+", "%2B")
            URLDecoder.decode(safe, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            value
        }
    }

    private fun formatBytesToGb(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return String.format(Locale.US, "%.1f GB", gb)
    }
}
