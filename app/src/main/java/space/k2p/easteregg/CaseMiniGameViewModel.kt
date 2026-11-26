package space.k2p.easteregg

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ActiveRouletteSession(
    val openingCase: WeaponCase,
    val tapeItems: List<WeaponSkinItem>,
    val winningIndexInTape: Int,
    val winningSkin: WeaponSkinItem,
    val subTileOffsetFraction: Float,
    val spinComplete: Boolean = false
)

data class CaseMiniGameUiState(
    val isLoading: Boolean = true,
    val balance: Int = 0,
    val casesOpened: Int = 0,
    val cases: List<WeaponCase> = emptyList(),
    val inventory: List<WeaponSkinItem> = emptyList(),
    val filterMode: InventoryFilterMode = InventoryFilterMode.CASES_FIRST,
    val activeRoulette: ActiveRouletteSession? = null,
    val inspectedSkin: WeaponSkinItem? = null,
    val snackbarMessage: String? = null
) {
    val sortedInventory: List<WeaponSkinItem>
        get() = inventory.sortedWith(
            compareByDescending<WeaponSkinItem> { it.rarity.sortPriority }
                .thenByDescending { it.sellPrice }
                .thenBy { it.title }
        )
}

class CaseMiniGameViewModel(
    appContext: Context
) : ViewModel() {

    private val storage = CaseMiniGameStorage(appContext.applicationContext)
    private var internalState = MiniGameState()

    private val _uiState = MutableStateFlow(CaseMiniGameUiState())
    val uiState: StateFlow<CaseMiniGameUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val loaded = storage.loadOrCreateInitial()
            internalState = loaded
            syncUiState(isLoading = false)
        }
    }

    fun setFilterMode(mode: InventoryFilterMode) {
        _uiState.update { it.copy(filterMode = mode) }
    }

    fun buyCase() {
        if (_uiState.value.isLoading || _uiState.value.activeRoulette != null) return

        val currentBalance = internalState.balance
        if (currentBalance < CaseMiniGameConstants.CASE_PRICE_TUGRIKS) {
            _uiState.update {
                it.copy(
                    snackbarMessage = "Недостаточно тугриков (нужно ${CaseMiniGameConstants.CASE_PRICE_TUGRIKS} Т)"
                )
            }
            return
        }

        if (internalState.cases.size >= CaseMiniGameConstants.MAX_CASES_SIZE) {
            _uiState.update {
                it.copy(snackbarMessage = "Достигнут лимит кейсов (${CaseMiniGameConstants.MAX_CASES_SIZE})")
            }
            return
        }

        val newBalance = safeSubtractBalance(currentBalance, CaseMiniGameConstants.CASE_PRICE_TUGRIKS)
        val newCase = CaseGenerator.generateCase(internalState.nextCaseNumber)
        internalState = internalState.copy(
            balance = newBalance,
            nextCaseNumber = internalState.nextCaseNumber + 1,
            cases = listOf(newCase) + internalState.cases
        )
        persistAndSync("Куплен ${newCase.title} за ${CaseMiniGameConstants.CASE_PRICE_TUGRIKS} Т")
    }

    fun startOpeningCase(caseId: String) {
        if (_uiState.value.isLoading || _uiState.value.activeRoulette != null) return

        val targetCase = internalState.cases.firstOrNull { it.id == caseId } ?: return
        if (targetCase.skins.size != CaseMiniGameConstants.SKINS_PER_CASE) return

        // Consume case immediately so rapid double-taps cannot duplicate rewards
        val remainingCases = internalState.cases.filterNot { it.id == caseId }
        internalState = internalState.copy(
            cases = remainingCases,
            casesOpened = internalState.casesOpened + 1
        )
        viewModelScope.launch { storage.save(internalState) }

        val winIndexInCase = CaseGenerator.pickWinningIndex(targetCase.skins)
        val winningSkin = targetCase.skins[winIndexInCase]

        // Build repeating tape strictly from the 11 pre-generated skins of this case
        val cycles = 6
        val tape = buildList {
            repeat(cycles) {
                addAll(targetCase.skins)
            }
        }
        val winningTapeIndex = 4 * CaseMiniGameConstants.SKINS_PER_CASE + winIndexInCase
        val jitter = ((System.nanoTime() % 61L) - 30L) / 100f // [-0.30 .. +0.30]

        val session = ActiveRouletteSession(
            openingCase = targetCase,
            tapeItems = tape,
            winningIndexInTape = winningTapeIndex,
            winningSkin = winningSkin,
            subTileOffsetFraction = jitter,
            spinComplete = false
        )

        _uiState.update {
            it.copy(
                cases = remainingCases,
                casesOpened = internalState.casesOpened,
                activeRoulette = session
            )
        }
    }

    fun markRouletteFinished() {
        _uiState.update { current ->
            val session = current.activeRoulette ?: return@update current
            current.copy(activeRoulette = session.copy(spinComplete = true))
        }
    }

    fun claimWonSkinToInventory() {
        val session = _uiState.value.activeRoulette ?: return
        if (!session.spinComplete) return

        if (internalState.inventory.size >= CaseMiniGameConstants.MAX_INVENTORY_SIZE) {
            _uiState.update {
                it.copy(
                    snackbarMessage = "Инвентарь переполнен (макс. ${CaseMiniGameConstants.MAX_INVENTORY_SIZE}). Продайте скин."
                )
            }
            return
        }

        internalState = internalState.copy(
            inventory = listOf(session.winningSkin) + internalState.inventory
        )
        _uiState.update { it.copy(activeRoulette = null) }
        persistAndSync("Скин «${session.winningSkin.title}» добавлен в инвентарь")
    }

    fun sellWonSkinImmediately() {
        val session = _uiState.value.activeRoulette ?: return
        if (!session.spinComplete) return

        val price = session.winningSkin.rarity.fixedPrice
        val newBalance = safeAddBalance(internalState.balance, price)
        internalState = internalState.copy(balance = newBalance)
        _uiState.update { it.copy(activeRoulette = null) }
        persistAndSync("Продано за $price Т")
    }

    fun inspectSkin(skin: WeaponSkinItem) {
        if (_uiState.value.activeRoulette != null) return
        _uiState.update { it.copy(inspectedSkin = skin) }
    }

    fun dismissInspect() {
        _uiState.update { it.copy(inspectedSkin = null) }
    }

    fun sellInventorySkin(skinId: String) {
        val skin = internalState.inventory.firstOrNull { it.id == skinId } ?: return
        val remainingInventory = internalState.inventory.filterNot { it.id == skinId }
        val price = skin.rarity.fixedPrice
        val newBalance = safeAddBalance(internalState.balance, price)

        internalState = internalState.copy(
            balance = newBalance,
            inventory = remainingInventory
        )
        _uiState.update { current ->
            current.copy(
                inspectedSkin = if (current.inspectedSkin?.id == skinId) null else current.inspectedSkin
            )
        }
        persistAndSync("Продано: ${skin.title} (+$price Т)")
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    private fun persistAndSync(message: String? = null) {
        viewModelScope.launch {
            storage.save(internalState)
        }
        syncUiState(isLoading = false, message = message)
    }

    private fun syncUiState(isLoading: Boolean, message: String? = null) {
        _uiState.update {
            it.copy(
                isLoading = isLoading,
                balance = internalState.balance,
                casesOpened = internalState.casesOpened,
                cases = internalState.cases,
                inventory = internalState.inventory,
                snackbarMessage = message ?: it.snackbarMessage
            )
        }
    }

    private fun safeAddBalance(current: Int, delta: Int): Int {
        if (delta <= 0) return current.coerceAtLeast(0)
        val sum = current.toLong() + delta.toLong()
        return sum.coerceIn(0L, CaseMiniGameConstants.MAX_BALANCE.toLong()).toInt()
    }

    private fun safeSubtractBalance(current: Int, cost: Int): Int {
        if (cost <= 0) return current.coerceAtLeast(0)
        val diff = current.toLong() - cost.toLong()
        return diff.coerceIn(0L, CaseMiniGameConstants.MAX_BALANCE.toLong()).toInt()
    }
}
