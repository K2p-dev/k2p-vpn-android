package space.k2p.core.events

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed class AuthEvent {
    data class DeviceRevoked(val message: String = "Это устройство было отвязано от аккаунта") : AuthEvent()
    data object LoggedOut : AuthEvent()
}

object AuthEventBus {
    private val _events = MutableSharedFlow<AuthEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<AuthEvent> = _events.asSharedFlow()

    suspend fun emit(event: AuthEvent) {
        _events.emit(event)
    }

    fun tryEmit(event: AuthEvent) {
        _events.tryEmit(event)
    }
}
