package space.k2p.data.api

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Url
import space.k2p.data.api.model.GithubReleaseDto

interface GithubApiService {
    @GET
    suspend fun getLatestRelease(
        @Url url: String
    ): Response<GithubReleaseDto>
}
