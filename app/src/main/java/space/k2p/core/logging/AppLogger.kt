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
    NET("NET")
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
            LogCategory.NETWORK_API -> tag == LogTag.API || tag == LogTag.NET || tag == LogTag.OTA
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

    private fun log(tag: LogTag, level: LogLevel, message: String, throwable: Throwable?) {
        val androidTag = "K2P-${tag.label}"
        when (level) {
            LogLevel.DEBUG -> Log.d(androidTag, message, throwable)
            LogLevel.INFO -> Log.i(androidTag, message, throwable)
            LogLevel.WARN -> Log.w(androidTag, message, throwable)
            LogLevel.ERROR -> Log.e(androidTag, message, throwable)
        }

        val fullMessage = if (throwable != null) {
            val stack = Log.getStackTraceString(throwable).trim()
            if (stack.isNotBlank()) "$message\n$stack" else "$message: ${throwable.message}"
        } else {
            message
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
