package io.galva.network.android

import io.galva.common.logger.LogLevel
import io.galva.common.logger.Logger
import io.galva.network.request.transactions.ObserveTransactionsRequest
import io.galva.network.request.transactions.ObservedTransaction
import io.galva.network.service.ServiceResult
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpAPITransactionServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var service: HttpAPITransactionService

    private val request = ObserveTransactionsRequest(
        anonymousId = "anonymous-123",
        endUserId = "user-123",
        transactions = listOf(ObservedTransaction("GPA.1234-5678-9012-34567", "google-play-purchase-token")),
    )

    @Before
    fun setup() {
        server = MockWebServer().also { it.start() }
        val client = OkHttpClientBuilder().apiKey("CLIENT_API_KEY").sdkVersion("android/1.0.0").build()
        val logger = Logger.create("Test", { _, _, _, _ -> }, level = LogLevel.WARN)
        service = HttpAPITransactionService(server.url("/").toString(), client, logger)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `posts the observed transactions to v1 transactions observe`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val result = service.observeTransactions(request)

        assertTrue(result is ServiceResult.Success)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/transactions/observe", recorded.path)
        assertEquals("CLIENT_API_KEY", recorded.getHeader("X-API-Key"))
        assertEquals("android/1.0.0", recorded.getHeader("x-sdk-version"))
        assertEquals(
            """{"anonymousId":"anonymous-123","endUserId":"user-123",""" +
                """"transactions":[{"orderId":"GPA.1234-5678-9012-34567","purchaseToken":"google-play-purchase-token"}]}""",
            recorded.body.readUtf8(),
        )
    }

    @Test
    fun `an anonymous user is sent with a null endUserId`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        service.observeTransactions(request.copy(endUserId = null))

        assertTrue(server.takeRequest().body.readUtf8().contains(""""endUserId":null"""))
    }

    @Test
    fun `a server error is a retryable Failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))

        val result = service.observeTransactions(request)

        assertTrue(result is ServiceResult.Failure && result.status == 503 && result.retryable)
    }

    @Test
    fun `a dropped connection is an Error, not thrown`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertTrue(service.observeTransactions(request) is ServiceResult.Error)
    }
}
