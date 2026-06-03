package io.galva.sdk.impl.operation

import android.content.Context
import io.galva.common.logger.Logger
import io.galva.common.network.ExponentialBackoffRetryPolicy
import io.galva.common.utils.AdvertisingIdRetriever
import io.galva.common.utils.JsonUtils
import io.galva.common.utils.UUIDv7
import io.galva.core.protocol.operation.APIOperation
import io.galva.core.protocol.operation.OperationManager
import io.galva.core.protocol.operation.OperationRequestConverter
import io.galva.network.service.IdentifyService
import io.galva.operation_queue.BatchSender
import io.galva.operation_queue.DefaultOperationQueue
import io.galva.operation_queue.OfflineToOnlineTrigger
import io.galva.operation_queue.OperationQueue
import io.galva.operation_queue.local.OperationsSqliteHelper
import io.galva.operation_queue.local.SqliteOperationStore
import io.galva.operation_queue.networkgate.StreamNetworkGate
import io.galva.operation_queue.policy.SizeOrTimeoutBatchPolicy
import io.galva.sdk.impl.adsvertise.GMSAdvertisingProvider
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class DefaultOperationManager internal constructor(
    val operationQueue: OperationQueue, private val converter: OperationRequestConverter
) : OperationManager {
    override suspend fun recordOperation(apiOperation: APIOperation) {
        val batchMessage = converter.operationToBatchMessage(apiOperation)
        val payload = JsonUtils.defaultJson.encodeToString(batchMessage)
        operationQueue.enqueue(payload, apiOperation.opType, UUIDv7.randomUUID().toString())
    }

    override fun stopRecordOperation() {
        operationQueue.stop()
    }

    override fun startRecordOperation() {
        operationQueue.start()
    }

    override suspend fun clearAllOperation() {
        operationQueue.clear()
    }

    companion object {
        fun create(
            context: Context,
            identifyService: IdentifyService,
            queueHandleScope: CoroutineScope,
            logger: Logger,
            maxBatchSize: Int = 10,
            maxBatchWaitTime: kotlin.time.Duration = 10.seconds
        ): DefaultOperationManager {
            val helper = OperationsSqliteHelper(context)
            val operationStore = SqliteOperationStore(helper, logger)
            val batchSender: BatchSender = DefaultBatchSender(
                identifyService,
                logger
            )
            val batchPolicy = SizeOrTimeoutBatchPolicy(maxBatchSize, maxBatchWaitTime)
            val networkMonitor = DefaultNetworkMonitor(context)
            val requestConverter = DefaultOperationRequestConverter(
                context, GMSAdvertisingProvider(
                    AdvertisingIdRetriever(context, queueHandleScope)
                )
            )
            val operationQueue = DefaultOperationQueue(
                store = operationStore,
                sender = batchSender,
                gate = StreamNetworkGate(networkMonitor),
                batchPolicy = batchPolicy,
                retryPolicy = ExponentialBackoffRetryPolicy(base = 1.minutes),
                clock = { System.currentTimeMillis() },
                idGen = {
                    UUIDv7.randomUUID().toString()
                },
                logger = logger,
                transitionTrigger = OfflineToOnlineTrigger(networkMonitor),
                scope = queueHandleScope,
            )
            return DefaultOperationManager(operationQueue, requestConverter)
        }
    }
}
