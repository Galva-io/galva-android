package io.galva.sdk.impl.operation

import io.galva.common.logger.Logger
import io.galva.common.network.RetryAfterException
import io.galva.common.utils.DateTimeFormatUtils
import io.galva.common.utils.JsonUtils
import io.galva.network.request.BatchCollectRequest
import io.galva.network.request.messages.AliasMessage
import io.galva.network.request.messages.BatchMessage
import io.galva.network.request.messages.CreateCommunicationEndpointMessage
import io.galva.network.request.messages.DeleteCommunicationEndpointMessage
import io.galva.network.request.messages.IdentityMessage
import io.galva.network.request.messages.TrackMessage
import io.galva.network.service.IdentifyService
import io.galva.network.service.ServiceResult
import io.galva.operation_queue.BatchSender
import io.galva.operation_queue.PermanentSendException
import io.galva.operation_queue.local.StoredOperation
import kotlinx.serialization.KSerializer
import java.util.Calendar
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Uploads a batch to `identities/batchCollect` and classifies the outcome for the queue:
 *
 * - 2xx → success
 * - 408, 429, 5xx, transport errors → retryable
 * - other 4xx, encoding errors, a batch with nothing sendable → [PermanentSendException]
 *   (retrying can never succeed and would block every operation behind it)
 */
internal class DefaultBatchSender(
    private val service: IdentifyService,
    private val logger: Logger,
) : BatchSender {
    override suspend fun send(batch: List<StoredOperation>): Result<Unit> {
        val messages = batch.mapNotNull(::decode)
        if (messages.isEmpty()) {
            return Result.failure(PermanentSendException("Batch contains no valid messages"))
        }
        val batchCallResult = service.sendBatchCollectRequest(
            BatchCollectRequest(
                messages, sentAt = DateTimeFormatUtils.format(
                    Calendar.getInstance()
                )
            )
        )
        return when (batchCallResult) {
            is ServiceResult.Success<Unit> -> Result.success(Unit)

            is ServiceResult.Failure -> if (batchCallResult.retryable) {
                Result.failure(RetryAfterException(parseRetryAfter(batchCallResult)))
            } else {
                Result.failure(
                    PermanentSendException("HTTP ${batchCallResult.status}: ${batchCallResult.body}")
                )
            }

            is ServiceResult.Error -> Result.failure(batchCallResult.error)

            is ServiceResult.UnknownError -> Result.failure(
                PermanentSendException("Batch could not be sent", batchCallResult.exception)
            )
        }
    }

    private fun decode(operation: StoredOperation): BatchMessage? {
        val serializer: KSerializer<out BatchMessage> = when (operation.type) {
            "IdentityMessage" -> IdentityMessage.serializer()
            "AliasMessage" -> AliasMessage.serializer()
            "TrackMessage" -> TrackMessage.serializer()
            "CreateCommunicationEndpointMessage" -> CreateCommunicationEndpointMessage.serializer()
            "DeleteCommunicationEndpointMessage" -> DeleteCommunicationEndpointMessage.serializer()
            else -> {
                logger.warn { "Dropping operation ${operation.id}: unsupported type ${operation.type}" }
                return null
            }
        }
        return runCatching { JsonUtils.defaultJson.decodeFromString(serializer, operation.payload) }
            .onFailure { logger.error(it) { "Dropping operation ${operation.id}: cannot decode ${operation.type}" } }
            .getOrNull()
    }

    private fun parseRetryAfter(failure: ServiceResult.Failure): Duration? {
        val header = failure.headers["Retry-After"] ?: failure.headers["retry-after"] ?: return null
        // Delta-seconds form: "120"
        return header.trim().toLongOrNull()?.seconds
    }
}
