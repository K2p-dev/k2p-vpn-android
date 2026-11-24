package space.k2p.ui.screens.tariffs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.k2p.data.api.model.TariffDto
import space.k2p.data.api.model.UserProfileResponse
import space.k2p.data.repository.ClientRepository
import space.k2p.data.repository.PaymentRepository

data class TariffsUiState(
    val isLoading: Boolean = false,
    val profile: UserProfileResponse? = null,
    val tariffs: List<TariffDto> = emptyList(),
    val discountPercent: Int = 0,
    val errorMessage: String? = null,
    val isCreatingPayment: Boolean = false
)

sealed class TariffsNavigationEvent {
    data class OpenPayUrl(val url: String) : TariffsNavigationEvent()
}

class TariffsViewModel(
    private val paymentRepository: PaymentRepository,
    private val clientRepository: ClientRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TariffsUiState())
    val uiState: StateFlow<TariffsUiState> = _uiState.asStateFlow()

    private val _navigationEvents = MutableSharedFlow<TariffsNavigationEvent>(extraBufferCapacity = 1)
    val navigationEvents: SharedFlow<TariffsNavigationEvent> = _navigationEvents.asSharedFlow()

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val profileResult = clientRepository.getProfile()
            val pricesResult = paymentRepository.getPrices()

            if (pricesResult.isSuccess) {
                val prices = pricesResult.getOrNull()
                _uiState.update {
                    it.copy(
                        profile = profileResult.getOrNull(),
                        tariffs = prices?.tariffs.orEmpty(),
                        discountPercent = prices?.discountPercent ?: 0,
                        isLoading = false
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        profile = profileResult.getOrNull(),
                        errorMessage = pricesResult.exceptionOrNull()?.message ?: "Не удалось загрузить тарифы",
                        isLoading = false
                    )
                }
            }
        }
    }

    fun buyTariff(tariff: TariffDto) {
        viewModelScope.launch {
            _uiState.update { it.copy(isCreatingPayment = true, errorMessage = null) }
            val result = paymentRepository.createPayment(tariff.months)
            result.onSuccess { response ->
                _uiState.update { it.copy(isCreatingPayment = false) }
                val url = response.payUrl
                if (!url.isNullOrBlank()) {
                    _navigationEvents.emit(TariffsNavigationEvent.OpenPayUrl(url))
                } else {
                    _uiState.update { it.copy(errorMessage = "Ссылка на оплату не получена") }
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isCreatingPayment = false,
                        errorMessage = error.message ?: "Ошибка создания платежа"
                    )
                }
            }
        }
    }
}
