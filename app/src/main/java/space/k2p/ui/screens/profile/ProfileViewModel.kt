package space.k2p.ui.screens.profile

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
import space.k2p.data.api.model.DeviceItemDto
import space.k2p.data.api.model.UserProfileResponse
import space.k2p.data.repository.AuthRepository
import space.k2p.data.repository.ClientRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

data class ProfileUiState(
    val isLoading: Boolean = false,
    val profile: UserProfileResponse? = null,
    val devices: List<DeviceItemDto> = emptyList(),
    val errorMessage: String? = null,
    val deviceToDelete: DeviceItemDto? = null,
    val isDeletingDevice: Boolean = false
) {
    val accountTypeTitle: String
        get() {
            val p = profile ?: return ""
            return if (p.tgLinked) {
                val userTag = if (!p.username.isNullOrBlank()) " (@${p.username})" else ""
                "TG ${p.userId ?: 0}$userTag"
            } else {
                val cleanId = abs(p.userId ?: 0)
                "Веб-аккаунт #$cleanId"
            }
        }

    val formattedExpiryDate: String
        get() {
            val ms = profile?.expiryTimeMs ?: return ""
            return try {
                val sdf = SimpleDateFormat("dd MMMM yyyy", Locale.forLanguageTag("ru"))
                sdf.format(Date(ms))
            } catch (_: Exception) {
                ""
            }
        }
}

sealed class ProfileNavigationEvent {
    data object NavigateToAuth : ProfileNavigationEvent()
}

class ProfileViewModel(
    private val clientRepository: ClientRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    private val _navigationEvents = MutableSharedFlow<ProfileNavigationEvent>(extraBufferCapacity = 1)
    val navigationEvents: SharedFlow<ProfileNavigationEvent> = _navigationEvents.asSharedFlow()

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val profileResult = clientRepository.getProfile()
            val devicesResult = clientRepository.getDevices()

            if (profileResult.isSuccess) {
                _uiState.update {
                    it.copy(
                        profile = profileResult.getOrNull(),
                        devices = devicesResult.getOrDefault(emptyList()),
                        isLoading = false
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        errorMessage = profileResult.exceptionOrNull()?.message ?: "Не удалось загрузить профиль",
                        isLoading = false
                    )
                }
            }
        }
    }

    fun requestDeleteDevice(device: DeviceItemDto) {
        if (device.isCurrent) return
        _uiState.update { it.copy(deviceToDelete = device) }
    }

    fun dismissDeleteDialog() {
        _uiState.update { it.copy(deviceToDelete = null) }
    }

    fun confirmDeleteDevice() {
        val device = _uiState.value.deviceToDelete ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isDeletingDevice = true) }
            val result = clientRepository.deleteDevice(device.hwid)
            if (result.isSuccess) {
                _uiState.update {
                    it.copy(
                        deviceToDelete = null,
                        isDeletingDevice = false
                    )
                }
                loadData()
            } else {
                _uiState.update {
                    it.copy(
                        isDeletingDevice = false,
                        errorMessage = result.exceptionOrNull()?.message ?: "Не удалось отвязать устройство"
                    )
                }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            _navigationEvents.emit(ProfileNavigationEvent.NavigateToAuth)
        }
    }
}
