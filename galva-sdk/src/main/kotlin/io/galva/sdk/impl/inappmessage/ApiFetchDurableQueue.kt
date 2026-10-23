package io.galva.sdk.impl.inappmessage

import android.content.Context
import io.galva.common.logger.Logger
import io.galva.common.network.ExponentialBackoffRetryPolicy
import io.galva.common.utils.JsonUtils
import io.galva.common.utils.UUIDv7
import io.galva.network.request.APIFetchRequest
import io.galva.network.service.IAMService
import io.galva.network.service.ServiceResult
import io.galva.operation_queue.BatchSender
import io.galva.operation_queue.DefaultOperationQueue
import io.galva.operation_queue.OfflineToOnlineTrigger
import io.galva.operation_queue.OperationQueue
import io.galva.operation_queue.PermanentSendException
import io.galva.operation_queue.local.OperationsSqliteHelper
import io.galva.operation_queue.local.SqliteOperationStore
import io.galva.operation_queue.local.StoredOperation
import io.galva.operation_queue.networkgate.StreamNetworkGate
import io.galva.operation_queue.policy.SizeOrTimeoutBatchPolicy
import io.galva.sdk.impl.operation.DefaultNetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi

/**
 * Guaranteed delivery for `apiFetch` calls a bundle marks `shouldRetry`, like the iOS SDK's
 * `DurableRequestQueue`: requests are persisted in their own SQLite file, replayed one at a time
 * oldest-first, retried with backoff across network loss and app restarts, and capped at
 * [MAX_STORED] (oldest dropped first). 2xx is delivered; 408 / 429 / 5xx and transport errors are
 * retried; other responses are dropped.
 */
internal class ApiFetchDurableQueue(private val queue: OperationQueue) {

    fun start() = queue.start()

    /** Retry pending requests now, e.g. when the app returns to the foreground. */
    fun drain() = queue.flush()

    suspend fun enqueue(request: APIFetchRequest) {
        queue.enqueue(
            payload = JsonUtils.defaultJson.encodeToString(APIFetchRequest.serializer(), request),
            type = TYPE,
        )
    }

    companion object {
        private const val TYPE = "ApiFetch"
        private const val DB_NAME = "galva-proxy.db"
        const val MAX_STORED = 500

        @OptIn(ExperimentalUuidApi::class)
        fun create(
            context: Context,
            service: IAMService,
            scope: CoroutineScope,
            logger: Logger,
        ): ApiFetchDurableQueue {
            val networkMonitor = DefaultNetworkMonitor(context)
            val queue = DefaultOperationQueue(
                store = SqliteOperationStore(OperationsSqliteHelper(context, DB_NAME), logger),
                sender = ApiFetchReplaySender(service),
                gate = StreamNetworkGate(networkMonitor),
                // One request per batch, sent as soon as it is enqueued.
                batchPolicy = SizeOrTimeoutBatchPolicy(1, 1.seconds),
                retryPolicy = ExponentialBackoffRetryPolicy(base = 1.seconds, cap = 1.minutes),
                clock = { System.currentTimeMillis() },
                idGen = { UUIDv7.randomUUID().toString() },
                transitionTrigger = OfflineToOnlineTrigger(networkMonitor),
                scope = scope,
                logger = logger,
                maxStoredCount = MAX_STORED,
            )
            return ApiFetchDurableQueue(queue)
        }
    }
}

/** Replays one persisted apiFetch request and classifies the outcome for the queue. */
internal class ApiFetchReplaySender(private val service: IAMService) : BatchSender {
    override suspend fun send(batch: List<StoredOperation>): Result<Unit> {
        for (operation in batch) {
            val request = runCatching {
                JsonUtils.defaultJson.decodeFromString(APIFetchRequest.serializer(), operation.payload)
            }.getOrElse {
                return Result.failure(PermanentSendException("Stored apiFetch request cannot be decoded", it))
            }
            val failure = when (val result = service.apiFetch(request)) {
                is ServiceResult.Success -> when (val status = result.value.status) {
                    in 200..299 -> null
                    408, 429, in 500..599 -> IllegalStateException("HTTP $status")
                    else -> PermanentSendException("HTTP $status")
                }

                is ServiceResult.Error -> result.error
                is ServiceResult.Failure -> if (result.retryable) {
                    IllegalStateException("HTTP ${result.status}")
                } else {
                    PermanentSendException("HTTP ${result.status}")
                }

                is ServiceResult.UnknownError -> PermanentSendException("apiFetch failed", result.exception)
            }
            if (failure != null) return Result.failure(failure)
        }
        return Result.success(Unit)
    }
}
