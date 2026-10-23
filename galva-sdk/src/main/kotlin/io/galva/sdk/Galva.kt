package io.galva.sdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.annotation.VisibleForTesting
import io.galva.billing.BillingManager
import io.galva.common.lifecycle.AppLifecycleObserver
import io.galva.common.lifecycle.AppLifecycleState
import io.galva.common.logger.LogLevel
import io.galva.common.logger.LogSink
import io.galva.common.logger.Logger
import io.galva.common.utils.AppInfoSource
import io.galva.common.utils.DateTimeFormatUtils
import io.galva.common.utils.EmailValidator
import io.galva.common.utils.NotificationUtils
import io.galva.common.utils.PlayBillingVersion
import io.galva.core.protocol.Configuration
import io.galva.core.protocol.identity.ProfileProperty
import io.galva.core.protocol.operation.APIOperation
import io.galva.core.protocol.operation.OperationManager
import io.galva.iam.InAppMessagingManager
import io.galva.iam.Message
import io.galva.iam.bundle.WebViewBundleCache
import io.galva.iam.bundle.WebViewBundleDownloader
import io.galva.iam.bundle.WebViewBundleResolver
import io.galva.identity.Identity
import io.galva.identity.IdentityManager
import io.galva.localstorage.KeyValueStorageFactory
import io.galva.network.android.HttpAPIIamService
import io.galva.network.android.HttpAPIIdentifyService
import io.galva.network.android.HttpAPISDKService
import io.galva.network.android.OkHttpClientBuilder
import io.galva.network.service.IAMService
import io.galva.operation_queue.policy.SizeOrTimeoutBatchPolicy
import io.galva.sdk.impl.billing.DefaultBillingManager
import io.galva.sdk.impl.billing.DeferredBillingManager
import io.galva.sdk.impl.billing.GalvaProductIdSource
import io.galva.sdk.impl.deeplink.DeeplinkOpenCommunicationRouterHandler
import io.galva.sdk.impl.deeplink.DeeplinkRouter
import io.galva.sdk.impl.deeplink.LocalPendingDeeplinkStore
import io.galva.sdk.impl.identity.DefaultIdentityManager
import io.galva.sdk.impl.inappmessage.AndroidAppLifecycleObserver
import io.galva.sdk.impl.inappmessage.ApiFetchDurableQueue
import io.galva.sdk.impl.inappmessage.DefaultInAppMessagingManager
import io.galva.sdk.impl.inappmessage.InAppMessageAutoDisplay
import io.galva.sdk.impl.inappmessage.ScreenMessageOverlay
import io.galva.sdk.impl.inappmessage.SdkNotificationHandler
import io.galva.sdk.impl.operation.DefaultOperationManager
import io.galva.sdk.impl.session.SdkSettings
import io.galva.sdk.impl.session.SessionTracker
import io.galva.sdk.impl.store.SdkInitializeConfigStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Calendar
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
    private val galvaScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
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

    internal val configuration: Configuration
        get() = config ?: error("Galva not configured. Call Galva.instance.configure(...) first.")
    internal val operationManager: OperationManager
        get() = _operationManager
            ?: error("Galva not configured. Call Galva.instance.configure(...) first.")
    internal val identity: IdentityManager
        get() = identityManager
            ?: error("Galva not configured. Call Galva.instance.configure(...) first.")

    internal val inAppMessageManager: InAppMessagingManager
        get() = _inAppMessageManager
            ?: error("Galva not configured. Call Galva.instance.configure(...) first.")

    /**
     * Play Billing catalog and purchases used by in-app messages. Usable right after [configure];
     * calls are forwarded once the Play billing client has been set up in the background.
     */
    public val billingManager: BillingManager by lazy { DeferredBillingManager(billingManagerReady, galvaScope) }

    internal val sdkNotificationHandler: SdkNotificationHandler
        get() = _sdkNotificationHandler
            ?: error("Galva not configured. Call Galva.instance.configure(...) first.")

    private val deeplinkRouter: DeeplinkRouter
        get() = _deeplinkRouter
            ?: error("Galva not configured. Call Galva.instance.configure(...) first.")

    /** apiFetch proxy used by in-app message bundles. */
    internal val iamService: IAMService
        get() = _iamService
            ?: error("Galva not configured. Call Galva.instance.configure(...) first.")

    /** Persistent queue for apiFetch calls marked `shouldRetry`. */
    internal val apiFetchQueue: ApiFetchDurableQueue
        get() = _apiFetchQueue
            ?: error("Galva not configured. Call Galva.instance.configure(...) first.")

    private fun startProcessingGalvaEvents() {
        galvaScope.launch(Dispatchers.IO) {
            for (event in channel) {
                when (event) {
                    is GalvaEvent.Identify -> identifyInternal(
                        event.userId, event.email, event.obfuscatedAccountId
                    )

                    is GalvaEvent.SetPushToken -> setPushTokenInternal(event.token)
                    is GalvaEvent.ClearPushToken -> clearPushTokenInternal()
                    is GalvaEvent.UpdateProperties -> updatePropertiesInternal(event.properties)
                    is GalvaEvent.TrackPushEvent -> {
                        record(APIOperation.TrackPushNotification(event.communicationId, event.eventType, event.timestamp))
                        event.onRecorded?.invoke()
                    }

                    is GalvaEvent.SetOptOut -> setOptOutInternal(event.optedOut)
                    GalvaEvent.Foreground -> sessionTracker?.handleForeground()

                    is GalvaEvent.HandleOpenURL -> deeplinkRouter.handle(event.uri)
                    GalvaEvent.ProcessPendingDeeplink -> deeplinkRouter.handlePending()

                    GalvaEvent.Logout -> logoutInternal()
                }
            }
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
    private fun configure(context: Context, configuration: Configuration) {
        if (isConfigured) {
            logger.warn("Galva.configure() called twice — ignoring subsequent call.")
            return
        }
        // Only the application context is kept: the SDK outlives any activity.
        val appContext = context.applicationContext
        this.config = configuration
        configuration.logSink?.let(logger::setSink)
        logger.setLogLevel(configuration.logLevel)
        logger.setEnableLogging(configuration.logLevel != LogLevel.NONE)
        // SharedPreferences load on their own thread; nothing is read here.
        settings = SdkSettings(appContext)

        val appLifecycle: AppLifecycleObserver = AndroidAppLifecycleObserver()
        // Registered now so the first resumed activity is known when messages arrive.
        val autoDisplay = if (configuration.autoDisplayInAppMessages) {
            InAppMessageAutoDisplay(appContext as Application, galvaScope, logger).also { it.register() }
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
                setUp(appContext, configuration, appLifecycle, autoDisplay)
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
    ) {
        if (!PlayBillingVersion.isSupported) {
            logger.error {
                val found = if (PlayBillingVersion.isPresent) "an older Play Billing Library" else "no Play Billing Library"
                "Galva SDK requires Play Billing 8.0+, but the app has $found. Add to your build.gradle.kts: " +
                    "implementation(\"com.android.billingclient:billing-ktx:8.0.0\")"
            }
        }
        val settings = checkNotNull(settings)
        trackingOptedOut = settings.optedOut

        _sdkNotificationHandler = SdkNotificationHandler(appContext)
        val keyValueStorage = KeyValueStorageFactory.create(appContext)
        identityManager = DefaultIdentityManager.create(keyValueStorage, logger)
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
        val configStore = SdkInitializeConfigStore(sdkInitClient, keyValueStorage = keyValueStorage)
        val billing = DefaultBillingManager.create(
            appContext, logger, GalvaProductIdSource(
                configStore
            ), galvaScope
        ).apply {
            initialize()
        }
        billingManagerReady.complete(billing)
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
        if (ready.isCompleted) {
            block()
        } else {
            galvaScope.launch(Dispatchers.Main) {
                ready.await()
                block()
            }
        }
    }

    /** Records [operation] unless the user opted out of tracking. */
    private suspend fun record(operation: APIOperation) {
        if (trackingOptedOut) {
            logger.debug { "${operation::class.simpleName} dropped (opted out)" }
            return
        }
        operationManager.recordOperation(operation)
    }

    private suspend fun trackInternal(event: String, properties: JsonObject?) {
        record(APIOperation.Track(identity.current.anonymousId, identity.current.userId, event, properties))
    }

    /** `session_start` properties, same keys as the iOS SDK. */
    private fun sessionProperties(context: Context): JsonObject = buildJsonObject {
        put("device_locale", Locale.getDefault().toString())
        put("os_version", Build.VERSION.RELEASE)
        AppInfoSource.read(context)?.version?.let { put("app_version", it) }
        put("sdk_version", sdkVersion)
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
        val email = requestedEmail?.takeIf(EmailValidator::isValid)
        if (requestedEmail != null && email == null) logger.warn { "identify — dropped invalid email" }
        identity.identify(userId, email, obfuscatedAccountId)
        record(
            APIOperation.Identify(
                anonymousId = identity.current.anonymousId,
                userId = userId,
                obfuscatedAccountId = obfuscatedAccountId,
                email = email
            )
        )
        deeplinkRouter.handlePending()
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
        val valid = properties.filterNot { property ->
            val email = (property.propertyValue as? JsonPrimitive)?.takeIf { it.isString }?.content
            (property.key == EMAIL_TRAIT && email != null && !EmailValidator.isValid(email)).also { invalid ->
                if (invalid) logger.warn { "updateProperties — dropped invalid $EMAIL_TRAIT" }
            }
        }
        if (valid.isEmpty()) return
        val propertyObject = JsonObject(valid.associate { property -> property.key to property.propertyValue })
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

    private suspend fun logoutInternal() {
        // Drop the signed-out user's undelivered operations so none of them is sent after the
        // switch. Scoped to that identity: operations of the next identity are never purged.
        operationManager.clearOperationsOf(identity.current.anonymousId)
        // Recorded after the purge so the endpoint deletion for the signed-out user still goes out.
        clearPushTokenInternal()
        identity.logout()
        // Seen / presented messages belonged to the signed-out user.
        inAppMessageManager.reset()
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
            operationManager.clearAllOperation()
            logger.info { "Opted out — pending events purged" }
        } else if (!optedOut && wasOptedOut) {
            logger.info { "Opted back in" }
        }
    }

    // ── Push ────────────────────────────────────────────────────────────────

    public fun setPushToken(token: String) {
        channel.trySend(GalvaEvent.SetPushToken(token))
    }

    private suspend fun setPushTokenInternal(token: String) {
        if (trackingOptedOut) {
            logger.debug { "setPushToken dropped (opted out)" }
            return
        }
        val invalidToken = identity.current.pushToken
        if (invalidToken == token) {
            logger.debug { "Push token unchanged — nothing to register" }
            return
        }
        if (!(invalidToken.isNullOrEmpty())) {
            logger.info { "Replacing the registered push token" }
            clearInvalidPushTokenInternal(invalidToken)
        }
        identity.setPushToken(token)
        record(
            APIOperation.SetPushToken(
                anonymousId = identity.current.anonymousId, token = token
            )
        )
    }

    private suspend fun clearInvalidPushTokenInternal(pushToken: String) {
        identity.clearPushToken()
        record(
            APIOperation.ClearPushToken(
                anonymousId = identity.current.anonymousId, token = pushToken
            )
        )
    }

    public fun clearPushToken() {
        channel.trySend(GalvaEvent.ClearPushToken)
    }

    private suspend fun clearPushTokenInternal() {
        val currentToken = identity.current.pushToken ?: return
        clearInvalidPushTokenInternal(currentToken)
    }

    public fun isGalvaNotification(message: Map<String, String>): Boolean =
        NotificationUtils.isCommunicationNotification(message)

    /** Shows a Galva push notification and tracks it; call from `onMessageReceived`. */
    public fun handlePush(message: Map<String, String>) {
        message[COMMUNICATION_ID]?.let { trackPushEvent(it, PUSH_PRESENTED) }
        whenReady { sdkNotificationHandler.handleNotification(message) }
    }

    /**
     * Records a push interaction on the SDK queue. [onRecorded] runs once it is persisted (or
     * dropped while opted out), so a BroadcastReceiver can finish its `goAsync()` work.
     */
    internal fun trackPushEvent(communicationId: String, eventType: String, onRecorded: (() -> Unit)? = null) {
        channel.trySend(
            GalvaEvent.TrackPushEvent(
                communicationId, eventType, DateTimeFormatUtils.format(Calendar.getInstance()), onRecorded
            )
        )
    }

    // ── In-app messages ─────────────────────────────────────────────────────

    /**
     * Messages published by the SDK: at most one per return to the foreground (or
     * [checkForMessages]). Collect while your UI is visible and pass each to [showMessage], or set
     * `Configuration(autoDisplayInAppMessages = true)` instead.
     */
    public fun getInAppMessage(): Flow<Message> = flow {
        ready.await()
        emitAll(inAppMessageManager.messages)
    }

    public fun showMessage(activity: Activity, message: Message) {
        whenReady { inAppMessageManager.showMessage(activity, message) }
    }

    /**
     * Polls for an in-app message now. Polling already happens on every return to the foreground;
     * use this after a user action that should trigger a workflow.
     */
    public fun checkForMessages() {
        whenReady { inAppMessageManager.checkForMessages() }
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
        logger.setLogLevel(level)
    }

    public fun setEnableLogging(enableLogging: Boolean) {
        logger.setEnableLogging(enableLogging)
    }

    /**
     * Sends SDK logs to [sink] instead of Logcat — for example to forward them to Crashlytics or
     * a file. The configured log level still filters what reaches the sink.
     */
    public fun setLogger(sink: LogSink) {
        logger.setSink(sink)
    }

    public companion object {
        private const val EMAIL_TRAIT = "\$gv_email"
        private const val COMMUNICATION_ID = "communicationId"
        private const val PUSH_PRESENTED = "communication_presented"
        private const val GALVA_SCHEME_PREFIX = "gv"

        /** Version of this SDK, e.g. for diagnostics screens and support reports. */
        @JvmStatic
        public val sdkVersion: String get() = BuildConfig.SDK_VERSION

        @JvmStatic
        public val instance: Galva by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { Galva(logger = defaultLogger()) }

        private fun defaultLogger(): Logger = Logger.create(
            tag = "Galva",
            sink = AndroidLogSink(),
            level = LogLevel.WARN,
            enabled = true,
        )

        /** Configures the SDK. Call once, from `Application.onCreate()`, on the main thread. */
        @JvmStatic
        public fun configure(context: Context, configuration: Configuration) {
            instance.configure(context, configuration)
        }
    }

    internal sealed class GalvaEvent {
        data class Identify(
            val userId: String, val email: String?, val obfuscatedAccountId: String?
        ) : GalvaEvent()

        data class SetPushToken(val token: String) : GalvaEvent()
        data object ClearPushToken : GalvaEvent()
        data class UpdateProperties(val properties: List<ProfileProperty>) : GalvaEvent()

        data class TrackPushEvent(
            val communicationId: String,
            val eventType: String,
            val timestamp: String,
            val onRecorded: (() -> Unit)?,
        ) : GalvaEvent()

        data class SetOptOut(val optedOut: Boolean) : GalvaEvent()
        data object Foreground : GalvaEvent()

        data class HandleOpenURL(val uri: Uri) : GalvaEvent()

        data object ProcessPendingDeeplink : GalvaEvent()

        data object Logout : GalvaEvent()
    }
}
