package space.k2p.data.api

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import space.k2p.BuildConfig
import space.k2p.core.config.AppConfig
import space.k2p.core.device.DeviceSecurity
import space.k2p.core.events.AuthEvent
import space.k2p.core.events.AuthEventBus
import space.k2p.core.vpn.VpnCoreProvider
import space.k2p.data.auth.TokenStorage
import java.util.concurrent.TimeUnit

class NetworkModule(
    private val context: Context,
    private val tokenStorage: TokenStorage
) {
    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        encodeDefaults = true
    }

    private val authHeadersInterceptor = Interceptor { chain ->
        val original = chain.request()
        val builder = original.newBuilder()

        // Pass HWID and Device Name headers on every request
        builder.header("X-Device-HWID", DeviceSecurity.getHardwareId(context))
        builder.header("X-Device-Name", DeviceSecurity.getDeviceName())

        // Pass Authorization if token is present
        val token = tokenStorage.getJwtSync()
        if (!token.isNullOrBlank() && original.header("Authorization") == null) {
            builder.header("Authorization", "Bearer $token")
        }

        chain.proceed(builder.build())
    }

    private val auth401Interceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())

        if (response.code == 401) {
            val peekedBody = try {
                response.peekBody(2048).string()
            } catch (_: Exception) {
                ""
            }

            val isDeviceRevoked = peekedBody.contains("DEVICE_REVOKED", ignoreCase = true)
            val isInvalidToken = peekedBody.contains("INVALID_TOKEN", ignoreCase = true)

            if (isDeviceRevoked || isInvalidToken) {
                // Immediately stop VPN and clear credentials
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { VpnCoreProvider.engine.stop() }
                    tokenStorage.clear()
                    val message = if (isDeviceRevoked) {
                        "Это устройство было отвязано от аккаунта"
                    } else {
                        "Сессия истекла. Пожалуйста, выполните вход повторно"
                    }
                    AuthEventBus.emit(AuthEvent.DeviceRevoked(message))
                }
            }
        }

        response
    }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BODY
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
    }

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(authHeadersInterceptor)
        .addInterceptor(auth401Interceptor)
        .addInterceptor(loggingInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(AppConfig.API_BASE_URL.removeSuffix("/") + "/")
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    val authApiService: AuthApiService = retrofit.create(AuthApiService::class.java)
    val clientApiService: ClientApiService = retrofit.create(ClientApiService::class.java)
    val paymentApiService: PaymentApiService = retrofit.create(PaymentApiService::class.java)
    val githubApiService: GithubApiService = retrofit.create(GithubApiService::class.java)
}
