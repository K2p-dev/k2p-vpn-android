package space.k2p.core.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "k2p_theme_prefs")

enum class ThemeMode {
    DARK,
    LIGHT,
    SYSTEM
}

enum class AccentMode {
    BRAND,
    DYNAMIC,
    CUSTOM
}

data class ThemeConfig(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val accentMode: AccentMode = AccentMode.BRAND,
    val customAccentHex: String = "#D91438"
)

class ThemePreferences(private val context: Context) {
    private val keyThemeMode = stringPreferencesKey("theme_mode")
    private val keyAccentMode = stringPreferencesKey("accent_mode")
    private val keyCustomAccentHex = stringPreferencesKey("custom_accent_hex")

    val themeConfigFlow: Flow<ThemeConfig> = context.dataStore.data.map { prefs ->
        val themeModeStr = prefs[keyThemeMode] ?: ThemeMode.DARK.name
        val accentModeStr = prefs[keyAccentMode] ?: AccentMode.BRAND.name
        val customAccentHex = prefs[keyCustomAccentHex] ?: "#D91438"

        ThemeConfig(
            themeMode = runCatching { ThemeMode.valueOf(themeModeStr) }.getOrDefault(ThemeMode.DARK),
            accentMode = runCatching { AccentMode.valueOf(accentModeStr) }.getOrDefault(AccentMode.BRAND),
            customAccentHex = customAccentHex
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { prefs ->
            prefs[keyThemeMode] = mode.name
        }
    }

    suspend fun setAccentMode(mode: AccentMode) {
        context.dataStore.edit { prefs ->
            prefs[keyAccentMode] = mode.name
        }
    }

    suspend fun setCustomAccentHex(hex: String) {
        context.dataStore.edit { prefs ->
            prefs[keyCustomAccentHex] = hex
        }
    }
}
