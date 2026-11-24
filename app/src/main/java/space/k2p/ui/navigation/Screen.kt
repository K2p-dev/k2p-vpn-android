package space.k2p.ui.navigation

sealed class Screen(val route: String) {
    data object Auth : Screen("auth")
    data object Main : Screen("main")
    data object Tariffs : Screen("tariffs")
    data object Profile : Screen("profile")
    data object Settings : Screen("settings")
}
