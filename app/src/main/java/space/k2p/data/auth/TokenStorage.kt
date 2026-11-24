package space.k2p.data.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import space.k2p.core.security.KeystoreStorage

private val Context.tokenDataStore by preferencesDataStore(name = "k2p_auth_prefs")

class TokenStorage(private val context: Context) {
    private val keyTokenCipher = stringPreferencesKey("jwt_cipher")
    private val keyTokenIv = stringPreferencesKey("jwt_iv")
    private val keyUserId = longPreferencesKey("user_id")
    private val keyEmail = stringPreferencesKey("user_email")
    private val keyHwid = stringPreferencesKey("user_hwid")

    val tokenFlow: Flow<String?> = context.tokenDataStore.data.map { prefs ->
        val cipher = prefs[keyTokenCipher]
        val iv = prefs[keyTokenIv]
        if (cipher != null && iv != null) {
            KeystoreStorage.decrypt(ivBase64 = iv, cipherTextBase64 = cipher)
        } else {
            null
        }
    }

    val userIdFlow: Flow<Long?> = context.tokenDataStore.data.map { prefs ->
        prefs[keyUserId]
    }

    val emailFlow: Flow<String?> = context.tokenDataStore.data.map { prefs ->
        prefs[keyEmail]
    }

    val isLoggedInFlow: Flow<Boolean> = tokenFlow.map { it != null }

    fun getJwtSync(): String? {
        return runBlocking {
            tokenFlow.firstOrNull()
        }
    }

    suspend fun saveAuth(token: String, userId: Long, email: String? = null, hwid: String? = null) {
        val encrypted = KeystoreStorage.encrypt(token) ?: return
        context.tokenDataStore.edit { prefs ->
            prefs[keyTokenCipher] = encrypted.cipherText
            prefs[keyTokenIv] = encrypted.iv
            prefs[keyUserId] = userId
            if (email != null) {
                prefs[keyEmail] = email
            }
            if (hwid != null) {
                prefs[keyHwid] = hwid
            }
        }
    }

    suspend fun clear() {
        context.tokenDataStore.edit { prefs ->
            prefs.clear()
        }
    }
}
