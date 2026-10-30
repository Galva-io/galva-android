package io.galva.network.android

import io.galva.common.logger.LogLevel
import io.galva.common.logger.Logger
import io.galva.common.utils.JsonUtils
import io.galva.network.HttpClient
import io.galva.network.HttpMethod
import io.galva.network.NetworkError
import io.galva.network.request.BatchCollectRequest
import io.galva.network.request.messages.IdentityMessage
import io.galva.network.service.IdentifyService
import io.galva.network.service.ServiceResult
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpAPIIdentifyServiceTest {
    lateinit var server: MockWebServer
    lateinit var httpAPIIdentifyService: IdentifyService
    lateinit var client: HttpClient
    lateinit var baseURL: String
    lateinit var logger: Logger

    @Before
    fun setup() {
        server = MockWebServer().also { it.start() }
        baseURL = server.url("/").toString()
        client = OkHttpClientBuilder().apiKey("k").sdkVersion("1.2.3").build()
        logger = Logger.create("Test", { level, tag, message, throwable ->
            println("[$level] $tag: $message ${throwable?.message}")
        }, level = LogLevel.WARN)
        httpAPIIdentifyService = HttpAPIIdentifyService(baseURL, client, logger = logger)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `identify sends POST to v1 identify with serialized body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val result = callAPI()
        val recorded = server.takeRequest()
        assertTrue(result is ServiceResult.Success)
        assertEquals(HttpMethod.POST.name, recorded.method)
        assertTrue(
            recorded.requestUrl?.toUrl()
                .toString() == "${baseURL.removeSuffix("/")}/identities/batchCollect"
        )
    }

    @Test
    fun `identify maps 4xx to Failure with retryable false`() = runTest {

        server.enqueue(MockResponse().setResponseCode(400))
        val result = callAPI()
        val failure = result as ServiceResult.Failure
        assertEquals(400, failure.status)
        assertFalse(failure.retryable)
    }

    @Test
    fun `identify maps 503 to Failure with retryable true`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        val result = callAPI()
        val failure = result as ServiceResult.Failure
        assertTrue(failure.retryable)
    }

    @Test
    fun `identify maps Timeout error to Error`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val result = callAPI()
        val err = result as ServiceResult.Error
        assertTrue(err.error is NetworkError.Timeout)
    }

    @Test
    fun `identify maps unknown host exception to error`() = runTest {
        val service = HttpAPIIdentifyService("http://localserver", client, logger = logger)
        val result = service.sendBatchCollectRequest(
            BatchCollectRequest(
                messages = listOf(
                    IdentityMessage(
                        timestamp = "test",
                        anonymousId = "test",
                    )
                ), sentAt = "now"
            )
        )
        val err = result as ServiceResult.Error
        assertTrue(err.error is NetworkError.NoConnectivity)
    }

    @Test
    fun `identify invalid base url should return unknown error`() = runTest {
        println(JsonUtils.defaultJson.encodeToString(BatchCollectRequest.serializer(),BatchCollectRequest(
            messages = listOf(
                IdentityMessage(
                    timestamp = "test",
                    anonymousId = "test",
                )
            ), sentAt = "now"
        )))
        val service = HttpAPIIdentifyService("", client,  logger = logger)
        val result = service.sendBatchCollectRequest(
            BatchCollectRequest(
                messages = listOf(
                    IdentityMessage(
                        timestamp = "test",
                        anonymousId = "test",
                    )
                ), sentAt = "now"
            )
        )

        assertTrue(result is ServiceResult.UnknownError)
    }

    private suspend fun callAPI(): ServiceResult<Unit> {
        val result = httpAPIIdentifyService.sendBatchCollectRequest(
            BatchCollectRequest(
                messages = listOf(
                    IdentityMessage(
                        timestamp = "test",
                        anonymousId = "test",
                    )
                ), sentAt = "now",
            )
        )
        return result
    }
}