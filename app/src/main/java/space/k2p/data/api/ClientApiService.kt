package space.k2p.data.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import space.k2p.data.api.model.ActivateEmergencyTransportResponse
import space.k2p.data.api.model.ClientConfigResponse
import space.k2p.data.api.model.DeleteDeviceResponse
import space.k2p.data.api.model.DevicesListResponse
import space.k2p.data.api.model.EmergencyTransportResponse
import space.k2p.data.api.model.SaveEmergencyTransportRequest
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

    @GET("api/client/emergency-transport")
    suspend fun getEmergencyTransport(): Response<EmergencyTransportResponse>

    @POST("api/client/emergency-transport")
    suspend fun saveEmergencyTransport(
        @Body request: SaveEmergencyTransportRequest
    ): Response<EmergencyTransportResponse>

    @DELETE("api/client/emergency-transport")
    suspend fun deleteEmergencyTransport(): Response<EmergencyTransportResponse>

    @POST("api/client/emergency-transport/activate")
    suspend fun activateEmergencyTransport(): Response<ActivateEmergencyTransportResponse>
}
