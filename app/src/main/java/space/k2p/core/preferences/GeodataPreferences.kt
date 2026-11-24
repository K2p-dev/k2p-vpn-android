package space.k2p.core.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.geodataDataStore by preferencesDataStore(name = "k2p_geodata_prefs")

class GeodataPreferences(
    private val context: Context,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val keyBypassRussia = booleanPreferencesKey("bypass_russia_enabled")
    private val keyLastUpdated = stringPreferencesKey("geodata_last_updated")

    @Volatile
    var isBypassRussiaCached: Boolean = true
        private set

    val bypassRussiaEnabledFlow: Flow<Boolean> = context.geodataDataStore.data.map { prefs ->
        prefs[keyBypassRussia] ?: true
    }

    val lastUpdatedFlow: Flow<String> = context.geodataDataStore.data.map { prefs ->
        prefs[keyLastUpdated] ?: "Встроена"
    }

    init {
        scope.launch {
            bypassRussiaEnabledFlow.collect { enabled ->
                isBypassRussiaCached = enabled
            }
        }
    }

    suspend fun setBypassRussiaEnabled(enabled: Boolean) {
        isBypassRussiaCached = enabled
        context.geodataDataStore.edit { prefs ->
            prefs[keyBypassRussia] = enabled
        }
    }

    suspend fun setLastUpdated(dateStr: String) {
        context.geodataDataStore.edit { prefs ->
            prefs[keyLastUpdated] = dateStr
        }
    }
}
