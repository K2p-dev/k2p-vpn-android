package space.k2p.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.k2p.data.api.model.EmailRegisterInitRequest
import space.k2p.data.api.model.EmailRegisterVerifyRequest
import space.k2p.data.api.model.LoginRequest
import space.k2p.data.api.model.TgRegisterRequest
import space.k2p.data.repository.ApiException
import space.k2p.data.repository.AuthRepository

enum class AuthTab {
    LOGIN,
    REGISTER
}

enum class RegisterMode {
    EMAIL_CODE,
    TELEGRAM
}

enum class RegisterStep {
    INPUT_CREDENTIALS,
    VERIFY_EMAIL_CODE,
    WAITING_TELEGRAM
}

data class AuthUiState(
    val selectedTab: AuthTab = AuthTab.LOGIN,
    val registerMode: RegisterMode = RegisterMode.EMAIL_CODE,
    val registerStep: RegisterStep = RegisterStep.INPUT_CREDENTIALS,
    val email: String = "",
    val password: String = "",
    val emailCode: String = "",
    val verifyToken: String = "",
    val codeExpiresInSeconds: Int = 900,
    val tgToken: String = "",
    val tgBotUrl: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val showDeviceLimitDialog: Boolean = false
)

sealed class AuthNavigationEvent {
    data object NavigateToMain : AuthNavigationEvent()
    data class OpenUrl(val url: String) : AuthNavigationEvent()
}

class AuthViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private val _navigationEvents = MutableSharedFlow<AuthNavigationEvent>(extraBufferCapacity = 1)
    val navigationEvents: SharedFlow<AuthNavigationEvent> = _navigationEvents.asSharedFlow()

    private var pollingJob: Job? = null
    private var countdownJob: Job? = null

    fun selectTab(tab: AuthTab) {
        stopPolling()
        stopCountdown()
        _uiState.update {
            it.copy(
                selectedTab = tab,
                registerStep = RegisterStep.INPUT_CREDENTIALS,
                errorMessage = null,
                isLoading = false,
                emailCode = ""
            )
        }
    }

    fun selectRegisterMode(mode: RegisterMode) {
        stopPolling()
        stopCountdown()
        _uiState.update {
            it.copy(
                registerMode = mode,
                registerStep = RegisterStep.INPUT_CREDENTIALS,
                errorMessage = null,
                isLoading = false,
                emailCode = ""
            )
        }
    }

    fun onEmailChanged(value: String) {
        _uiState.update { it.copy(email = value.trim(), errorMessage = null) }
    }

    fun onPasswordChanged(value: String) {
        _uiState.update { it.copy(password = value, errorMessage = null) }
    }

    fun onEmailCodeChanged(value: String) {
        if (value.length <= 6 && value.all { it.isDigit() }) {
            _uiState.update { it.copy(emailCode = value, errorMessage = null) }
            if (value.length == 6) {
                verifyEmailCode(value)
            }
        }
    }

    fun dismissDeviceLimitDialog() {
        _uiState.update { it.copy(showDeviceLimitDialog = false) }
    }

    // 1. Login with email & password
    fun login() {
        val state = _uiState.value
        if (state.email.isBlank() || state.password.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Введите email и пароль") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = authRepository.login(LoginRequest(email = state.email, password = state.password))
            result.onSuccess {
                _uiState.update { it.copy(isLoading = false) }
                _navigationEvents.emit(AuthNavigationEvent.NavigateToMain)
            }.onFailure { error ->
                handleError(error)
            }
        }
    }

    // 2. Register without Telegram: Step 1 (Send Code)
    fun initEmailRegister() {
        val state = _uiState.value
        if (state.email.isBlank() || state.password.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Введите email и пароль") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = authRepository.registerEmailInit(
                EmailRegisterInitRequest(email = state.email, password = state.password)
            )
            result.onSuccess { response ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        verifyToken = response.verifyToken.orEmpty(),
                        codeExpiresInSeconds = response.expiresIn ?: 900,
                        registerStep = RegisterStep.VERIFY_EMAIL_CODE,
                        emailCode = ""
                    )
                }
                startCountdown(response.expiresIn ?: 900)
            }.onFailure { error ->
                handleError(error)
            }
        }
    }

    // 2. Register without Telegram: Step 2 (Verify Code)
    fun verifyEmailCode(codeToVerify: String? = null) {
        val state = _uiState.value
        val code = codeToVerify ?: state.emailCode
        if (code.length != 6) {
            _uiState.update { it.copy(errorMessage = "Введите 6-значный код из письма") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = authRepository.registerEmailVerify(
                request = EmailRegisterVerifyRequest(
                    verifyToken = state.verifyToken,
                    code = code
                ),
                email = state.email
            )
            result.onSuccess {
                stopCountdown()
                _uiState.update { it.copy(isLoading = false) }
                _navigationEvents.emit(AuthNavigationEvent.NavigateToMain)
            }.onFailure { error ->
                handleError(error)
            }
        }
    }

    // 3. Register with Telegram link
    fun registerWithTelegram() {
        val state = _uiState.value
        if (state.email.isBlank() || state.password.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Введите email и пароль") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = authRepository.registerWithTelegram(
                TgRegisterRequest(email = state.email, password = state.password)
            )
            result.onSuccess { response ->
                val botUrl = response.botUrl.orEmpty()
                val token = response.token.orEmpty()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        tgToken = token,
                        tgBotUrl = botUrl,
                        registerStep = RegisterStep.WAITING_TELEGRAM
                    )
                }
                if (botUrl.isNotBlank()) {
                    _navigationEvents.emit(AuthNavigationEvent.OpenUrl(botUrl))
                }
                startPolling(token)
            }.onFailure { error ->
                handleError(error)
            }
        }
    }

    // 4. Quick Login with Telegram
    fun loginWithTelegram() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = authRepository.initTelegramAuth()
            result.onSuccess { response ->
                val botUrl = response.botUrl.orEmpty()
                val token = response.token.orEmpty()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        tgToken = token,
                        tgBotUrl = botUrl,
                        registerStep = RegisterStep.WAITING_TELEGRAM
                    )
                }
                if (botUrl.isNotBlank()) {
                    _navigationEvents.emit(AuthNavigationEvent.OpenUrl(botUrl))
                }
                startPolling(token)
            }.onFailure { error ->
                handleError(error)
            }
        }
    }

    private fun startPolling(token: String) {
        stopPolling()
        if (token.isBlank()) return

        pollingJob = viewModelScope.launch {
            while (true) {
                delay(2000L) // Poll every 2 seconds
                val result = authRepository.checkTelegramAuth(token)
                result.onSuccess { response ->
                    if (response.status.equals("ok", ignoreCase = true)) {
                        stopPolling()
                        _navigationEvents.emit(AuthNavigationEvent.NavigateToMain)
                        return@launch
                    }
                }.onFailure { error ->
                    if (error is ApiException && error.code == "DEVICE_LIMIT_REACHED") {
                        stopPolling()
                        _uiState.update { it.copy(showDeviceLimitDialog = true, isLoading = false) }
                        return@launch
                    }
                }
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private fun startCountdown(totalSeconds: Int) {
        stopCountdown()
        countdownJob = viewModelScope.launch {
            var remaining = totalSeconds
            while (remaining > 0) {
                delay(1000L)
                remaining--
                _uiState.update { it.copy(codeExpiresInSeconds = remaining) }
            }
        }
    }

    private fun stopCountdown() {
        countdownJob?.cancel()
        countdownJob = null
    }

    private fun handleError(error: Throwable) {
        if (error is ApiException && error.code == "DEVICE_LIMIT_REACHED") {
            _uiState.update { it.copy(isLoading = false, showDeviceLimitDialog = true) }
        } else {
            _uiState.update { it.copy(isLoading = false, errorMessage = error.message ?: "Произошла ошибка") }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
        stopCountdown()
    }
}
