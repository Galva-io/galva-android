package io.galva.sdk.impl.operation

import android.content.Context
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.galva.common.lifecycle.AppLifecycleObserver
import io.galva.common.lifecycle.AppLifecycleState
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
import io.galva.operation_queue.policy.BatchPolicy
import io.galva.sdk.impl.adsvertise.GMSAdvertisingProvider
import io.galva.sdk.impl.inappmessage.AndroidAppLifecycleObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class DefaultOperationManager internal constructor(
    private val lifecycleObserver: AppLifecycleObserver,
    val operationQueue: OperationQueue,
    private val converter: OperationRequestConverter,
    operationScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) : OperationManager {
    private fun foregroundTrigger(): Flow<AppLifecycleState> = callbackFlow {
        val subscriptionDeferred = async(Dispatchers.Main.immediate) {
            lifecycleObserver.observe {
                trySend(it)
            }
        }
        awaitClose {
            launch(Dispatchers.Main.immediate) {
                subscriptionDeferred.await().close()
            }
        }
    }

    init {
        operationScope.launch {
            foregroundTrigger().onStart {
                emit(AppLifecycleState.FOREGROUND) // trigger initial sync when start
            }.distinctUntilChanged().collectLatest {
                when (it) {
                    AppLifecycleState.FOREGROUND -> startRecordOperation()
                    AppLifecycleState.BACKGROUND -> stopRecordOperation()
                }
            }
        }

    }

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
            batchPolicy: BatchPolicy,
            lifecycleOwner: LifecycleOwner = ProcessLifecycleOwner.get()
        ): DefaultOperationManager {
            val helper = OperationsSqliteHelper(context)
            val operationStore = SqliteOperationStore(helper, logger)
            val batchSender: BatchSender = DefaultBatchSender(
                identifyService,
                logger
            )
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
            val lifecycleObserver = AndroidAppLifecycleObserver(lifecycleOwner)
            return DefaultOperationManager(
                lifecycleObserver, operationQueue, requestConverter,
                queueHandleScope
            )
        }
    }
}
