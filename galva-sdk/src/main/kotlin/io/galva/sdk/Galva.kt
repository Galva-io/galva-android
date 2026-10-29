package io.galva.sdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.galva.billing.BillingManager
import io.galva.common.lifecycle.AppLifecycleObserver
import io.galva.common.lifecycle.AppLifecycleState
import io.galva.common.logger.LogLevel as InternalLogLevel
import io.galva.common.logger.Logger
import io.galva.common.utils.AppInfoSource
import io.galva.common.utils.EmailValidator
import io.galva.common.utils.NotificationUtils
import io.galva.common.utils.PlayBillingVersion
import io.galva.core.protocol.operation.APIOperation
import io.galva.core.protocol.operation.OperationManager
import io.galva.iam.InAppMessagingManager
import io.galva.iam.bundle.WebViewBundleCache
import io.galva.iam.bundle.WebViewBundleDownloader
import io.galva.iam.bundle.WebViewBundleResolver
import io.galva.identity.Identity
import io.galva.identity.IdentityManager
import io.galva.localstorage.KeyValueStorageFactory
import io.galva.network.android.HttpAPIIamService
import io.galva.network.android.HttpAPIIdentifyService
import io.galva.network.android.HttpAPISDKService
import io.galva.network.android.HttpAPITransactionService
import io.galva.network.android.OkHttpClientBuilder
import io.galva.network.service.IAMService
import io.galva.operation_queue.policy.SizeOrTimeoutBatchPolicy
import io.galva.sdk.impl.billing.DefaultBillingManager
import io.galva.sdk.impl.billing.DeferredBillingManager
import io.galva.sdk.impl.billing.GalvaProductIdSource
import io.galva.sdk.impl.billing.PlayPurchases
import io.galva.sdk.impl.billing.PlayTransaction
import io.galva.sdk.impl.billing.UnavailableBillingManager
import io.galva.sdk.impl.deeplink.DeeplinkOpenCommunicationRouterHandler
import io.galva.sdk.impl.event.EventAttributes
import io.galva.sdk.impl.deeplink.DeeplinkRouter
import io.galva.sdk.impl.deeplink.LocalPendingDeeplinkStore
import io.galva.sdk.impl.identity.DefaultIdentityManager
import io.galva.sdk.impl.inappmessage.AndroidAppLifecycleObserver
import io.galva.sdk.impl.inappmessage.ApiFetchDurableQueue
import io.galva.sdk.impl.inappmessage.DefaultInAppMessagingManager
import io.galva.sdk.impl.inappmessage.InAppMessageAutoDisplay
import io.galva.sdk.impl.inappmessage.NotificationEvents
import io.galva.sdk.impl.inappmessage.ScreenMessageOverlay
import io.galva.sdk.impl.inappmessage.SdkNotificationHandler
import io.galva.sdk.impl.integration.PrefixedIntegrationLogger
import io.galva.sdk.impl.operation.DefaultOperationManager
import io.galva.sdk.impl.session.SdkSettings
import io.galva.sdk.impl.session.SessionTracker
import io.galva.sdk.impl.store.SdkInitializeConfigStore
import io.galva.sdk.impl.transaction.TransactionObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Entry point of the Galva SDK. Configure once with [Galva.configure], then use [Galva.instance].
 * Every call returns immediately; the work runs on a background queue in call order.
 */
public class Galva @VisibleForTesting internal constructor(
    internal val logger: Logger,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    /** SDK work never crashes the app: anything that escapes a coroutine is logged and dropped. */
    private val galvaScope: CoroutineScope = scope + CoroutineExceptionHandler { _, t ->
        logger.error(t) { "Unexpected SDK error (ignored)" }
    }

    @Volatile
    private var config: Configuration? = null

    @Volatile
    private var identityManager: IdentityManager? = null

    @Volatile
    private var _operationManager: OperationManager? = null

    @Volatile
    private var _inAppMessageManager: InAppMessagingManager? = null

    @Volatile
    private var _sdkNotificationHandler: SdkNotificationHandler? = null

    @Volatile
    private var _deeplinkRouter: DeeplinkRouter? = null

    @Volatile
    private var _iamService: IAMService? = null

    @Volatile
    private var _apiFetchQueue: ApiFetchDurableQueue? = null

    @Volatile
    private var settings: SdkSettings? = null

    @Volatile
    private var sessionTracker: SessionTracker? = null

    @Volatile
    private var _purchaseController: GalvaPurchaseController? = null

    @Volatile
    private var _transactionObserver: TransactionObserver? = null

    /** Null when Play Billing is unavailable: Galva can't read the account's purchases then. */
    @Volatile
    private var _playPurchases: PlayPurchases? = null

    /** Runs the purchases in-app messages request; null for the built-in Play Billing flow. */
    internal val purchaseController: GalvaPurchaseController? get() = _purchaseController

    /** Latest [setOptOut] value; null until called, then [isOptedOut] reads the persisted flag. */
    @Volatile
    private var optOutOverride: Boolean? = null

    /** Completes once the components built in the background by [configure] are available. */
    private val ready = CompletableDeferred<Unit>()

    private val billingManagerReady = CompletableDeferred<BillingManager>()

    /**
     * Opt-out state in call order, used to gate tracking. It only changes when the SDK queue
     * processes a [setOptOut], so `setOptOut(true); identify(x); setOptOut(false)` still drops x.
     * Touched only on the SDK queue (and by configure before the queue starts).
     */
    @Volatile
    private var trackingOptedOut = false

    private val channel = Channel<GalvaEvent>(Channel.UNLIMITED)

    public val isConfigured: Boolean get() = config != null


    /**
     * Play Billing catalog and purchases used by in-app messages. Usable right after [configure];
     * calls are forwarded once the Play billing client has been set up in the background.
     */
    internal val billingManager: BillingManager by lazy { DeferredBillingManager(billingManagerReady, galvaScope) }

    /**
     * Runs the purchases in-app messages request through [controller] instead of the SDK's
     * built-in Play Billing flow; null restores the built-in flow.
     *
     * Safe to call at any time, before or after [configure]; it applies to the next purchase a
     * message requests. The last controller set wins, including one an integration installs
     * (e.g. `GalvaIntegration.revenueCat()`), so don't combine the two.
     */
    public fun setPurchaseController(controller: GalvaPurchaseController?) {
        _purchaseController = controller
        logger.info { "Purchase controller set: ${controller?.javaClass?.name ?: "built-in Play Billing"}" }
    }

    /**
     * Reports the device's newest Play purchase to Galva now, instead of on the next app entry.
     *
     * Galva links each Play purchase to the user who made it. It reports purchases made in its
     * in-app messages right away, and the account's purchases every time the app comes to the
     * foreground. Call this right after your own billing code (your paywall, another billing SDK)
     * completes a purchase, or from a "Restore purchases" action, so Galva doesn't wait for the
     * next app entry.
     *
     * Returns immediately; safe to call at any time and more than once. Nothing is sent while the
     * user is opted out.
     */
    public fun reconcileTransactions() {
        // Queued like identify / logout, so the report goes to the user they leave in place.
        channel.trySend(GalvaEvent.ReconcileTransactions)
    }

    /** A purchase completed in an in-app message, through Galva's or the app's billing client. */
    internal fun reportTransaction(transaction: GalvaTransaction) {
        val observer = _transactionObserver ?: return
        val purchase = PlayTransaction(transaction.orderId, transaction.purchaseToken, transaction.purchaseTime, purchased = true)
        galvaScope.launch { observer.onTransactionCompleted(purchase) }
    }

    /** apiFetch proxy used by in-app message bundles; null until the SDK is set up. */
    internal val iamService: IAMService? get() = _iamService

    /** Persistent queue for apiFetch calls marked `shouldRetry`; null until the SDK is set up. */
    internal val apiFetchQueue: ApiFetchDurableQueue? get() = _apiFetchQueue

    private fun startProcessingGalvaEvents() {
        galvaScope.launch(Dispatchers.IO) {
            for (event in channel) {
                try {
                    process(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    logger.error(t) { "${event::class.simpleName} failed (ignored)" }
                }
            }
        }
    }

    private suspend fun process(event: GalvaEvent) {
        when (event) {
            is GalvaEvent.Identify -> identifyInternal(
                event.userId, event.email, event.obfuscatedAccountId
            )

            is GalvaEvent.SetPushToken -> setPushTokenInternal(event.token, event.provider)
            is GalvaEvent.ClearPushToken -> clearPushTokenInternal()
            GalvaEvent.ReconcileTransactions -> sweepTransactions()
            is GalvaEvent.UpdateProperties -> updatePropertiesInternal(event.properties)
            is GalvaEvent.TrackNotification -> try {
                trackInternal(event.eventName, event.properties)
            } finally {
                // A BroadcastReceiver waits on it: release it even when recording fails.
                event.onRecorded?.invoke()
            }

            is GalvaEvent.Track -> trackInternal(event.eventName, event.properties)
            is GalvaEvent.SetOptOut -> setOptOutInternal(event.optedOut)
            GalvaEvent.Foreground -> {
                sessionTracker?.handleForeground()
                sweepTransactions()
            }

            is GalvaEvent.HandleOpenURL -> _deeplinkRouter?.handle(event.uri)
            GalvaEvent.ProcessPendingDeeplink -> _deeplinkRouter?.handlePending()

            GalvaEvent.Logout -> logoutInternal()
        }
    }

    /**
     * Keeps the main thread to what has to run there — remembering the configuration and
     * registering lifecycle observers — and builds everything else in the background (creating the
     * Play billing client, storage and SQLite helpers is expensive), like the iOS SDK. Calls made
     * meanwhile are queued or wait for [ready].
     */
    @Synchronized
    @JvmName("configureInternal")
    private fun configure(context: Context, configuration: Configuration, integrations: List<GalvaIntegration>) {
        if (isConfigured) {
            logger.warn("Galva.configure() called twice — ignoring subsequent call.")
            return
        }
        if (configuration.apiKey.isBlank()) {
            logger.error { "Galva.configure() ignored: apiKey is blank. Galva stays disabled." }
            return
        }
        try {
            configureOnMainThread(context, configuration, integrations)
        } catch (t: Throwable) {
            config = null
            logger.error(t) { "Galva.configure() failed; Galva stays disabled" }
        }
    }

    private fun configureOnMainThread(
        context: Context,
        configuration: Configuration,
        integrations: List<GalvaIntegration>,
    ) {
        // Only the application context is kept: the SDK outlives any activity.
        val appContext = context.applicationContext ?: context
        this.config = configuration
        configuration.logSink?.let { logger.setSink(it.toInternal()) }
        logger.setLogLevel(configuration.logLevel.toInternal())
        logger.setEnableLogging(configuration.logLevel != LogLevel.NONE)
        // SharedPreferences load on their own thread; nothing is read here.
        settings = SdkSettings(appContext)

        val appLifecycle: AppLifecycleObserver = AndroidAppLifecycleObserver()
        // Registered now so the first resumed activity is known when messages arrive.
        val autoDisplay = if (configuration.autoDisplayInAppMessages) {
            val application = appContext as? Application
            if (application == null) logger.warn { "autoDisplayInAppMessages needs an Application context; disabled" }
            application?.let { InAppMessageAutoDisplay(it, galvaScope, logger).also(InAppMessageAutoDisplay::register) }
        } else {
            null
        }
        appLifecycle.observe { event ->
            when (event) {
                AppLifecycleState.BACKGROUND ->
                    updateProperties(ProfileProperty.LastActiveTime(System.currentTimeMillis()))

                AppLifecycleState.FOREGROUND -> {
                    channel.trySend(GalvaEvent.Foreground)
                    _apiFetchQueue?.drain()
                }
            }
        }
        galvaScope.launch(Dispatchers.IO) {
            try {
                setUp(appContext, configuration, appLifecycle, autoDisplay, integrations)
            } catch (t: Throwable) {
                logger.error(t) { "Galva setup failed — SDK calls are ignored" }
            }
        }
        logger.info { "Galva configured (env=${configuration.env})" }
    }

    /** Builds the SDK components off the main thread, then starts processing queued calls. */
    private suspend fun setUp(
        appContext: Context,
        configuration: Configuration,
        appLifecycle: AppLifecycleObserver,
        autoDisplay: InAppMessageAutoDisplay?,
        integrations: List<GalvaIntegration>,
    ) {
        val billingSupported = PlayBillingVersion.isSupported
        if (!billingSupported) {
            logger.error {
                val found = if (PlayBillingVersion.isPresent) "an older Play Billing Library" else "no Play Billing Library"
                "Galva SDK requires Play Billing 8.0+, but the app has $found; purchases in messages will fail. " +
                    "Add to your build.gradle.kts: implementation(\"com.android.billingclient:billing-ktx:8.0.0\")"
            }
        }
        val settings = settings ?: return
        trackingOptedOut = settings.optedOut

        _sdkNotificationHandler = SdkNotificationHandler(appContext, logger)
        val keyValueStorage = KeyValueStorageFactory.create(appContext, logger = logger)
        val identity = DefaultIdentityManager.create(keyValueStorage, logger).also { identityManager = it }
        val httpClient = OkHttpClientBuilder().apiKey(configuration.apiKey)
            .sdkVersion("android/$sdkVersion").logger(logger).build()
        val identifyService = HttpAPIIdentifyService(
            baseURL = configuration.env.baseAPIUrl,
            httpClient = httpClient,
            logger = logger,
        )

        val bundleCache = WebViewBundleCache(File(appContext.filesDir, "webview_bundles"))
        val bundleDownloader = WebViewBundleDownloader(
            httpClient, configuration.env.baseWebviewUrl
        )
        val webViewBundleResolver = WebViewBundleResolver(bundleCache, bundleDownloader, logger)
        val iamService = HttpAPIIamService(configuration.env.baseAPIUrl, httpClient, logger)
        _iamService = iamService
        // Started right away so requests left over from a previous launch are retried now.
        _apiFetchQueue = ApiFetchDurableQueue.create(appContext, iamService, galvaScope, logger).apply { start() }

        val sdkInitClient = HttpAPISDKService(
            baseURL = configuration.env.baseAPIUrl, httpClient, logger
        )
        val batchPolicy = SizeOrTimeoutBatchPolicy(10, 10.seconds)
        val configStore = SdkInitializeConfigStore(sdkInitClient, keyValueStorage = keyValueStorage, logger = logger)
        val billing = if (billingSupported) {
            DefaultBillingManager.create(appContext, logger, GalvaProductIdSource(configStore), galvaScope)
                .apply { initialize() }
        } else {
            UnavailableBillingManager()
        }
        billingManagerReady.complete(billing)
        val transactionObserver = TransactionObserver(
            service = HttpAPITransactionService(configuration.env.baseAPIUrl, httpClient, logger),
            identity = { identity.current },
            isOptedOut = { trackingOptedOut },
            logger = logger,
        ).also { _transactionObserver = it }
        (billing as? DefaultBillingManager)?.purchases?.let { purchases ->
            _playPurchases = purchases
            galvaScope.launch { purchases.updates.collect { transactionObserver.onPurchasesUpdated(it) } }
        }
        val inAppMessagingManager = DefaultInAppMessagingManager.create(
            logger = logger,
            identityService = identifyService,
            identityManager = identity,
            webViewBundleResolver = webViewBundleResolver,
            messageOverlay = ScreenMessageOverlay(),
            billingManager = billing,
            appLifecycleObserver = appLifecycle,
            scope = galvaScope,
        )
        _inAppMessageManager = inAppMessagingManager
        autoDisplay?.start(inAppMessagingManager)
        _deeplinkRouter = DeeplinkRouter(
            handlers = listOf(
                DeeplinkOpenCommunicationRouterHandler(
                    context = appContext,
                    identityManager = identity,
                    inAppMessagingManager = inAppMessagingManager,
                )
            ),
            pendingDeeplinkStore = LocalPendingDeeplinkStore(keyValueStorage),
            logger = logger,
        )
        _operationManager = DefaultOperationManager.create(
            appContext, identifyService, galvaScope, logger, batchPolicy, appLifecycle
        )
        if (configuration.autoTrackSessions) {
            sessionTracker = SessionTracker(
                lastSessionStart = settings,
                logger = logger,
                isOptedOut = { trackingOptedOut },
                sessionProperties = { sessionProperties(appContext) },
                track = { event, properties -> trackInternal(event, properties) },
            )
        }
        ready.complete(Unit)

        identity.initialize()
        if (identity.current.firstCreated) {
            record(
                APIOperation.CreateAnonymousId(
                    identity.current.anonymousId, identity.current.obfuscatedAccountId
                )
            )
        }
        startProcessingGalvaEvents()
        channel.trySend(GalvaEvent.ProcessPendingDeeplink)
        installIntegrations(integrations)

        // The SDK works from here on; a failure below only delays config, catalog or bundles.
        try {
            warmUp(configStore, billing, batchPolicy, webViewBundleResolver)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger.warn(t) { "Background warm-up failed; retried on the next launch" }
        }
    }

    /** Fetches the remote config, the product catalog and the message bundles. */
    private suspend fun warmUp(
        configStore: SdkInitializeConfigStore,
        billing: BillingManager,
        batchPolicy: SizeOrTimeoutBatchPolicy,
        webViewBundleResolver: WebViewBundleResolver,
    ) {
        val config = configStore.loadAndSaveConfig()
        billing.loadProducts()
        val maxBatchSize = max(config?.data?.batchCollection?.flushSize ?: 10, 10)
        val maxBatchWaitingTime = maxOf(
            config?.data?.batchCollection?.flushIntervalMs?.milliseconds ?: 10_000.milliseconds,
            10.seconds
        )
        batchPolicy.update(maxBatchSize, maxBatchWaitingTime)
        coroutineScope {
            config?.data?.webviewVersions?.map { webviewVersion ->
                async(Dispatchers.IO) {
                    webViewBundleResolver.doDownload(webviewVersion)
                }
            }?.awaitAll()
        }
    }

    /** Runs [block] now when the SDK is set up, otherwise once it is. */
    private fun whenReady(block: () -> Unit) {
        val guarded = {
            try {
                block()
            } catch (t: Throwable) {
                logger.error(t) { "Unexpected SDK error (ignored)" }
            }
        }
        if (ready.isCompleted) {
            guarded()
        } else {
            galvaScope.launch(Dispatchers.Main) {
                ready.await()
                guarded()
            }
        }
    }

    /**
     * Installs each integration once (the first of a name wins), each on its own coroutine so a
     * slow or failing one never holds up the others or the SDK.
     */
    @OptIn(GalvaIntegrationApi::class)
    @VisibleForTesting
    internal fun installIntegrations(integrations: List<GalvaIntegration>) {
        integrations.distinctBy { it.name }.forEach { integration ->
            galvaScope.launch(Dispatchers.IO) {
                logger.info { "Installing integration ${integration.name} ${integration.version}" }
                try {
                    integration.install(
                        IntegrationContext(PrefixedIntegrationLogger(integration.name, logger), galvaScope)
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    logger.error(t) { "Integration ${integration.name} failed to install" }
                }
            }
        }
    }

    /** Records [operation] unless the user opted out of tracking. */
    private suspend fun record(operation: APIOperation) {
        if (trackingOptedOut) {
            logger.debug { "${operation::class.simpleName} dropped (opted out)" }
            return
        }
        _operationManager?.recordOperation(operation)
    }

    private suspend fun trackInternal(event: String, properties: JsonObject?) {
        val identity = identityManager ?: return
        record(APIOperation.Track(identity.current.anonymousId, identity.current.userId, event, properties))
    }

    /** `session_start` properties, same keys as the iOS SDK. */
    private fun sessionProperties(context: Context): JsonObject = buildJsonObject {
        put("device_locale", Locale.getDefault().toString())
        put("os_version", Build.VERSION.RELEASE)
        AppInfoSource.read(context)?.version?.let { put("app_version", it) }
        put("sdk_version", sdkVersion)
    }

    // ── Events ──────────────────────────────────────────────────────────────

    /**
     * Tracks an event, like the iOS SDK's `AppEvents.track`. Attributes are a loose map: values
     * that are not JSON-compatible are silently dropped (strings, booleans, numbers, null, dates,
     * URIs, UUIDs, enums and nested maps / collections of those are kept). Events are persisted
     * and uploaded in batches; they are dropped while the user is opted out.
     */
    @JvmOverloads
    public fun track(eventName: String, attributes: Map<String, Any?>? = null) {
        // An empty name would get the whole batch rejected by the backend.
        if (eventName.isBlank()) {
            logger.warn { "track ignored — blank event name" }
            return
        }
        val properties = attributes?.let(EventAttributes::coerce)?.takeIf { it.isNotEmpty() }
        channel.trySend(GalvaEvent.Track(eventName, properties))
    }

    /** Tracks a strongly-typed [AppEvent]. */
    public fun track(event: AppEvent) {
        track(event.eventName, event.attributes)
    }

    // ── Identity ────────────────────────────────────────────────────────────

    /**
     * Identifies the current user. An invalid [email] is dropped (the rest of the call still
     * applies), mirroring the iOS SDK's client-side validation.
     */
    @JvmOverloads
    public fun identify(userId: String, email: String? = null, obfuscatedAccountId: String? = null) {
        channel.trySend(GalvaEvent.Identify(userId, email, obfuscatedAccountId))
    }

    private suspend fun identifyInternal(
        userId: String, requestedEmail: String? = null, obfuscatedAccountId: String? = null
    ) {
        if (trackingOptedOut) {
            logger.debug { "identify dropped (opted out)" }
            return
        }
        val identity = identityManager ?: return
        val email = requestedEmail?.takeIf(EmailValidator::isValid)
        if (requestedEmail != null && email == null) logger.warn { "identify — dropped invalid email" }
        identity.identify(userId, email, obfuscatedAccountId)
        logger.info { "Identified user $userId" }
        record(
            APIOperation.Identify(
                anonymousId = identity.current.anonymousId,
                userId = userId,
                obfuscatedAccountId = obfuscatedAccountId,
                email = email
            )
        )
        _deeplinkRouter?.handlePending()
    }

    /** Sets profile properties on the current user. An invalid email property is dropped. */
    public fun updateProperties(vararg properties: ProfileProperty) {
        channel.trySend(GalvaEvent.UpdateProperties(properties.toList()))
    }

    private suspend fun updatePropertiesInternal(properties: List<ProfileProperty>) {
        if (trackingOptedOut) {
            logger.debug { "updateProperties dropped (opted out)" }
            return
        }
        val valid = properties.map { it.key to it.toJson() }.filterNot { (key, value) ->
            val email = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            (key == EMAIL_TRAIT && email != null && !EmailValidator.isValid(email)).also { invalid ->
                if (invalid) logger.warn { "updateProperties — dropped invalid $EMAIL_TRAIT" }
            }
        }
        if (valid.isEmpty()) return
        val identity = identityManager ?: return
        val propertyObject = JsonObject(valid.toMap())
        identity.updateUserProperties(propertyObject)
        record(
            APIOperation.UpdateUserProperties(
                identity.current.anonymousId, propertyObject
            )
        )
    }

    public fun logout() {
        channel.trySend(GalvaEvent.Logout)
    }

    /** On every app entry and [reconcileTransactions]: reports the account's newest Play purchase, off the event queue. */
    private fun sweepTransactions() {
        val observer = _transactionObserver ?: return
        val purchases = _playPurchases ?: return
        galvaScope.launch { observer.sweep(purchases) }
    }

    private suspend fun logoutInternal() {
        val identity = identityManager ?: return
        // The signed-out user's undelivered operations stay queued: each was recorded complete
        // (anonymous id, user id, context), so it is sent as that user alongside the next user's.
        // The endpoint deletion is recorded the same way, before the switch.
        clearPushTokenInternal()
        identity.logout()
        // Seen / presented messages belonged to the signed-out user.
        _inAppMessageManager?.reset()
        // The new anonymous user reports the device's purchases again on the next app entry.
        _transactionObserver?.reset()
        logger.info { "Logged out; now anonymous as ${identity.current.anonymousId}" }
        record(
            APIOperation.CreateAnonymousId(
                identity.current.anonymousId, identity.current.obfuscatedAccountId
            )
        )
    }

    /** Current identity; empty while the SDK is still loading it after [configure]. */
    private val currentIdentity: Identity
        get() = identityManager?.current ?: Identity.empty()

    /** Your user id when identified, otherwise the anonymous id; empty until the SDK has loaded it. */
    public val currentUserId: String
        get() = currentIdentity.let { it.userId ?: it.anonymousId }

    public val obfuscatedAccountId: String
        get() = currentIdentity.obfuscatedAccountId

    public val isAnonymous: Boolean
        get() = currentIdentity.userId.isNullOrEmpty()

    // ── Privacy ─────────────────────────────────────────────────────────────

    /**
     * Turns server-bound tracking off (or back on), like the iOS SDK. While opted out, identify,
     * profile properties, push token registration, push / session events are dropped, and the
     * pending event queue is purged when switching to opted out. In-app messages keep working.
     * The choice is persisted across launches.
     */
    public fun setOptOut(optedOut: Boolean) {
        optOutOverride = optedOut
        channel.trySend(GalvaEvent.SetOptOut(optedOut))
    }

    /** Current opt-out state; safe to read from any thread. */
    public val isOptedOut: Boolean get() = optOutOverride ?: settings?.optedOut ?: false

    private suspend fun setOptOutInternal(optedOut: Boolean) {
        val settings = settings ?: return
        val wasOptedOut = trackingOptedOut
        settings.optedOut = optedOut
        trackingOptedOut = optedOut
        if (optedOut && !wasOptedOut) {
            // Nothing recorded before opting out may leave the device afterwards.
            _operationManager?.clearAllOperation()
            logger.info { "Opted out — pending events purged" }
        } else if (!optedOut && wasOptedOut) {
            logger.info { "Opted back in" }
        }
    }

    // ── Push ────────────────────────────────────────────────────────────────

    /**
     * Registers this device's push token, issued by [provider] (FCM unless the device lacks Google Play
     * services). Call it from the provider's new-token callback and with the current token on start-up.
     */
    @JvmOverloads
    public fun setPushToken(token: String, provider: PushProvider = PushProvider.FCM) {
        channel.trySend(GalvaEvent.SetPushToken(token, provider))
    }

    private suspend fun setPushTokenInternal(token: String, provider: PushProvider) {
        if (trackingOptedOut) {
            logger.debug { "setPushToken dropped (opted out)" }
            return
        }
        if (token.isBlank()) {
            // HMS getToken returns "" when the token is delivered later through onNewToken.
            logger.warn { "setPushToken ignored: the ${provider.name} token is empty" }
            return
        }
        val identity = identityManager ?: return
        val registered = identity.current
        val registeredToken = registered.pushToken
        if (registeredToken == token && registered.registeredPushPlatform == provider.platform) {
            logger.debug { "Push token unchanged — nothing to register" }
            return
        }
        if (!registeredToken.isNullOrEmpty()) {
            logger.info { "Push token changed; replacing the registered one" }
            clearInvalidPushTokenInternal(registeredToken, registered.registeredPushPlatform)
        } else {
            logger.info { "Push token registered (${provider.name})" }
        }
        identity.setPushToken(token, provider.platform)
        record(
            APIOperation.SetPushToken(
                anonymousId = identity.current.anonymousId, token = token, platform = provider.platform
            )
        )
    }

    private suspend fun clearInvalidPushTokenInternal(pushToken: String, platform: String) {
        val identity = identityManager ?: return
        identity.clearPushToken()
        record(
            APIOperation.ClearPushToken(
                anonymousId = identity.current.anonymousId, token = pushToken, platform = platform
            )
        )
    }

    public fun clearPushToken() {
        channel.trySend(GalvaEvent.ClearPushToken)
    }

    private suspend fun clearPushTokenInternal() {
        val registered = identityManager?.current ?: return
        val currentToken = registered.pushToken ?: return
        clearInvalidPushTokenInternal(currentToken, registered.registeredPushPlatform)
    }

    // Tokens stored before providers existed carry no platform; they were all FCM.
    private val Identity.registeredPushPlatform: String
        get() = pushPlatform ?: PushProvider.FCM.platform

    public fun isGalvaNotification(message: Map<String, String>): Boolean =
        NotificationUtils.isCommunicationNotification(message)

    /**
     * Shows a Galva push notification; call from `onMessageReceived`. `$gv_notification_presented`
     * is tracked only when the notification was actually posted (not when notifications are off).
     */
    public fun handlePush(message: Map<String, String>) {
        whenReady {
            if (_sdkNotificationHandler?.handleNotification(message) == true) {
                trackNotificationEvent(NotificationEvents.PRESENTED, message)
            }
        }
    }

    /**
     * Tracks a notification interaction like the iOS SDK: a regular track event whose attributes
     * are the push data plus `id`. [onRecorded] runs once it is persisted (or dropped while opted
     * out), so a BroadcastReceiver can finish its `goAsync()` work.
     */
    internal fun trackNotificationEvent(eventName: String, payload: Map<String, String>, onRecorded: (() -> Unit)? = null) {
        val properties = EventAttributes.coerce(NotificationEvents.attributes(payload))
        if (channel.trySend(GalvaEvent.TrackNotification(eventName, properties, onRecorded)).isFailure) {
            onRecorded?.invoke()
        }
    }

    // ── In-app messages ─────────────────────────────────────────────────────

    /**
     * Messages published by the SDK: at most one per return to the foreground (or
     * [checkForMessages]). Collect while your UI is visible and pass each to [showMessage]. From
     * Java, or without coroutines, use [observeInAppMessages]; or set
     * `Configuration(autoDisplayInAppMessages = true)` instead.
     */
    public fun getInAppMessage(): Flow<Message> = flow {
        ready.await()
        _inAppMessageManager?.let { manager -> emitAll(manager.messages.map { it.toPublic() }) }
    }

    /**
     * Calls [listener] on the main thread with each message published while [owner] is resumed,
     * typically to pass it to [showMessage]; stops for good when [owner] is destroyed. The callback
     * counterpart of [getInAppMessage], for Java and for code without coroutines:
     *
     * ```java
     * Galva.getInstance().observeInAppMessages(this, message -> Galva.getInstance().showMessage(this, message));
     * ```
     */
    public fun observeInAppMessages(owner: LifecycleOwner, listener: InAppMessageListener) {
        observe(owner, getInAppMessage(), listener)
    }

    @VisibleForTesting
    internal fun observe(owner: LifecycleOwner, messages: Flow<Message>, listener: InAppMessageListener) {
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                messages.collect { message ->
                    try {
                        listener.onInAppMessage(message)
                    } catch (e: Exception) {
                        // An app bug: keep delivering the next messages.
                        logger.error(e) { "InAppMessageListener failed on message ${message.id}" }
                    }
                }
            }
        }
    }

    public fun showMessage(activity: Activity, message: Message) {
        whenReady { _inAppMessageManager?.showMessage(activity, message.toInternal()) }
    }

    /**
     * Polls for an in-app message now. Polling already happens on every return to the foreground;
     * use this after a user action that should trigger a workflow.
     */
    public fun checkForMessages() {
        whenReady { _inAppMessageManager?.checkForMessages() }
    }

    /**
     * Hands an opened URI to Galva. Returns true when it is a Galva link (scheme starting with
     * `gv`), which the SDK then routes; any other URI returns false and is left untouched, so you
     * can route it yourself.
     */
    public fun handleOpenURL(uri: Uri): Boolean {
        if (uri.scheme?.startsWith(GALVA_SCHEME_PREFIX, ignoreCase = true) != true) return false
        channel.trySend(GalvaEvent.HandleOpenURL(uri))
        return true
    }

    // ── Logging ─────────────────────────────────────────────────────────────

    public fun setLogLevel(level: LogLevel) {
        logger.setLogLevel(level.toInternal())
    }

    public fun setEnableLogging(enableLogging: Boolean) {
        logger.setEnableLogging(enableLogging)
    }

    /**
     * Sends SDK logs to [sink] instead of Logcat — for example to forward them to Crashlytics or
     * a file. The configured log level still filters what reaches the sink.
     */
    public fun setLogger(sink: LogSink) {
        logger.setSink(sink.toInternal())
    }

    public companion object {
        private const val EMAIL_TRAIT = "\$gv_email"
        private const val GALVA_SCHEME_PREFIX = "gv"

        /** Version of this SDK, e.g. for diagnostics screens and support reports. */
        @JvmStatic
        public val sdkVersion: String get() = BuildConfig.SDK_VERSION

        @JvmStatic
        public val instance: Galva by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { Galva(logger = defaultLogger()) }

        private fun defaultLogger(): Logger = Logger.create(
            tag = "Galva",
            sink = AndroidLogSink(),
            level = InternalLogLevel.WARN,
            enabled = true,
        )

        /**
         * Configures the SDK. Call once, from `Application.onCreate()`, on the main thread.
         *
         * @param integrations connections to other SDKs in the app, from integration packages,
         *   e.g. `GalvaIntegration.revenueCat()`. Installed once Galva is set up.
         */
        @JvmStatic
        @JvmOverloads
        public fun configure(
            context: Context,
            configuration: Configuration,
            integrations: List<GalvaIntegration> = emptyList(),
        ) {
            instance.configure(context, configuration, integrations)
        }
    }

    internal sealed class GalvaEvent {
        data class Identify(
            val userId: String, val email: String?, val obfuscatedAccountId: String?
        ) : GalvaEvent()

        data class SetPushToken(val token: String, val provider: PushProvider) : GalvaEvent()
        data object ClearPushToken : GalvaEvent()
        data object ReconcileTransactions : GalvaEvent()
        data class UpdateProperties(val properties: List<ProfileProperty>) : GalvaEvent()

        data class TrackNotification(
            val eventName: String,
            val properties: JsonObject,
            val onRecorded: (() -> Unit)?,
        ) : GalvaEvent()

        data class Track(val eventName: String, val properties: JsonObject?) : GalvaEvent()

        data class SetOptOut(val optedOut: Boolean) : GalvaEvent()
        data object Foreground : GalvaEvent()

        data class HandleOpenURL(val uri: Uri) : GalvaEvent()

        data object ProcessPendingDeeplink : GalvaEvent()

        data object Logout : GalvaEvent()
    }
}
