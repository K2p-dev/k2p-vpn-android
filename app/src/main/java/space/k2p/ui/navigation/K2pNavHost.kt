package space.k2p.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import space.k2p.K2pApp
import space.k2p.core.preferences.ThemePreferences
import space.k2p.ui.screens.auth.AuthScreen
import space.k2p.ui.screens.auth.AuthViewModel
import space.k2p.ui.screens.main.MainScreen
import space.k2p.ui.screens.profile.ProfileScreen
import space.k2p.ui.screens.profile.ProfileViewModel
import space.k2p.ui.screens.settings.SettingsScreen
import space.k2p.ui.screens.settings.SettingsViewModel
import space.k2p.ui.screens.tariffs.TariffsScreen
import space.k2p.ui.screens.tariffs.TariffsViewModel

@Composable
fun K2pNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    startDestination: String = Screen.Auth.route
) {
    val context = LocalContext.current.applicationContext

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        composable(Screen.Auth.route) {
            val authViewModel: AuthViewModel = viewModel {
                AuthViewModel(K2pApp.instance.authRepository)
            }
            AuthScreen(
                viewModel = authViewModel,
                onLoginSuccess = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.Auth.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Main.route) {
            val mainViewModel: space.k2p.ui.screens.main.MainViewModel = viewModel {
                space.k2p.ui.screens.main.MainViewModel(
                    subscriptionRepository = K2pApp.instance.subscriptionRepository,
                    clientRepository = K2pApp.instance.clientRepository,
                    vpnCoreEngine = space.k2p.core.vpn.VpnCoreProvider.engine
                )
            }
            MainScreen(
                viewModel = mainViewModel,
                onNavigateToTariffs = { navController.navigate(Screen.Tariffs.route) },
                onNavigateToProfile = { navController.navigate(Screen.Profile.route) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) }
            )
        }

        composable(Screen.Tariffs.route) {
            val tariffsViewModel: TariffsViewModel = viewModel {
                TariffsViewModel(
                    paymentRepository = K2pApp.instance.paymentRepository,
                    clientRepository = K2pApp.instance.clientRepository
                )
            }
            TariffsScreen(
                viewModel = tariffsViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Profile.route) {
            val profileViewModel: ProfileViewModel = viewModel {
                ProfileViewModel(
                    clientRepository = K2pApp.instance.clientRepository,
                    authRepository = K2pApp.instance.authRepository
                )
            }
            ProfileScreen(
                viewModel = profileViewModel,
                onBack = { navController.popBackStack() },
                onLogout = {
                    navController.navigate(Screen.Auth.route) {
                        popUpTo(Screen.Main.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Settings.route) {
            val settingsViewModel: SettingsViewModel = viewModel {
                SettingsViewModel(
                    context = context,
                    themePreferences = ThemePreferences(context),
                    splitPreferences = K2pApp.instance.splitTunnelingPreferences,
                    appUpdateRepository = K2pApp.instance.appUpdateRepository,
                    geodataPreferences = K2pApp.instance.geodataPreferences
                )
            }
            SettingsScreen(
                viewModel = settingsViewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
