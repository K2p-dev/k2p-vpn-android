package space.k2p.core.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.splitDataStore by preferencesDataStore(name = "k2p_split_tunneling_prefs")

class SplitTunnelingPreferences(private val context: Context) {
    private val keyIsEnabled = booleanPreferencesKey("split_tunneling_enabled")
    private val keyExcludedPackages = stringSetPreferencesKey("excluded_packages")
    private val keyShowSystemApps = booleanPreferencesKey("show_system_apps")

    val isEnabledFlow: Flow<Boolean> = context.splitDataStore.data.map { prefs ->
        prefs[keyIsEnabled] ?: false
    }

    val excludedPackagesFlow: Flow<Set<String>> = context.splitDataStore.data.map { prefs ->
        prefs[keyExcludedPackages] ?: emptySet()
    }

    val showSystemAppsFlow: Flow<Boolean> = context.splitDataStore.data.map { prefs ->
        prefs[keyShowSystemApps] ?: false
    }

    suspend fun setEnabled(enabled: Boolean) {
        context.splitDataStore.edit { prefs ->
            prefs[keyIsEnabled] = enabled
        }
    }

    suspend fun setShowSystemApps(show: Boolean) {
        context.splitDataStore.edit { prefs ->
            prefs[keyShowSystemApps] = show
        }
    }

    suspend fun togglePackage(packageName: String) {
        context.splitDataStore.edit { prefs ->
            val current = prefs[keyExcludedPackages]?.toMutableSet() ?: mutableSetOf()
            if (current.contains(packageName)) {
                current.remove(packageName)
            } else {
                current.add(packageName)
            }
            prefs[keyExcludedPackages] = current
        }
    }

    suspend fun clear() {
        context.splitDataStore.edit { prefs ->
            prefs[keyExcludedPackages] = emptySet()
        }
    }
}
