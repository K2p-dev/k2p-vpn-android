package space.k2p.easteregg

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.caseMiniGameDataStore by preferencesDataStore(name = "case_minigame_prefs")

class CaseMiniGameStorage(
    private val context: Context
) {
    private val stateKey = stringPreferencesKey("minigame_state_v1")

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        isLenient = true
    }

    suspend fun loadOrCreateInitial(): MiniGameState {
        val loaded = loadSafely()
        if (loaded != null && loaded.initialized) {
            return sanitize(loaded)
        }

        val starterCases = (1..CaseMiniGameConstants.INITIAL_CASE_COUNT).map { index ->
            CaseGenerator.generateCase(index)
        }
        val initialState = MiniGameState(
            initialized = true,
            balance = 0,
            casesOpened = 0,
            nextCaseNumber = CaseMiniGameConstants.INITIAL_CASE_COUNT + 1,
            cases = starterCases,
            inventory = emptyList()
        )
        save(initialState)
        return initialState
    }

    suspend fun save(state: MiniGameState) {
        val clean = sanitize(state)
        runCatching {
            val encoded = json.encodeToString(clean)
            context.caseMiniGameDataStore.edit { prefs ->
                prefs[stateKey] = encoded
            }
        }
    }

    private suspend fun loadSafely(): MiniGameState? {
        return try {
            val prefs = context.caseMiniGameDataStore.data.firstOrNull() ?: return null
            val raw = prefs[stateKey] ?: return null
            if (raw.isBlank()) return null
            json.decodeFromString<MiniGameState>(raw)
        } catch (_: Exception) {
            // Corrupted JSON or incompatible schema: recover gracefully without crashing
            null
        }
    }

    private fun sanitize(state: MiniGameState): MiniGameState {
        val safeBalance = state.balance.coerceIn(0, CaseMiniGameConstants.MAX_BALANCE)
        val safeOpened = state.casesOpened.coerceAtLeast(0)
        val safeNextNum = state.nextCaseNumber.coerceAtLeast(1)

        val safeCases = state.cases
            .take(CaseMiniGameConstants.MAX_CASES_SIZE)
            .mapIndexed { idx, weaponCase ->
                val validSkins = weaponCase.skins
                    .take(CaseMiniGameConstants.SKINS_PER_CASE)
                    .map { skin ->
                        skin.copy(sellPrice = skin.rarity.fixedPrice)
                    }
                val counts = validSkins.groupingBy { it.rarity }.eachCount()
                val validDistribution = validSkins.size == CaseMiniGameConstants.SKINS_PER_CASE &&
                    (counts[SkinRarity.LEGENDARY] ?: 0) == SkinRarity.LEGENDARY.maxPerCase &&
                    (counts[SkinRarity.EPIC] ?: 0) <= SkinRarity.EPIC.maxPerCase &&
                    (counts[SkinRarity.RARE] ?: 0) <= SkinRarity.RARE.maxPerCase &&
                    (counts[SkinRarity.COMMON] ?: 0) <= SkinRarity.COMMON.maxPerCase
                if (validDistribution) {
                    weaponCase.copy(skins = validSkins)
                } else {
                    CaseGenerator.generateCase(idx + 1)
                }
            }

        val safeInventory = state.inventory
            .take(CaseMiniGameConstants.MAX_INVENTORY_SIZE)
            .map { skin ->
                skin.copy(sellPrice = skin.rarity.fixedPrice)
            }

        return state.copy(
            balance = safeBalance,
            casesOpened = safeOpened,
            nextCaseNumber = safeNextNum,
            cases = safeCases,
            inventory = safeInventory
        )
    }
}
