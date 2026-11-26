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
import space.k2p.core.logging.AppLogger
import space.k2p.core.logging.LogTag
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
        val host = original.url.host

        // Attach Authorization and device headers ONLY to our own backend domain
        if (host.endsWith("proksbro.space")) {
            val builder = original.newBuilder()

            // Pass HWID and Device Name headers on every request to our API
            builder.header("X-Device-HWID", DeviceSecurity.getHardwareId(context))
            builder.header("X-Device-Name", DeviceSecurity.getDeviceName())

            // Pass Authorization if token is present
            val token = tokenStorage.getJwtSync()
            if (!token.isNullOrBlank() && original.header("Authorization") == null) {
                builder.header("Authorization", "Bearer $token")
            }

            chain.proceed(builder.build())
        } else {
            chain.proceed(original)
        }
    }

    private val auth401Interceptor = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)

        if (response.code == 401 && request.url.host.endsWith("proksbro.space")) {
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

    private val apiLoggerInterceptor = Interceptor { chain ->
        val request = chain.request()
        val hasAuth = !request.header("Authorization").isNullOrBlank()
        val authMask = if (hasAuth) " [Auth: Bearer ***]" else ""
        val urlStr = request.url.toString().replace(Regex("token=[^&]+"), "token=***")
        val startMs = System.currentTimeMillis()
        try {
            val response = chain.proceed(request)
            val elapsed = System.currentTimeMillis() - startMs
            val msg = "--> ${request.method} $urlStr$authMask | <-- HTTP ${response.code} (${elapsed} мс)"
            if (response.isSuccessful) {
                AppLogger.d(LogTag.API, msg)
            } else {
                AppLogger.w(LogTag.API, msg)
            }
            response
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startMs
            AppLogger.e(LogTag.API, "--> ${request.method} $urlStr$authMask | ОШИБКА (${elapsed} мс): ${e.message}", e)
            throw e
        }
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
        .addInterceptor(apiLoggerInterceptor)
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

    // Separate clean OkHttpClient and Retrofit for GitHub API without auth headers
    val githubOkHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "K2P-VPN-Android")
                .build()
            chain.proceed(request)
        }
        .addInterceptor(apiLoggerInterceptor)
        .addInterceptor(loggingInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val githubRetrofit: Retrofit = Retrofit.Builder()
        .baseUrl("https://api.github.com/")
        .client(githubOkHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    val githubApiService: GithubApiService = githubRetrofit.create(GithubApiService::class.java)
}
