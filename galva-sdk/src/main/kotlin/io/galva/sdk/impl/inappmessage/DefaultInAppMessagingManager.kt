package io.galva.sdk.impl.inappmessage

import android.app.Activity
import android.content.Context
import androidx.annotation.VisibleForTesting
import io.galva.billing.BillingManager
import io.galva.common.lifecycle.AppLifecycleObserver
import io.galva.common.lifecycle.AppLifecycleState
import io.galva.common.logger.Logger

import io.galva.iam.InAppMessagingManager
import io.galva.iam.Message
import io.galva.iam.MessageOverlay
import io.galva.iam.WorkflowType
import io.galva.iam.bundle.WebViewBundleResolver
import io.galva.identity.IdentityManager
import io.galva.network.request.ChannelType
import io.galva.network.request.ListCommunicationRequest
import io.galva.network.request.ResolveMessageRequest
import io.galva.network.response.CommunicationResponse
import io.galva.network.response.MessageResponse
import io.galva.network.service.IdentifyService
import io.galva.network.service.ServiceResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * Polls for in-app messages and presents them with the iOS SDK's selection rules:
 *
 * - Polls only on "return events": cold start / every return to the foreground, and
 *   [checkForMessages]. No timer.
 * - Display budget: at most one message per return event. While a deep-link or programmatic
 *   presentation is resolving ([DisplaySlot.Pending]), or a message was already delivered or is
 *   still on screen ([DisplaySlot.Used]), the poll skips the network entirely.
 * - One request per poll; the server returns the highest-priority message first and the client
 *   never re-ranks. The first item not seen before wins; the others stay unmarked so they surface
 *   on a later return event.
 * - A message that went on screen is never delivered again this session, and a re-fired deep link
 *   to it stands down ([hasPresented]).
 */
internal class DefaultInAppMessagingManager private constructor(
    private val lifecycleObserver: AppLifecycleObserver,
    private val logger: Logger,
    private val identityService: IdentifyService,
    private val messageOverlay: MessageOverlay,
    private val identityManager: IdentityManager,
    private val billingManager: BillingManager,
    private val bundleResolver: WebViewBundleResolver,
    private val scope: CoroutineScope,
) : InAppMessagingManager {

    /** The display budget of the current return event. */
    sealed interface DisplaySlot {
        data object Available : DisplaySlot

        /** A deep-link / programmatic presentation is resolving; polling is suppressed. */
        data class Pending(val messageId: String) : DisplaySlot

        /** A message was delivered or presented; polling waits for the next return event. */
        data class Used(val messageId: String) : DisplaySlot
    }

    private val lock = Any()
    private var displaySlot: DisplaySlot = DisplaySlot.Available
    private var activeMessageId: String? = null

    /** Messages already delivered by a poll this session; bounded by [MAX_SEEN_IDS]. */
    private val seenIds = mutableSetOf<String>()

    /** Messages that went on screen this session. */
    private val presentedIds = mutableSetOf<String>()

    private val stream = MutableSharedFlow<Message>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val messages: Flow<Message> = stream.asSharedFlow()

    init {
        // ProcessLifecycleOwner must be observed from the main thread. Registering replays the
        // current state, so a process that is already in the foreground polls right away.
        scope.launch(Dispatchers.Main.immediate) {
            lifecycleObserver.observe { state ->
                if (state == AppLifecycleState.FOREGROUND) scope.launch { poll() }
            }
        }
    }

    override fun checkForMessages() {
        scope.launch { poll() }
    }

    override fun hasPresented(messageId: String): Boolean = synchronized(lock) { messageId in presentedIds }

    override fun reset() {
        synchronized(lock) {
            seenIds.clear()
            presentedIds.clear()
            activeMessageId = null
            displaySlot = DisplaySlot.Available
        }
    }

    // ── Polling ─────────────────────────────────────────────────────────────

    /** One poll for a return event. Returns the delivered message, if any. */
    @VisibleForTesting
    internal suspend fun poll(): Message? {
        val canFetch = synchronized(lock) {
            resetDisplaySlotIfStale()
            displaySlot == DisplaySlot.Available
        }
        if (!canFetch) {
            logger.debug { "In-app poll skipped — display slot busy" }
            return null
        }

        identityManager.awaitInitialized()
        val request = ListCommunicationRequest(
            ChannelType.IN_APP_MESSAGE, identityManager.anonymousId, identityManager.userId, cursor = null
        )
        val items = when (val result = identityService.fetchCommunications(request)) {
            is ServiceResult.Success<CommunicationResponse> -> result.value.data
            is ServiceResult.Failure -> {
                logger.warn { "In-app poll rejected: HTTP ${result.status}" }
                return null
            }

            is ServiceResult.Error -> {
                logger.info { "In-app poll skipped (offline?): ${result.error.message}" }
                return null
            }

            is ServiceResult.UnknownError -> {
                logger.warn(result.exception) { "In-app poll failed" }
                return null
            }
        }

        val message = synchronized(lock) {
            // A deep link may have claimed the slot while the request was in flight. Drop the
            // results unmarked so they surface again on the next return event.
            if (displaySlot != DisplaySlot.Available) {
                logger.info { "In-app poll dropped — display slot claimed mid-flight" }
                return null
            }
            val item = items.firstOrNull { it.id !in seenIds }
            if (item == null) {
                pruneSeenIfNeeded()
                return null
            }
            seenIds += item.id
            // Delivery consumes the budget even if the app never presents the message.
            displaySlot = DisplaySlot.Used(item.id)
            pruneSeenIfNeeded()
            Message(
                id = item.id,
                workflowType = WorkflowType.fromWireValue(item.workflowType),
                createdAt = item.createdAt,
            )
        }
        logger.debug { "In-app message delivered: ${message.id}" }
        stream.emit(message)
        return message
    }

    /** A return event starts a fresh budget unless something is pending or still on screen. */
    private fun resetDisplaySlotIfStale() {
        if (displaySlot is DisplaySlot.Used && activeMessageId == null) {
            displaySlot = DisplaySlot.Available
        }
    }

    private fun pruneSeenIfNeeded() {
        if (seenIds.size > MAX_SEEN_IDS) seenIds.clear()
    }

    // ── Presentation ────────────────────────────────────────────────────────

    override fun showMessage(context: Activity, message: Message) {
        showMessage(context as Context, message)
    }

    override fun showMessage(context: Context, message: Message) {
        showMessage(context, message, emptyMap())
    }

    override fun showMessage(context: Context, message: Message, deepLinkParameters: Map<String, String>) {
        // Claim the budget before any async work: polling stays suppressed for the whole flow,
        // so a deep link and a foreground poll can never present two messages at once.
        synchronized(lock) {
            if (activeMessageId == message.id || displaySlot == DisplaySlot.Pending(message.id)) {
                logger.debug { "In-app message ${message.id} is already being shown" }
                return
            }
            displaySlot = DisplaySlot.Pending(message.id)
        }
        // Hold the caller weakly: resolving can take a while and the activity may finish meanwhile.
        val contextRef = WeakReference(context)
        val appContext = context.applicationContext
        scope.launch {
            val presented = try {
                resolveAndPresent(contextRef, appContext, message, deepLinkParameters)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Failed to present message ${message.id}" }
                false
            }
            // Nothing was shown — restore the budget so the next poll may fetch again.
            if (!presented) releaseDisplaySlot(message.id)
        }
    }

    private suspend fun resolveAndPresent(
        contextRef: WeakReference<Context>,
        appContext: Context,
        message: Message,
        deepLinkParameters: Map<String, String>,
    ): Boolean {
        val anonymousId = identityManager.anonymousId
        val countryCode = billingManager.getStorefrontCountryCode()
            ?: appContext.resources.configuration.locales[0].country
        val resolveMessage = identityService.resolveMessage(
            ResolveMessageRequest(
                message.id, anonymousId, io.galva.sdk.iam.BuildConfig.JS_BRIDGE_VERSION,
                countryCode,
                identityManager.userId
            )
        )
        if (resolveMessage !is ServiceResult.Success<MessageResponse>) {
            logger.error("Failed to resolve message with id ${message.id}")
            return false
        }
        if (!resolveMessage.value.valid) {
            logger.info { "In-app message ${message.id} invalidated by server" }
            return false
        }
        return when (val outcome = bundleResolver.resolve(resolveMessage.value)) {
            is WebViewBundleResolver.Outcome.Failed -> {
                logger.error(outcome.cause) { "Failed to resolve bundle for message ${message.id}: ${outcome.reason}" }
                false
            }

            is WebViewBundleResolver.Outcome.Ready -> withContext(Dispatchers.Main.immediate) {
                val caller = contextRef.get()
                    ?.takeUnless { it is Activity && (it.isFinishing || it.isDestroyed) }
                messageOverlay.present(caller ?: appContext, resolveMessage.value, outcome.file, deepLinkParameters) {
                    onMessageDismissed(message.id)
                }
                setActiveMessageId(message.id)
                true
            }
        }
    }

    /** A presentation went live: it consumes the budget and is never delivered again this session. */
    private fun setActiveMessageId(messageId: String) = synchronized(lock) {
        activeMessageId = messageId
        displaySlot = DisplaySlot.Used(messageId)
        presentedIds += messageId
        seenIds += messageId
    }

    /** The budget stays spent after a dismiss; the next return event resets it. */
    private fun onMessageDismissed(messageId: String) = synchronized(lock) {
        if (activeMessageId == messageId) activeMessageId = null
    }

    private fun releaseDisplaySlot(messageId: String) = synchronized(lock) {
        when (val slot = displaySlot) {
            is DisplaySlot.Pending -> if (slot.messageId == messageId) displaySlot = DisplaySlot.Available
            is DisplaySlot.Used -> if (slot.messageId == messageId && activeMessageId == null) {
                displaySlot = DisplaySlot.Available
            }

            DisplaySlot.Available -> Unit
        }
    }

    companion object {
        private const val MAX_SEEN_IDS = 1024

        fun create(
            logger: Logger,
            identityService: IdentifyService,
            identityManager: IdentityManager,
            webViewBundleResolver: WebViewBundleResolver,
            messageOverlay: MessageOverlay,
            billingManager: BillingManager,
            appLifecycleObserver: AppLifecycleObserver,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ): DefaultInAppMessagingManager {
            return DefaultInAppMessagingManager(
                lifecycleObserver = appLifecycleObserver,
                logger = logger,
                identityService = identityService,
                messageOverlay = messageOverlay,
                identityManager = identityManager,
                scope = scope,
                bundleResolver = webViewBundleResolver,
                billingManager = billingManager
            )
        }
    }
}
