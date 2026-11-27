package space.k2p.core.logging

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

enum class LogTag(val label: String) {
    XRAY("XRAY"),
    OPENFLUX("OPENFLUX"),
    VPN("VPN"),
    WHITELIST("WHITELIST"),
    OTA("OTA"),
    API("API"),
    NET("NET"),
    PING("PING")
}

enum class LogLevel(val label: String) {
    DEBUG("D"),
    INFO("I"),
    WARN("W"),
    ERROR("E")
}

enum class LogCategory(val title: String) {
    ALL("Все"),
    XRAY("Xray"),
    OPENFLUX("OpenFlux"),
    WHITELIST("Белые списки"),
    NETWORK_API("Сеть и API")
}

data class LogEntry(
    val id: Long,
    val timestamp: String,
    val tag: LogTag,
    val level: LogLevel,
    val message: String
) {
    fun formatLine(): String = "[$timestamp] [${tag.label}] $message"

    fun matchesCategory(category: LogCategory): Boolean {
        return when (category) {
            LogCategory.ALL -> true
            LogCategory.XRAY -> tag == LogTag.XRAY || tag == LogTag.VPN
            LogCategory.OPENFLUX -> tag == LogTag.OPENFLUX
            LogCategory.WHITELIST -> tag == LogTag.WHITELIST
            LogCategory.NETWORK_API -> tag == LogTag.API || tag == LogTag.NET || tag == LogTag.PING || tag == LogTag.OTA
        }
    }
}

object AppLogger {
    private const val MAX_ENTRIES = 1500
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val idGenerator = AtomicLong(0L)
    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>(MAX_ENTRIES)

    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    fun d(tag: LogTag, message: String) {
        log(tag, LogLevel.DEBUG, message, null)
    }

    fun i(tag: LogTag, message: String) {
        log(tag, LogLevel.INFO, message, null)
    }

    fun w(tag: LogTag, message: String, throwable: Throwable? = null) {
        log(tag, LogLevel.WARN, message, throwable)
    }

    fun e(tag: LogTag, message: String, throwable: Throwable? = null) {
        log(tag, LogLevel.ERROR, message, throwable)
    }

    private val bearerRegex = Regex("(?i)(Bearer\\s+)[A-Za-z0-9._-]+")
    private val uuidRegex = Regex("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b")
    private val secretFieldRegex = Regex("(?i)(\"(?:session_secret|token|jwt|password|publicKey|shortId|auth)\"\\s*:\\s*\")[^\"]+(\")")
    private val kvSecretRegex = Regex("(?i)((?:session_secret|SessionContext|EncryptionKeyFile|doc_url|publicKey|shortId)\\s*[=:]\\s*)\\S+")
    private val querySecretRegex = Regex("(?i)([?&](?:pbk|sid|password|token|session_secret)=)[^&\\s\"']+")
    private val proxyUriUserinfoRegex = Regex("(?i)((?:vless|hysteria2|hy2)://)[^@\\s]+@")
    private val docUrlRegex = Regex("https://(?:[a-zA-Z0-9-]+\\.)*(?:yandex\\.(?:ru|com)|yadi\\.sk|mail\\.ru)/\\S*")

    internal fun redact(input: String): String {
        return input
            .replace(bearerRegex, "$1***")
            .replace(uuidRegex, "***-***-***")
            .replace(secretFieldRegex, "$1***$2")
            .replace(kvSecretRegex, "$1***")
            .replace(querySecretRegex, "$1***")
            .replace(proxyUriUserinfoRegex, "$1***@")
            .replace(docUrlRegex) { match ->
                val url = match.value
                val hostEnd = url.indexOf('/', startIndex = "https://".length)
                if (hostEnd > 0) "${url.substring(0, hostEnd)}/***" else "https://***"
            }
    }

    private fun log(tag: LogTag, level: LogLevel, message: String, throwable: Throwable?) {
        val safeMessage = redact(message)
        val androidTag = "K2P-${tag.label}"
        when (level) {
            LogLevel.DEBUG -> Log.d(androidTag, safeMessage, throwable)
            LogLevel.INFO -> Log.i(androidTag, safeMessage, throwable)
            LogLevel.WARN -> Log.w(androidTag, safeMessage, throwable)
            LogLevel.ERROR -> Log.e(androidTag, safeMessage, throwable)
        }

        val fullMessage = if (throwable != null) {
            val stack = redact(Log.getStackTraceString(throwable).trim())
            if (stack.isNotBlank()) "$safeMessage\n$stack" else "$safeMessage: ${redact(throwable.message.orEmpty())}"
        } else {
            safeMessage
        }

        val timestamp = LocalTime.now().format(timeFormatter)
        val entry = LogEntry(
            id = idGenerator.incrementAndGet(),
            timestamp = timestamp,
            tag = tag,
            level = level,
            message = fullMessage
        )

        val snapshot: List<LogEntry>
        synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) {
                buffer.removeFirst()
            }
            buffer.addLast(entry)
            snapshot = buffer.toList()
        }
        _logsFlow.value = snapshot
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
        }
        _logsFlow.value = emptyList()
    }

    fun exportAsText(entries: List<LogEntry>? = null): String {
        val target = entries ?: _logsFlow.value
        return target.joinToString(separator = "\n") { it.formatLine() }
    }
}
