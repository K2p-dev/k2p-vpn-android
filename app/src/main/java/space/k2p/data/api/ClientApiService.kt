package space.k2p.data.api

import retrofit2.Response
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Path
import space.k2p.data.api.model.ClientConfigResponse
import space.k2p.data.api.model.DeleteDeviceResponse
import space.k2p.data.api.model.DevicesListResponse
import space.k2p.data.api.model.UserProfileResponse

interface ClientApiService {
    @GET("api/client/profile")
    suspend fun getProfile(): Response<UserProfileResponse>

    @GET("api/client/devices")
    suspend fun getDevices(): Response<DevicesListResponse>

    @DELETE("api/client/devices/{hwid}")
    suspend fun deleteDevice(
        @Path("hwid") hwid: String
    ): Response<DeleteDeviceResponse>

    @GET("api/client/config")
    suspend fun getConfig(): Response<ClientConfigResponse>
}
