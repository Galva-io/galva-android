package io.galva.network.android

import io.galva.network.Endpoint
import io.galva.network.RequestBuilder
import io.galva.network.toUrl
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class OkHttpAPIClientTest {

    lateinit var server: MockWebServer
    @Before
    fun setup(){
         server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown(){
        server.shutdown()
    }
    @Test
    fun `requests include X-SDK-Version header`() = runTest {
        val client = OkHttpClientBuilder()
            .apiKey("k")
            .sdkVersion("1.2.3")
            .build()

        server.enqueue(MockResponse().setResponseCode(200))
        client.execute(RequestBuilder.get(server.url(Endpoint.IDENTIFY.toUrl("/batchCollect")).toString()).build())

        val recorded = server.takeRequest()
        assertEquals("1.2.3", recorded.getHeader("X-SDK-Version"))
    }
}