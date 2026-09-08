package io.galva.iam.bundle

import io.galva.network.HttpClient
import io.galva.network.NetworkError
import io.galva.network.RequestBuilder
import io.galva.network.service.ServiceResult
import kotlin.time.Duration.Companion.seconds

class WebViewBundleDownloader(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) {
    suspend fun download(webviewVersion: String): ServiceResult<String> = try {
//        val fileName = "$webviewVersion.html"
        val fileName = "$webviewVersion.html"
        val request = RequestBuilder.get(baseUrl.plus(fileName))
            .timeout(30.seconds)
            .build()
        val response = httpClient.execute(request)

        if (response.isSuccessful) ServiceResult.Success(response.body)
        else ServiceResult.Failure(
            status = response.status,
            body = response.body,
            headers = response.headers,
            retryable = response.isRetryable,
        )
    } catch (e: NetworkError) {
        ServiceResult.Error(e)
    }
}