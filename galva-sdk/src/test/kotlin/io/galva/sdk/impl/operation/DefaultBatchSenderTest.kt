package io.galva.sdk.impl.operation

import io.galva.common.logger.NoOpLogger
import io.galva.common.network.RetryAfterException
import io.galva.common.utils.JsonUtils
import io.galva.network.HttpHeaders
import io.galva.network.NetworkError
import io.galva.network.request.BatchCollectRequest
import io.galva.network.request.ListCommunicationRequest
import io.galva.network.request.ResolveMessageRequest
import io.galva.network.request.messages.IdentityMessage
import io.galva.network.response.CommunicationResponse
import io.galva.network.response.MessageResponse
import io.galva.network.service.IdentifyService
import io.galva.network.service.ServiceResult
import io.galva.operation_queue.PermanentSendException
import io.galva.operation_queue.local.StoredOperation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class DefaultBatchSenderTest {

    private class FakeIdentifyService(var result: ServiceResult<Unit>) : IdentifyService {
        val sent = mutableListOf<BatchCollectRequest>()

        override suspend fun sendBatchCollectRequest(request: BatchCollectRequest): ServiceResult<Unit> {
            sent += request
            return result
        }

        override suspend fun fetchCommunications(request: ListCommunicationRequest): ServiceResult<CommunicationResponse> =
            error("not used")

        override suspend fun resolveMessage(request: ResolveMessageRequest): ServiceResult<MessageResponse> =
            error("not used")
    }

    private val identityOp = StoredOperation(
        id = "op-1",
        type = "IdentityMessage",
        payload = JsonUtils.defaultJson.encodeToString(
            IdentityMessage.serializer(),
            IdentityMessage(timestamp = "2026-10-23T00:00:00Z", anonymousId = "anon-1"),
        ),
        createdAt = 0L,
    )

    private fun failure(status: Int, headers: HttpHeaders = HttpHeaders.Builder().build()) =
        ServiceResult.Failure(status = status, body = "", headers = headers, retryable = status == 408 || status == 429 || status >= 500)

    @Test
    fun `2xx is a success`() = runTest {
        val service = FakeIdentifyService(ServiceResult.Success(Unit))

        val result = DefaultBatchSender(service, NoOpLogger).send(listOf(identityOp))

        assertTrue(result.isSuccess)
        assertEquals(1, service.sent.single().messages.size)
    }

    @Test
    fun `non-retryable 4xx is permanent`() = runTest {
        val result = DefaultBatchSender(FakeIdentifyService(failure(400)), NoOpLogger).send(listOf(identityOp))

        assertTrue(result.exceptionOrNull() is PermanentSendException)
    }

    @Test
    fun `5xx is retryable`() = runTest {
        val result = DefaultBatchSender(FakeIdentifyService(failure(503)), NoOpLogger).send(listOf(identityOp))

        val error = result.exceptionOrNull()
        assertTrue(error is RetryAfterException)
        assertFalse(error is PermanentSendException)
    }

    @Test
    fun `429 carries Retry-After`() = runTest {
        val headers = HttpHeaders.Builder().add("Retry-After", "120").build()

        val error = DefaultBatchSender(FakeIdentifyService(failure(429, headers)), NoOpLogger)
            .send(listOf(identityOp)).exceptionOrNull()

        assertEquals(120.seconds, (error as RetryAfterException).retryAfter)
    }

    @Test
    fun `transport error is retryable`() = runTest {
        val service = FakeIdentifyService(ServiceResult.Error(NetworkError.NoConnectivity()))

        val error = DefaultBatchSender(service, NoOpLogger).send(listOf(identityOp)).exceptionOrNull()

        assertTrue(error is NetworkError)
    }

    @Test
    fun `batch with only unsupported or undecodable operations is permanent and not sent`() = runTest {
        val service = FakeIdentifyService(ServiceResult.Success(Unit))
        val batch = listOf(
            identityOp.copy(id = "unknown", type = "SomethingNew"),
            identityOp.copy(id = "broken", payload = "{not json"),
        )

        val result = DefaultBatchSender(service, NoOpLogger).send(batch)

        assertTrue(result.exceptionOrNull() is PermanentSendException)
        assertTrue(service.sent.isEmpty())
    }
}
