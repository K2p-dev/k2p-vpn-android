package space.k2p.data.api

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Url
import space.k2p.data.api.model.GithubReleaseModel

interface GithubApiService {
    @GET
    suspend fun getReleases(
        @Url url: String
    ): Response<List<GithubReleaseModel>>
}
