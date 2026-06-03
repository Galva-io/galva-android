package io.galva.sdk.impl.operation

import io.galva.common.logger.Logger
import io.galva.common.network.RetryAfterException
import io.galva.common.utils.DateTimeFormatUtils
import io.galva.common.utils.JsonUtils
import io.galva.network.request.BatchCollectRequest
import io.galva.network.request.messages.AliasMessage
import io.galva.network.request.messages.IdentityMessage
import io.galva.network.request.messages.TrackMessage
import io.galva.network.service.IdentifyService
import io.galva.network.service.ServiceResult
import io.galva.operation_queue.BatchSender
import io.galva.operation_queue.local.StoredOperation
import java.util.Calendar
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class DefaultBatchSender(private val service: IdentifyService,
    private val logger: Logger) : BatchSender {
    override suspend fun send(batch: List<StoredOperation>): Result<Unit> {
        val messages = batch.mapNotNull {operation ->
            logger.info {
                "Processing message with type=${operation.type} and payload=${operation.payload}"
            }
            when (operation.type) {
                "IdentityMessage" -> {
                    runCatching { JsonUtils.defaultJson.decodeFromString<IdentityMessage>(operation.payload).also {
                        logger.info {
                            "Decoded IdentityMessage: $it"
                        }
                    } }.onFailure {
                        logger.error {
                            "Failed to decode TrackMessage with payload: ${operation.payload}"
                        }
                    }.getOrNull()
                }

                "AliasMessage" -> {
                    runCatching { JsonUtils.defaultJson.decodeFromString<AliasMessage>(operation.payload).also {
                        logger.info {
                            "Decoded AliasMessage: $it"
                        }
                    } }.onFailure {
                        logger.error {
                            "Failed to decode TrackMessage with payload: ${operation.payload}"
                        }
                    }.getOrNull()
                }
                "TrackMessage" ->{
                    runCatching { JsonUtils.defaultJson.decodeFromString<TrackMessage>(operation.payload).also {
                        logger.info {
                            "Decoded TrackMessage: $it"
                        }
                    } }.onFailure {
                        logger.error {
                            "Failed to decode TrackMessage with payload: ${operation.payload}"
                        }
                    }.getOrNull()
                }

                else -> null

            }
        }

        if (messages.isEmpty()) {
            return Result.failure(IllegalArgumentException("Batch contains no valid messages"))
        }
        val batchCallResult = service.sendBatchCollectRequest(
            BatchCollectRequest(
                messages, sentAt = DateTimeFormatUtils.format(
                    Calendar.getInstance()
                )
            )
        )
        return when (batchCallResult) {
            is ServiceResult.Error -> Result.failure(
                batchCallResult.error.cause ?: Throwable("Unknown error")
            )

            is ServiceResult.Failure ->{

                if(batchCallResult.retryable){
                    val retryDuration = parseRetryAfter(batchCallResult)
                    Result.failure(RetryAfterException(retryDuration))
                }else{
                    Result.failure(IllegalStateException("HTTP ${batchCallResult.status}: ${batchCallResult.body}"))
                }
            }
            is ServiceResult.Success<Unit> -> Result.success(Unit)
            is ServiceResult.UnknownError -> Result.failure(
                batchCallResult.exception.cause ?: Throwable("Unknown error")
            )
        }
    }
    private fun parseRetryAfter(failure: ServiceResult.Failure): Duration? {
        val header = failure.headers["Retry-After"] ?: return null
        // Delta-seconds form: "120"
        return  header.toLongOrNull()?.let { return it.seconds }
    }
}