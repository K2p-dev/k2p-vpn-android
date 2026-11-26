package space.k2p.core.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import space.k2p.core.security.KeystoreStorage
import space.k2p.data.api.model.EmergencyTransportState

private val Context.emergencyTransportDataStore by preferencesDataStore(name = "k2p_emergency_transport_prefs")

class EmergencyTransportStorage(
    private val context: Context,
    private val json: Json
) {
    private val keyCipher = stringPreferencesKey("state_cipher")
    private val keyIv = stringPreferencesKey("state_iv")

    suspend fun loadState(): EmergencyTransportState {
        return try {
            val prefs = context.emergencyTransportDataStore.data.firstOrNull() ?: return EmergencyTransportState()
            val cipher = prefs[keyCipher] ?: return EmergencyTransportState()
            val iv = prefs[keyIv] ?: return EmergencyTransportState()
            val plain = KeystoreStorage.decrypt(ivBase64 = iv, cipherTextBase64 = cipher)
                ?: return EmergencyTransportState()
            json.decodeFromString<EmergencyTransportState>(plain).copy(isSyncing = false)
        } catch (_: Exception) {
            EmergencyTransportState()
        }
    }

    suspend fun saveState(state: EmergencyTransportState) {
        val sanitized = state.copy(isSyncing = false, lastError = null)
        val plain = json.encodeToString(sanitized)
        val encrypted = KeystoreStorage.encrypt(plain) ?: return
        context.emergencyTransportDataStore.edit { prefs ->
            prefs[keyCipher] = encrypted.cipherText
            prefs[keyIv] = encrypted.iv
        }
    }

    suspend fun clear() {
        context.emergencyTransportDataStore.edit { prefs ->
            prefs.clear()
        }
    }
}
