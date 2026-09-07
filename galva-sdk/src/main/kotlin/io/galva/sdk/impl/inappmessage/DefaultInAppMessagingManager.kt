package io.galva.sdk.impl.inappmessage

import android.app.Activity
import android.content.Context
import io.galva.billing.BillingManager
import io.galva.common.lifecycle.AppLifecycleObserver
import io.galva.common.lifecycle.AppLifecycleState
import io.galva.common.logger.Logger

import io.galva.iam.InAppMessagingManager
import io.galva.iam.Message
import io.galva.iam.MessageOverlay
import io.galva.iam.bundle.WebViewBundleResolver
import io.galva.identity.IdentityManager
import io.galva.network.request.ChannelType
import io.galva.network.request.ListCommunicationRequest
import io.galva.network.request.ResolveMessageRequest
import io.galva.network.response.Communication
import io.galva.network.response.CommunicationResponse
import io.galva.network.response.MessageResponse
import io.galva.network.service.IdentifyService
import io.galva.network.service.ServiceResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class DefaultInAppMessagingManager private constructor(
    private val lifecycleObserver: AppLifecycleObserver,
    private val pollInterval: Duration = 1.minutes,
    private val logger: Logger,
    private val identityService: IdentifyService,
    private val messageOverlay: MessageOverlay,
    private val identityManager: IdentityManager,
    private val billingManager: BillingManager,
    private val bundleResolver: WebViewBundleResolver,
    private val dataResolveScope: CoroutineScope,
) : InAppMessagingManager {
    private val activeFetchJobsLock = Any()
    private val activeFetchJobs = mutableSetOf<Job>()
    private val resumeFetchEvents = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private fun foregroundTrigger(): Flow<Unit> = callbackFlow {
        val subscriptionDeferred = async(Dispatchers.Main.immediate) {
            lifecycleObserver.observe(onEvent = {
                when(it) {
                    AppLifecycleState.FOREGROUND -> trySend(Unit)
                    AppLifecycleState.BACKGROUND -> {}
                }
            })
        }
        awaitClose {
            launch(Dispatchers.Main.immediate) {
                subscriptionDeferred.await().close()
            }
        }
    }

    /** Long-poll trigger flow — emits Unit every [pollInterval]. */
    private fun pollTrigger(): Flow<Unit> = flow {
        while (currentCoroutineContext().isActive) {
            delay(pollInterval)
            emit(Unit)
        }
    }

    override val messages: Flow<Message> = merge(
        foregroundTrigger().onStart {
            emit(Unit)
        },
        pollTrigger(),
        resumeFetchEvents,
    ).mapNotNull {
        fetchMessage()
    }.distinctUntilChanged()

    override fun showMessage(context: Activity, message: Message) {
        showMessage(context as Context, message)
    }

    override fun showMessage(context: Context, message: Message) {
        dataResolveScope.launch(Dispatchers.IO) {
            val anonymousId = identityManager.anonymousId
            val countryCode = billingManager.getStorefrontCountryCode() ?: context.resources.configuration.locales[0].country
            val resolveMessage = identityService.resolveMessage(
                ResolveMessageRequest(
                    message.id, anonymousId, io.galva.sdk.iam.BuildConfig.JS_BRIDGE_VERSION,
                    countryCode,
                    identityManager.userId
                )
            )
            if (resolveMessage is ServiceResult.Success<MessageResponse>) {
                when (val outcome = bundleResolver.resolve(resolveMessage.value)) {
                    is WebViewBundleResolver.Outcome.Failed -> {
                        logger.error(outcome.cause) { "Failed to resolve bundle for message ${message.id}: ${outcome.reason}" }
                    }
                    is WebViewBundleResolver.Outcome.Ready -> {
                        withContext(Dispatchers.Main.immediate) {
                            messageOverlay.present(context, resolveMessage.value,outcome.file, onDismiss = {

                            })
                        }
                    }
                }

            } else {
                logger.error("Failed to resolve message with id ${message.id}")
            }
        }

    }

    override fun cancelFetchMessage() {
        val jobs = synchronized(activeFetchJobsLock) {
            activeFetchJobs.toList()
        }
        jobs.forEach { job ->
            job.cancel(CancellationException("Cancelled to handle a deeplink"))
        }
    }

    override fun resumeFetchMessage() {
        resumeFetchEvents.tryEmit(Unit)
    }

    private suspend fun fetchMessage(): Message? = supervisorScope {
        val fetchJob = async(start = CoroutineStart.LAZY) {
            fetchMessageData()
        }
        synchronized(activeFetchJobsLock) {
            activeFetchJobs.add(fetchJob)
        }

        try {
            fetchJob.start()
            fetchJob.await()
        } catch (error: CancellationException) {
            if (!currentCoroutineContext().isActive) throw error
            logger.debug { "In-app message fetch cancelled" }
            null
        } finally {
            synchronized(activeFetchJobsLock) {
                activeFetchJobs.remove(fetchJob)
            }
        }
    }

    private suspend fun fetchMessageData(): Message? {
        // suspend until we have an anonymousId, which is required for the list communication request
        identityManager.awaitInitialized()
        var cursor: String? = null
        var hasMore = true
        val anonymousId = identityManager.anonymousId
        val messages = mutableListOf<Communication>()
        while (hasMore) {
            val request =
                ListCommunicationRequest(ChannelType.IN_APP_MESSAGE, anonymousId, identityManager.userId,cursor, 20)
            logger.error {
                "Fetching messages with request: anonymousId=${request.anonymousId}, cursor=${request.cursor}, limit=${request.limit}, channelType=${request.channelType}"
            }
            when (val serviceResult = identityService.fetchCommunications(request)) {
                is ServiceResult.Success<CommunicationResponse> -> {
                    logger.info { "Fetched ${serviceResult.value.data.size} messages" }
                    messages.addAll(serviceResult.value.data)
                    hasMore = serviceResult.value.meta.nextCursor != null
                    cursor = serviceResult.value.meta.nextCursor
                }

                is ServiceResult.Error -> {
                    hasMore = false
                    logger.error { "Message fetch failed with message: ${serviceResult.error.message}" }
                }

                is ServiceResult.Failure -> {
                    hasMore = false
                    logger.error { "Message fetch rejected: HTTP ${serviceResult.status}" }
                }

                is ServiceResult.UnknownError -> {
                    hasMore = false
                    logger.error {
                        "Message fetch failed with unknown error: ${serviceResult.exception.message}"
                    }
                }
            }
        }
        messages.sortBy {
            it.createdAt
        }
        return messages.firstOrNull()?.run {
            Message(id = id)
        }
    }

    companion object {

        fun create(
            logger: Logger,
            identityService: IdentifyService,
            identityManager: IdentityManager,
            webViewBundleResolver: WebViewBundleResolver,
            messageOverlay: MessageOverlay,
            billingManager: BillingManager,
            appLifecycleObserver: AppLifecycleObserver
        ): DefaultInAppMessagingManager {
            return DefaultInAppMessagingManager(
                lifecycleObserver = appLifecycleObserver,
                logger = logger,
                identityService = identityService,
                messageOverlay = messageOverlay,
                identityManager = identityManager,
                dataResolveScope = CoroutineScope(Dispatchers.IO),
                bundleResolver = webViewBundleResolver,
                billingManager = billingManager
            )
        }
    }
}
