package space.k2p

import android.app.Application
import space.k2p.core.preferences.SplitTunnelingPreferences
import space.k2p.core.preferences.SubscriptionPreferences
import space.k2p.core.vpn.RealVpnCoreEngine
import space.k2p.core.vpn.VpnCoreProvider
import space.k2p.data.api.NetworkModule
import space.k2p.data.auth.TokenStorage
import space.k2p.data.repository.AppUpdateRepository
import space.k2p.data.repository.AuthRepository
import space.k2p.data.repository.ClientRepository
import space.k2p.data.repository.PaymentRepository
import space.k2p.data.repository.SubscriptionRepository

class K2pApp : Application() {
    lateinit var tokenStorage: TokenStorage
        private set

    lateinit var splitTunnelingPreferences: SplitTunnelingPreferences
        private set

    lateinit var geodataPreferences: space.k2p.core.preferences.GeodataPreferences
        private set

    lateinit var networkModule: NetworkModule
        private set

    lateinit var authRepository: AuthRepository
        private set

    lateinit var clientRepository: ClientRepository
        private set

    lateinit var paymentRepository: PaymentRepository
        private set

    lateinit var appUpdateRepository: AppUpdateRepository
        private set

    lateinit var subscriptionPreferences: SubscriptionPreferences
        private set

    lateinit var subscriptionRepository: SubscriptionRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        tokenStorage = TokenStorage(this)
        splitTunnelingPreferences = SplitTunnelingPreferences(this)
        geodataPreferences = space.k2p.core.preferences.GeodataPreferences(this)
        subscriptionPreferences = SubscriptionPreferences(this)
        networkModule = NetworkModule(this, tokenStorage)

        // Initialize geodata databases from assets if missing or corrupted
        space.k2p.core.geodata.GeodataManager.initGeodata(this)

        // Initialize core environment for ping measurements & VPN
        runCatching { libv2ray.Libv2ray.initCoreEnv(filesDir.absolutePath, "") }

        // Create notification channels
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(android.app.NotificationManager::class.java)
            val updateChannel = android.app.NotificationChannel(
                "k2p_updates",
                "Обновления «Как 2 пальца»",
                android.app.NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Уведомления о новых версиях и обновлениях базы геоданных"
            }
            notificationManager?.createNotificationChannel(updateChannel)
        }

        // Initialize real VPN core engine
        VpnCoreProvider.engine = space.k2p.core.vpn.RealVpnCoreEngine(this)

        authRepository = AuthRepository(
            context = this,
            apiService = networkModule.authApiService,
            tokenStorage = tokenStorage,
            json = networkModule.json
        )
        clientRepository = ClientRepository(
            context = this,
            apiService = networkModule.clientApiService,
            json = networkModule.json
        )
        paymentRepository = PaymentRepository(
            apiService = networkModule.paymentApiService,
            json = networkModule.json
        )
        appUpdateRepository = AppUpdateRepository(
            githubApiService = networkModule.githubApiService
        )
        subscriptionRepository = SubscriptionRepository(
            context = this,
            clientApiService = networkModule.clientApiService,
            okHttpClient = networkModule.okHttpClient,
            json = networkModule.json,
            subscriptionPreferences = subscriptionPreferences
        )

        // Schedule periodic 24h background workers for geodata and app updates
        space.k2p.core.workers.WorkScheduler.schedulePeriodicTasks(this)
    }

    companion object {
        lateinit var instance: K2pApp
            private set
    }
}
