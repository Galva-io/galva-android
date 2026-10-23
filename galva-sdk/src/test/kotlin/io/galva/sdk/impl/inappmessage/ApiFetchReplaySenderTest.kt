package io.galva.sdk.impl.inappmessage

import io.galva.common.utils.JsonUtils
import io.galva.network.HttpMethod
import io.galva.network.NetworkError
import io.galva.network.request.APIFetchRequest
import io.galva.network.response.APIFetchResult
import io.galva.network.service.IAMService
import io.galva.network.service.ServiceResult
import io.galva.operation_queue.PermanentSendException
import io.galva.operation_queue.local.StoredOperation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiFetchReplaySenderTest {

    private class FakeIamService(var result: ServiceResult<APIFetchResult>) : IAMService {
        val requests = mutableListOf<APIFetchRequest>()
        override fun isValidPath(path: String) = true
        override suspend fun apiFetch(apiFetchRequest: APIFetchRequest): ServiceResult<APIFetchResult> {
            requests += apiFetchRequest
            return result
        }
    }

    private val request = APIFetchRequest("/identities/x", HttpMethod.POST, "{}", mapOf("A" to "b"), shouldRetry = true)
    private val stored = StoredOperation(
        id = "1",
        type = "ApiFetch",
        payload = JsonUtils.defaultJson.encodeToString(APIFetchRequest.serializer(), request),
        createdAt = 0L,
    )

    private fun http(status: Int) =
        ServiceResult.Success(APIFetchResult(status, status in 200..299, emptyMap(), JsonPrimitive(""), "text"))

    private suspend fun send(result: ServiceResult<APIFetchResult>): Result<Unit> {
        val service = FakeIamService(result)
        return ApiFetchReplaySender(service).send(listOf(stored)).also {
            assertEquals(listOf(request), service.requests)
        }
    }

    @Test
    fun `2xx is delivered`() = runTest {
        assertTrue(send(http(204)).isSuccess)
    }

    @Test
    fun `408, 429, 5xx and transport errors are retried`() = runTest {
        for (result in listOf(http(408), http(429), http(503), ServiceResult.Error(NetworkError.Timeout()))) {
            val error = send(result).exceptionOrNull()
            assertTrue("$result should fail", error != null)
            assertFalse("$result should be retryable", error is PermanentSendException)
        }
    }

    @Test
    fun `other client errors are dropped`() = runTest {
        assertTrue(send(http(400)).exceptionOrNull() is PermanentSendException)
        assertTrue(send(http(404)).exceptionOrNull() is PermanentSendException)
    }

    @Test
    fun `undecodable rows are dropped`() = runTest {
        val result = ApiFetchReplaySender(FakeIamService(http(200))).send(listOf(stored.copy(payload = "{")))

        assertTrue(result.exceptionOrNull() is PermanentSendException)
    }
}
