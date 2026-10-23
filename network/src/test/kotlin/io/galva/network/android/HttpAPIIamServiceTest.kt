package io.galva.network.android

import io.galva.common.logger.NoOpLogger
import io.galva.network.HttpMethod
import io.galva.network.ProxyUrlResolver
import io.galva.network.request.APIFetchRequest
import io.galva.network.response.APIFetchResult
import io.galva.network.service.ServiceResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpAPIIamServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var service: HttpAPIIamService

    @Before
    fun setup() {
        server = MockWebServer().also { it.start() }
        val client = OkHttpClientBuilder().apiKey("real-key").sdkVersion("android/1.0").build()
        service = HttpAPIIamService(server.url("/").toString(), client, NoOpLogger)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `http error responses are results the page can read`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json").setBody("""{"error":"nope"}"""))

        val result = service.apiFetch(APIFetchRequest("/identities/x", HttpMethod.GET)) as ServiceResult.Success<APIFetchResult>

        assertEquals(404, result.value.status)
        assertFalse(result.value.ok)
        assertEquals("json", result.value.bodyType)
        assertEquals(JsonPrimitive("nope"), result.value.body.jsonObject["error"])
        assertEquals("application/json", result.value.headers["content-type"])
    }

    @Test
    fun `non JSON bodies are returned as text`() = runTest {
        server.enqueue(MockResponse().setBody("plain"))

        val result = service.apiFetch(APIFetchRequest("x", HttpMethod.GET)) as ServiceResult.Success<APIFetchResult>

        assertEquals("text", result.value.bodyType)
        assertEquals(JsonPrimitive("plain"), result.value.body)
        assertEquals("/x", server.takeRequest().path)
    }

    @Test
    fun `bundle cannot override the SDK managed headers`() = runTest {
        server.enqueue(MockResponse())

        service.apiFetch(
            APIFetchRequest(
                path = "/x",
                method = HttpMethod.POST,
                body = "{}",
                headers = mapOf("x-api-key" to "stolen", "X-SDK-Version" to "fake", "X-Custom" to "ok"),
            )
        )

        val recorded = server.takeRequest()
        assertEquals(listOf("real-key"), recorded.headers.values("X-API-Key"))
        assertEquals(listOf("android/1.0"), recorded.headers.values("x-sdk-version"))
        assertEquals("ok", recorded.getHeader("X-Custom"))
    }

    @Test
    fun `transport failure is an error`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertTrue(service.apiFetch(APIFetchRequest("/x", HttpMethod.GET)) is ServiceResult.Error)
    }

    @Test
    fun `paths that leave the API origin are rejected`() {
        val base = "https://api.galva.io/"

        assertEquals("https://api.galva.io/identities/x", ProxyUrlResolver.resolve("identities/x", base))
        assertEquals("https://api.galva.io/identities/x?a=1", ProxyUrlResolver.resolve("/identities/x?a=1", base))
        assertNull(ProxyUrlResolver.resolve("https://evil.example/x", base))
        assertNull(ProxyUrlResolver.resolve("//evil.example/x", base))
        assertNull(ProxyUrlResolver.resolve(" ", base))
        assertFalse(service.isValidPath("https://evil.example/x"))
    }
}
