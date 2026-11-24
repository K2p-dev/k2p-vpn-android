package space.k2p.core.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import space.k2p.core.config.AppConfig

private val Context.subDataStore by preferencesDataStore(name = "k2p_subscription_prefs")

class SubscriptionPreferences(private val context: Context) {

    companion object {
        private val KEY_NODES_JSON = stringPreferencesKey("nodes_json")
        private val KEY_SELECTED_NODE_ID = stringPreferencesKey("selected_node_id")
        private val KEY_TRAFFIC_INFO = stringPreferencesKey("traffic_info")
        private val KEY_ANNOUNCE_SLOGAN = stringPreferencesKey("announce_slogan")
        private val KEY_LAST_UPDATED = longPreferencesKey("last_updated_epoch_ms")
        private val KEY_SUB_STATUS = stringPreferencesKey("sub_status")
        private val KEY_SUB_EXPIRY_DAYS = intPreferencesKey("sub_expiry_days")
        private val KEY_SUB_EXPIRY_DATE = stringPreferencesKey("sub_expiry_date")
        private val KEY_UPDATE_INTERVAL_HOURS = longPreferencesKey("update_interval_hours")
    }

    val nodesJsonFlow: Flow<String?> = context.subDataStore.data.map { prefs ->
        prefs[KEY_NODES_JSON]
    }

    val selectedNodeIdFlow: Flow<String?> = context.subDataStore.data.map { prefs ->
        prefs[KEY_SELECTED_NODE_ID]
    }

    val trafficInfoFlow: Flow<String> = context.subDataStore.data.map { prefs ->
        prefs[KEY_TRAFFIC_INFO] ?: "30.1 GB / ∞"
    }

    val announceSloganFlow: Flow<String> = context.subDataStore.data.map { prefs ->
        prefs[KEY_ANNOUNCE_SLOGAN] ?: AppConfig.APP_SLOGAN
    }

    val lastUpdatedEpochMsFlow: Flow<Long> = context.subDataStore.data.map { prefs ->
        prefs[KEY_LAST_UPDATED] ?: 0L
    }

    val updateIntervalHoursFlow: Flow<Long> = context.subDataStore.data.map { prefs ->
        prefs[KEY_UPDATE_INTERVAL_HOURS] ?: 6L
    }

    val subStatusFlow: Flow<String> = context.subDataStore.data.map { prefs ->
        prefs[KEY_SUB_STATUS] ?: "unknown"
    }

    val subExpiryDaysFlow: Flow<Int> = context.subDataStore.data.map { prefs ->
        prefs[KEY_SUB_EXPIRY_DAYS] ?: 0
    }

    val subExpiryDateFlow: Flow<String> = context.subDataStore.data.map { prefs ->
        prefs[KEY_SUB_EXPIRY_DATE] ?: ""
    }

    suspend fun saveSubscriptionData(
        nodesJson: String,
        trafficInfo: String,
        announceSlogan: String,
        status: String,
        expiryDays: Int,
        expiryDate: String,
        updateIntervalHours: Long = 6L
    ) {
        context.subDataStore.edit { prefs ->
            prefs[KEY_NODES_JSON] = nodesJson
            prefs[KEY_TRAFFIC_INFO] = trafficInfo
            prefs[KEY_ANNOUNCE_SLOGAN] = announceSlogan
            prefs[KEY_LAST_UPDATED] = System.currentTimeMillis()
            prefs[KEY_SUB_STATUS] = status
            prefs[KEY_SUB_EXPIRY_DAYS] = expiryDays
            prefs[KEY_SUB_EXPIRY_DATE] = expiryDate
            prefs[KEY_UPDATE_INTERVAL_HOURS] = updateIntervalHours
        }
    }

    suspend fun setSelectedNodeId(nodeId: String) {
        context.subDataStore.edit { prefs ->
            prefs[KEY_SELECTED_NODE_ID] = nodeId
        }
    }

    suspend fun updateNodesJson(nodesJson: String) {
        context.subDataStore.edit { prefs ->
            prefs[KEY_NODES_JSON] = nodesJson
        }
    }

    suspend fun clear() {
        context.subDataStore.edit { prefs ->
            prefs.clear()
        }
    }
}
