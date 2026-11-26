package space.k2p.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.k2p.core.device.AppListManager
import space.k2p.core.device.InstalledAppInfo
import space.k2p.core.preferences.AccentMode
import space.k2p.core.preferences.SplitTunnelingPreferences
import space.k2p.core.preferences.ThemeConfig
import space.k2p.core.preferences.ThemeMode
import space.k2p.core.preferences.ThemePreferences
import space.k2p.core.geodata.GeodataManager
import space.k2p.core.preferences.GeodataPreferences
import space.k2p.core.vpn.RealVpnCoreEngine
import space.k2p.core.vpn.VpnState
import space.k2p.data.repository.AppUpdateInfo
import space.k2p.data.repository.AppUpdateRepository

data class SettingsUiState(
    val themeConfig: ThemeConfig = ThemeConfig(),
    val isSplitTunnelingEnabled: Boolean = false,
    val excludedPackages: Set<String> = emptySet(),
    val showSystemApps: Boolean = false,
    val installedApps: List<InstalledAppInfo> = emptyList(),
    val appSearchQuery: String = "",
    val isLoadingApps: Boolean = false,
    val isCheckingUpdate: Boolean = false,
    val updateInfo: AppUpdateInfo? = null,
    val updateMessage: String? = null,
    val showUpdateDialog: Boolean = false,
    val isBypassRussiaEnabled: Boolean = true,
    val geodataLastUpdated: String = "Встроена",
    val isUpdatingGeodata: Boolean = false,
    val geodataMessage: String? = null
) {
    val filteredApps: List<InstalledAppInfo>
        get() {
            if (appSearchQuery.isBlank()) return installedApps
            val query = appSearchQuery.trim().lowercase()
            return installedApps.filter {
                it.appName.lowercase().contains(query) || it.packageName.lowercase().contains(query)
            }
        }
}

class SettingsViewModel(
    private val context: Context,
    private val themePreferences: ThemePreferences,
    private val splitPreferences: SplitTunnelingPreferences,
    private val appUpdateRepository: AppUpdateRepository,
    private val geodataPreferences: GeodataPreferences = space.k2p.K2pApp.instance.geodataPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                themePreferences.themeConfigFlow,
                splitPreferences.isEnabledFlow,
                splitPreferences.excludedPackagesFlow,
                splitPreferences.showSystemAppsFlow
            ) { theme, isSplitEnabled, excluded, showSystem ->
                _uiState.update {
                    it.copy(
                        themeConfig = theme,
                        isSplitTunnelingEnabled = isSplitEnabled,
                        excludedPackages = excluded,
                        showSystemApps = showSystem
                    )
                }
            }.collect {}
        }

        viewModelScope.launch {
            geodataPreferences.bypassRussiaEnabledFlow.collect { enabled ->
                _uiState.update { it.copy(isBypassRussiaEnabled = enabled) }
            }
        }

        viewModelScope.launch {
            geodataPreferences.lastUpdatedFlow.collect { updated ->
                _uiState.update { it.copy(geodataLastUpdated = updated) }
            }
        }

        loadApps()
    }

    fun loadApps() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingApps = true) }
            val showSystem = _uiState.value.showSystemApps
            val apps = AppListManager.getInstalledApps(context, includeSystemApps = showSystem)
            _uiState.update { it.copy(installedApps = apps, isLoadingApps = false) }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            themePreferences.setThemeMode(mode)
        }
    }

    fun setAccentMode(mode: AccentMode) {
        viewModelScope.launch {
            themePreferences.setAccentMode(mode)
        }
    }

    fun setCustomAccentHex(hex: String) {
        viewModelScope.launch {
            themePreferences.setCustomAccentHex(hex)
        }
    }

    fun toggleSplitTunneling(enabled: Boolean) {
        viewModelScope.launch {
            splitPreferences.setEnabled(enabled)
        }
    }

    fun toggleShowSystemApps(show: Boolean) {
        viewModelScope.launch {
            splitPreferences.setShowSystemApps(show)
            _uiState.update { it.copy(showSystemApps = show) }
            loadApps()
        }
    }

    fun onAppSearchQueryChanged(query: String) {
        _uiState.update { it.copy(appSearchQuery = query) }
    }

    fun togglePackageExcluded(packageName: String) {
        viewModelScope.launch {
            splitPreferences.togglePackage(packageName)
        }
    }

    fun checkForUpdate() {
        viewModelScope.launch {
            _uiState.update { it.copy(isCheckingUpdate = true, updateMessage = null) }
            val result = space.k2p.K2pApp.instance.appUpdateManager.checkForUpdate()
            result.onSuccess { info ->
                _uiState.update {
                    it.copy(
                        isCheckingUpdate = false,
                        updateInfo = info,
                        showUpdateDialog = true,
                        updateMessage = if (info.isUpdateAvailable) null else "У вас установлена самая актуальная версия (${info.currentVersion})"
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isCheckingUpdate = false,
                        updateMessage = "Ошибка проверки обновлений: ${error.message}"
                    )
                }
            }
        }
    }

    fun dismissUpdateDialog() {
        _uiState.update { it.copy(showUpdateDialog = false) }
    }

    fun dismissUpdateMessage() {
        _uiState.update { it.copy(updateMessage = null) }
    }

    fun toggleBypassRussia(enabled: Boolean) {
        viewModelScope.launch {
            geodataPreferences.setBypassRussiaEnabled(enabled)
            // If VPN is currently connected, restart it to apply updated routing immediately
            if (RealVpnCoreEngine.internalState.value == VpnState.CONNECTED) {
                GeodataManager.restartVpn(context)
            }
        }
    }

    fun updateGeodataManual() {
        viewModelScope.launch {
            _uiState.update { it.copy(isUpdatingGeodata = true, geodataMessage = null) }
            val result = GeodataManager.updateGeodataOta(context)
            result.onSuccess { msg ->
                _uiState.update { it.copy(isUpdatingGeodata = false, geodataMessage = msg) }
            }.onFailure { err ->
                _uiState.update { it.copy(isUpdatingGeodata = false, geodataMessage = "Ошибка обновления: ${err.message}") }
            }
        }
    }

    fun dismissGeodataMessage() {
        _uiState.update { it.copy(geodataMessage = null) }
    }
}
