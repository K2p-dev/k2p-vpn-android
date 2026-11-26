package space.k2p.core.vpn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object XrayConfigBuilder {

    fun build(
        node: VpnServerNode,
        context: Context? = null,
        bypassRussia: Boolean? = null
    ): String {
        val root = JSONObject()

        val k2pApp = (context?.applicationContext as? space.k2p.K2pApp) ?: runCatching { space.k2p.K2pApp.instance }.getOrNull()
        val shouldBypassPref = bypassRussia ?: k2pApp?.geodataPreferences?.isBypassRussiaCached ?: true

        val hasGeodata = k2pApp?.let { app ->
            val filesDir = app.filesDir
            val geoip = File(filesDir, "geoip.dat")
            val geosite = File(filesDir, "geosite.dat")
            geoip.exists() && geoip.length() >= 50 * 1024L &&
                geosite.exists() && geosite.length() >= 50 * 1024L
        } ?: false

        val shouldBypass = shouldBypassPref && hasGeodata

        // 1. Log
        val xrayLogFile = k2pApp?.let { File(it.filesDir, "xray_error.log").absolutePath }
        val log = JSONObject().apply {
            put("loglevel", "warning")
            if (!xrayLogFile.isNullOrBlank()) {
                put("error", xrayLogFile)
            }
        }
        root.put("log", log)

        // 2. DNS
        val dns = JSONObject().apply {
            put("servers", JSONArray(listOf("1.1.1.1", "8.8.8.8")))
        }
        root.put("dns", dns)

        // 3. Inbounds (tun)
        val inbounds = JSONArray()
        val tunInbound = JSONObject().apply {
            put("tag", "tun-in")
            put("protocol", "tun")
            put("settings", JSONObject().apply {
                put("name", "xray0")
                put("MTU", 1500)
            })
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray(listOf("http", "tls", "quic")))
                put("routeOnly", true)
            })
        }
        inbounds.put(tunInbound)
        root.put("inbounds", inbounds)

        // 4. Outbounds
        val outbounds = JSONArray()

        // Proxy outbound
        val proxyOutbound = if (node.protocol.equals("hysteria", ignoreCase = true) ||
            node.protocol.equals("hysteria2", ignoreCase = true) ||
            node.protocol.equals("hy2", ignoreCase = true)
        ) {
            buildHysteria2Outbound(node)
        } else {
            buildVlessOutbound(node)
        }
        outbounds.put(proxyOutbound)

        // Direct outbound
        val directOutbound = JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
        }
        outbounds.put(directOutbound)

        // Block outbound
        val blockOutbound = JSONObject().apply {
            put("tag", "block")
            put("protocol", "blackhole")
        }
        outbounds.put(blockOutbound)

        root.put("outbounds", outbounds)

        // 5. Routing
        val routing = JSONObject().apply {
            put("domainStrategy", "IPIfNonMatch")
            val rules = JSONArray()

            if (shouldBypass) {
                // Direct for Russian & private IPs
                val ruIpRule = JSONObject().apply {
                    put("type", "field")
                    put("ip", JSONArray(listOf("geoip:private", "geoip:ru")))
                    put("outboundTag", "direct")
                }
                // Direct for Russian domains (.ru, .su, .рф, banks, Gosuslugi)
                val ruDomainRule = JSONObject().apply {
                    put("type", "field")
                    put("domain", JSONArray(listOf(
                        "geosite:ru-available-only-inside",
                        "domain:ru",
                        "domain:su",
                        "domain:xn--p1ai"
                    )))
                    put("outboundTag", "direct")
                }
                rules.put(ruIpRule)
                rules.put(ruDomainRule)
            } else {
                val localIpRule = JSONObject().apply {
                    put("type", "field")
                    put("ip", JSONArray(listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8")))
                    put("outboundTag", "direct")
                }
                rules.put(localIpRule)
            }

            put("rules", rules)
        }
        root.put("routing", routing)

        // 6. Stats & Policy (mandatory for Xray traffic counting)
        root.put("stats", JSONObject())

        val policy = JSONObject().apply {
            val levels = JSONObject().apply {
                val level0 = JSONObject().apply {
                    put("statsUserUplink", true)
                    put("statsUserDownlink", true)
                }
                put("0", level0)
            }
            put("levels", levels)

            val system = JSONObject().apply {
                put("statsOutboundUplink", true)
                put("statsOutboundDownlink", true)
            }
            put("system", system)
        }
        root.put("policy", policy)

        return root.toString(2)
    }

    /**
     * Builds an Xray TUN config whose "proxy" outbound points to a local SOCKS5 upstream
     * (e.g. isolated OpenFlux child process on 127.0.0.1:<port>).
     */
    fun buildForSocksUpstream(
        socksHost: String = "127.0.0.1",
        socksPort: Int,
        context: Context? = null,
        bypassRussia: Boolean? = null
    ): String {
        val root = JSONObject()

        val k2pApp = (context?.applicationContext as? space.k2p.K2pApp) ?: runCatching { space.k2p.K2pApp.instance }.getOrNull()
        val shouldBypassPref = bypassRussia ?: k2pApp?.geodataPreferences?.isBypassRussiaCached ?: true

        val hasGeodata = k2pApp?.let { app ->
            val filesDir = app.filesDir
            val geoip = File(filesDir, "geoip.dat")
            val geosite = File(filesDir, "geosite.dat")
            geoip.exists() && geoip.length() >= 50 * 1024L &&
                geosite.exists() && geosite.length() >= 50 * 1024L
        } ?: false

        val shouldBypass = shouldBypassPref && hasGeodata

        // 1. Log
        val xrayLogFile = k2pApp?.let { File(it.filesDir, "xray_error.log").absolutePath }
        val log = JSONObject().apply {
            put("loglevel", "warning")
            if (!xrayLogFile.isNullOrBlank()) {
                put("error", xrayLogFile)
            }
        }
        root.put("log", log)

        // 2. DNS
        val dns = JSONObject().apply {
            put("servers", JSONArray(listOf("1.1.1.1", "8.8.8.8")))
        }
        root.put("dns", dns)

        // 3. Inbounds (tun)
        val inbounds = JSONArray()
        val tunInbound = JSONObject().apply {
            put("tag", "tun-in")
            put("protocol", "tun")
            put("settings", JSONObject().apply {
                put("name", "xray0")
                put("MTU", 1500)
            })
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray(listOf("http", "tls", "quic")))
                put("routeOnly", true)
            })
        }
        inbounds.put(tunInbound)
        root.put("inbounds", inbounds)

        // 4. Outbounds: "proxy" switched to local OpenFlux SOCKS5
        val outbounds = JSONArray()
        val socksOutbound = JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "socks")
            put("settings", JSONObject().apply {
                put("servers", JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", socksHost)
                        put("port", socksPort)
                    })
                })
            })
        }
        outbounds.put(socksOutbound)

        val directOutbound = JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
        }
        outbounds.put(directOutbound)

        val blockOutbound = JSONObject().apply {
            put("tag", "block")
            put("protocol", "blackhole")
        }
        outbounds.put(blockOutbound)

        root.put("outbounds", outbounds)

        // 5. Routing
        val routing = JSONObject().apply {
            put("domainStrategy", "IPIfNonMatch")
            val rules = JSONArray()
            if (shouldBypass) {
                rules.put(JSONObject().apply {
                    put("type", "field")
                    put("ip", JSONArray(listOf("geoip:private", "geoip:ru")))
                    put("outboundTag", "direct")
                })
                rules.put(JSONObject().apply {
                    put("type", "field")
                    put("domain", JSONArray(listOf(
                        "geosite:ru-available-only-inside",
                        "domain:ru",
                        "domain:su",
                        "domain:xn--p1ai"
                    )))
                    put("outboundTag", "direct")
                })
            } else {
                rules.put(JSONObject().apply {
                    put("type", "field")
                    put("ip", JSONArray(listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8")))
                    put("outboundTag", "direct")
                })
            }
            put("rules", rules)
        }
        root.put("routing", routing)

        // 6. Stats & Policy
        root.put("stats", JSONObject())
        val policy = JSONObject().apply {
            put("levels", JSONObject().apply {
                put("0", JSONObject().apply {
                    put("statsUserUplink", true)
                    put("statsUserDownlink", true)
                })
            })
            put("system", JSONObject().apply {
                put("statsOutboundUplink", true)
                put("statsOutboundDownlink", true)
            })
        }
        root.put("policy", policy)

        return root.toString(2)
    }

    fun buildForPing(node: VpnServerNode): String {
        val root = JSONObject()
        root.put("log", JSONObject().apply { put("loglevel", "none") })
        root.put("dns", JSONObject().apply { put("servers", JSONArray(listOf("1.1.1.1", "8.8.8.8"))) })

        val outbounds = JSONArray()
        val proxy = if (node.protocol.equals("hysteria", ignoreCase = true) ||
            node.protocol.equals("hysteria2", ignoreCase = true) ||
            node.protocol.equals("hy2", ignoreCase = true)
        ) {
            buildHysteria2Outbound(node)
        } else {
            buildVlessOutbound(node)
        }
        outbounds.put(proxy)

        val direct = JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
        }
        outbounds.put(direct)

        root.put("outbounds", outbounds)
        return root.toString()
    }

    private fun buildVlessOutbound(node: VpnServerNode): JSONObject {
        val outbound = JSONObject()
        outbound.put("tag", "proxy")
        outbound.put("protocol", "vless")

        // settings
        val settings = JSONObject()
        val vnext = JSONArray()
        val serverObj = JSONObject().apply {
            put("address", node.host)
            put("port", node.port)

            val users = JSONArray()
            val user = JSONObject().apply {
                put("id", node.uuidOrPassword)
                put("encryption", "none")
                if (!node.flow.isNullOrBlank()) {
                    put("flow", node.flow)
                }
            }
            users.put(user)
            put("users", users)
        }
        vnext.put(serverObj)
        settings.put("vnext", vnext)
        outbound.put("settings", settings)

        // streamSettings
        val streamSettings = JSONObject()
        streamSettings.put("network", "xhttp")

        // xhttpSettings
        val xhttpSettings = JSONObject()
        val rawPath = node.path?.takeIf { it.isNotBlank() } ?: "/"
        val decodedPath = try {
            URLDecoder.decode(rawPath, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            rawPath
        }
        xhttpSettings.put("path", decodedPath)

        if (!node.hostHeader.isNullOrBlank()) {
            xhttpSettings.put("host", node.hostHeader)
        }
        if (!node.xhttpMode.isNullOrBlank()) {
            xhttpSettings.put("mode", node.xhttpMode)
        }
        if (!node.xhttpExtra.isNullOrBlank()) {
            try {
                val decodedExtra = URLDecoder.decode(node.xhttpExtra, StandardCharsets.UTF_8.name())
                val extraJson = JSONObject(decodedExtra)
                xhttpSettings.put("extra", extraJson)
            } catch (_: Exception) {
                // If not valid JSON, ignore or fallback
            }
        }
        streamSettings.put("xhttpSettings", xhttpSettings)

        // reality
        streamSettings.put("security", "reality")
        val realitySettings = JSONObject().apply {
            put("serverName", node.sni ?: node.host)
            put("fingerprint", node.fp?.takeIf { it.isNotBlank() } ?: "chrome")
            put("publicKey", node.pbk ?: "")
            put("shortId", node.sid ?: "")

            val rawSpx = node.spx?.takeIf { it.isNotBlank() } ?: "/"
            val decodedSpx = try {
                URLDecoder.decode(rawSpx, StandardCharsets.UTF_8.name())
            } catch (_: Exception) {
                rawSpx
            }
            put("spiderX", decodedSpx)
        }
        streamSettings.put("realitySettings", realitySettings)

        outbound.put("streamSettings", streamSettings)
        return outbound
    }

    private fun buildHysteria2Outbound(node: VpnServerNode): JSONObject {
        val outbound = JSONObject()
        outbound.put("tag", "proxy")
        outbound.put("protocol", "hysteria")

        // settings: version 2, address, port (auth is NOT here)
        val settings = JSONObject().apply {
            put("version", 2)
            put("address", node.host)
            put("port", node.port)
        }
        outbound.put("settings", settings)

        // streamSettings
        val streamSettings = JSONObject()
        streamSettings.put("network", "hysteria")

        // hysteriaSettings: version 2, auth (strictly inside streamSettings.hysteriaSettings)
        val hysteriaSettings = JSONObject().apply {
            put("version", 2)
            put("auth", node.uuidOrPassword)
        }
        streamSettings.put("hysteriaSettings", hysteriaSettings)

        // security: tls
        streamSettings.put("security", "tls")
        val tlsSettings = JSONObject().apply {
            put("serverName", node.sni ?: node.host)
            put("allowInsecure", node.insecure)
        }
        streamSettings.put("tlsSettings", tlsSettings)

        // finalmask for obfs salamander
        if (node.obfs.equals("salamander", ignoreCase = true) && !node.obfsPassword.isNullOrBlank()) {
            val finalmask = JSONObject().apply {
                val udpArray = JSONArray()
                val salamanderObj = JSONObject().apply {
                    put("type", "salamander")
                    put("settings", JSONObject().apply {
                        put("password", node.obfsPassword)
                    })
                }
                udpArray.put(salamanderObj)
                put("udp", udpArray)
            }
            streamSettings.put("finalmask", finalmask)
        }

        outbound.put("streamSettings", streamSettings)
        return outbound
    }
}
