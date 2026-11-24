package space.k2p.core.config

import space.k2p.BuildConfig

object AppConfig {
    const val APP_NAME: String = "Как 2 пальца"
    const val APP_SLOGAN: String = "Два движения. Ноль проблем."
    const val PACKAGE_NAME: String = "space.k2p"

    val VERSION_NAME: String get() = BuildConfig.VERSION_NAME
    val VERSION_CODE: Int get() = BuildConfig.VERSION_CODE

    const val API_BASE_URL: String = "https://api.proksbro.space"
    const val CABINET_URL: String = "https://lk.proksbro.space"
    const val OFFER_URL: String = "https://pay.proksbro.space/offer"
    const val BOT_URL: String = "https://t.me/easyforeasy_bot"
    const val SUPPORT_URL: String = "https://t.me/two_fingers_suport"

    const val GITHUB_RELEASES_API: String = "https://api.github.com/repos/K2p-dev/k2p-vpn-android/releases?per_page=1"

    const val SUB_AUTO_UPDATE_HOURS: Long = 6L
    const val MAX_DEVICES: Int = 4
}
