package io.galva.sdk

import android.app.Activity
import android.content.Context
import android.icu.util.Calendar
import androidx.annotation.VisibleForTesting
import io.galva.sdk.BuildConfig
import io.galva.billing.BillingManager
import io.galva.common.lifecycle.AppLifecycleObserver
import io.galva.common.lifecycle.AppLifecycleState
import io.galva.common.logger.LogLevel
import io.galva.common.logger.Logger
import io.galva.common.utils.DateTimeFormatUtils
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
import io.galva.identity.IdentityManager
import io.galva.localstorage.KeyValueStorageFactory
import io.galva.network.android.HttpAPIIdentifyService
import io.galva.network.android.HttpAPIProductService
import io.galva.network.android.HttpAPISDKService
import io.galva.network.android.OkHttpClientBuilder
import io.galva.network.request.sdk.InitConfigSdkRequest
import io.galva.operation_queue.policy.SizeOrTimeoutBatchPolicy
import io.galva.sdk.impl.billing.DefaultBillingManager
import io.galva.sdk.impl.billing.GalvaProductIdSource
import io.galva.sdk.impl.identity.DefaultIdentityManager
import io.galva.sdk.impl.inappmessage.AndroidAppLifecycleObserver
import io.galva.sdk.impl.inappmessage.DefaultInAppMessagingManager
import io.galva.sdk.impl.inappmessage.ScreenMessageOverlay
import io.galva.sdk.impl.inappmessage.SdkNotificationHandler
import io.galva.sdk.impl.operation.DefaultOperationManager
import io.galva.sdk.impl.store.SdkInitializeConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class Galva @VisibleForTesting internal constructor(
    val logger: Logger,
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
    private var _billingManager: BillingManager? = null

    @Volatile
    private var _sdkNotificationHandler: SdkNotificationHandler? = null

    private val channel = Channel<GalvaEvent>(Channel.UNLIMITED)

    val isConfigured: Boolean get() = config != null

    val configuration: Configuration
        get() = config ?: error("Galva not configured. Call Galva.instance.configure(...) first.")
    val operationManager: OperationManager
        get() = _operationManager
            ?: error("Galva not configured. Call Galva.instance.configure(...) first.")
    val identity: IdentityManager
        get() = identityManager
            ?: error("Galva not configured. Call Galva.instance.configure(...) first.")

    val inAppMessageManager: InAppMessagingManager
        get() = _inAppMessageManager
            ?: error("Galva not configured. Call Galva.instance.configure(...) first.")

    val billingManager: BillingManager
        get() = _billingManager
            ?: error("Galva not configured. Call Galva.instance.configure(...) first.")


    val sdkNotificationHandler: SdkNotificationHandler
        get() = _sdkNotificationHandler
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
                    is GalvaEvent.TrackNotificationPresented -> trackNotificationPresentedInternal(
                        event.message, event.timestamp
                    )

                    GalvaEvent.Logout -> logoutInternal()
                }
            }
        }
    }

    @Synchronized
    @JvmName("configureInternal")
    private fun configure(context: Context, configuration: Configuration) {
        if (isConfigured) {
            log(
                this.configuration.logLevel,
                "Galva.configure() called twice — ignoring subsequent call."
            )
            return
        }
        this.config = configuration
        logger.setLogLevel(configuration.logLevel)
        logger.setEnableLogging(configuration.logLevel != LogLevel.NONE)
        if (!PlayBillingVersion.ensureMinimumVersion(8)) {
            logger.error {
                "Detected Play Billing version ${PlayBillingVersion.current}, " + "but Galva SDK requires 8.0+. Add to your build.gradle.kts: " + "implementation(\"com.android.billingclient:billing-ktx:8.0.0\")"
            }
        }
        _sdkNotificationHandler = SdkNotificationHandler(context)
        val keyValueStorage = KeyValueStorageFactory.create(context)
        identityManager = DefaultIdentityManager.create(keyValueStorage, logger)
        val httpClient = OkHttpClientBuilder().apiKey(configuration.apiKey)
            .sdkVersion("android/${io.galva.sdk.BuildConfig.SDK_VERSION}").logger(logger).build()
        val identifyService = HttpAPIIdentifyService(
            baseURL = configuration.env.baseAPIUrl,
            httpClient = httpClient,
            logger = logger,
        )

        val bundleCache = WebViewBundleCache(File(context.filesDir, "webview_bundles"))
        val bundleDownloader = WebViewBundleDownloader(
            httpClient, configuration.env.baseWebviewUrl
        )
        val webViewBundleResolver = WebViewBundleResolver(bundleCache, bundleDownloader, logger)

        val sdkInitClient = HttpAPISDKService(
            baseURL = configuration.env.baseAPIUrl, httpClient, logger
        )
        val batchPolicy = SizeOrTimeoutBatchPolicy(10, 10.seconds)
        val configStore = SdkInitializeConfigStore(sdkInitClient, keyValueStorage = keyValueStorage)
        _billingManager = DefaultBillingManager.create(
            context, logger, GalvaProductIdSource(
                configStore
            ), galvaScope
        ).apply {
            initialize()
        }
        val appLifecycle: AppLifecycleObserver = AndroidAppLifecycleObserver()
        _inAppMessageManager = DefaultInAppMessagingManager.create(
            logger = logger,
            identityService = identifyService,
            identityManager = identity,
            webViewBundleResolver = webViewBundleResolver,
            messageOverlay = ScreenMessageOverlay(),
            billingManager = billingManager,
            appLifecycle
        )
        _operationManager = DefaultOperationManager.create(
            context, identifyService, galvaScope, logger, batchPolicy, appLifecycle
        )
        galvaScope.launch(Dispatchers.IO) {
            identity.initialize()
            val config = configStore.loadAndSaveConfig()
            billingManager.loadProducts()
            val maxBatchSize = max(config?.data?.batchCollection?.flushSize ?: 10, 10)
            val maxBatchWaitingTime = maxOf(
                config?.data?.batchCollection?.flushIntervalMs?.milliseconds ?: 10_000.milliseconds,
                10.seconds
            )
            batchPolicy.update(maxBatchSize, maxBatchWaitingTime)
            if (identity.current.firstCreated) {
                operationManager.recordOperation(
                    APIOperation.CreateAnonymousId(
                        identity.current.anonymousId, identity.current.obfuscatedAccountId
                    )
                )
            }
            startProcessingGalvaEvents()
            config?.data?.webviewVersions?.map { webviewVersion ->
                async(Dispatchers.IO) {
                    webViewBundleResolver.doDownload(webviewVersion)
                }
            }?.awaitAll()
        }
        appLifecycle.observe { event ->
            if (event == AppLifecycleState.BACKGROUND) {
                updateProperties(ProfileProperty.LastActiveTime(System.currentTimeMillis()))
            }
        }
        log(configuration.logLevel, "Galva configured (apiKey=${configuration.apiKey})")
    }

    fun identify(userId: String, email: String? = null, obfuscatedAccountId: String? = null) {
        channel.trySend(GalvaEvent.Identify(userId, email, obfuscatedAccountId))
    }

    private suspend fun identifyInternal(
        userId: String, email: String? = null, obfuscatedAccountId: String? = null
    ) {
        identity.identify(userId, email, obfuscatedAccountId)
        operationManager.recordOperation(
            APIOperation.Identify(
                anonymousId = identity.current.anonymousId,
                userId = userId,
                obfuscatedAccountId = obfuscatedAccountId,
                email = email
            )
        )
    }

    fun setPushToken(token: String) {
        channel.trySend(GalvaEvent.SetPushToken(token))
    }

    private suspend fun setPushTokenInternal(token: String) {
        val invalidToken = identity.current.pushToken
        if (!(invalidToken.isNullOrEmpty())) {
            log(
                LogLevel.INFO,
                "Overriding existing push token: $invalidToken with new token: $token"
            )
            clearInvalidPushTokenInternal(invalidToken)
        }
        identity.setPushToken(token)
        operationManager.recordOperation(
            APIOperation.SetPushToken(
                anonymousId = identity.current.anonymousId, token = token
            )
        )
    }


    private suspend fun clearInvalidPushTokenInternal(pushToken: String) {
        identity.clearPushToken()
        operationManager.recordOperation(
            APIOperation.ClearPushToken(
                anonymousId = identity.current.anonymousId, token = pushToken
            )
        )
    }


    fun clearPushToken() {
        channel.trySend(GalvaEvent.ClearPushToken)
    }

    private suspend fun clearPushTokenInternal() {
        val currentToken = identity.current.pushToken ?: return
        clearInvalidPushTokenInternal(currentToken)
    }


    fun updateProperties(vararg properties: ProfileProperty) {
        channel.trySend(GalvaEvent.UpdateProperties(properties.toList()))
    }

    private suspend fun updatePropertiesInternal(properties: List<ProfileProperty>) {
        val propertyObject = JsonObject(
            properties.associate { property ->
                property.key to property.propertyValue
            })
        identity.updateUserProperties(propertyObject)
        operationManager.recordOperation(
            APIOperation.UpdateUserProperties(
                identity.current.anonymousId, propertyObject
            )
        )
    }

    private suspend fun trackNotificationPresentedInternal(
        message:Map<String, String>, timestamp: String
    ) {
        val communicationId = message["communicationId"] ?: return
        operationManager.recordOperation(
            APIOperation.TrackPushNotification(
                communicationId, "communication_presented", timestamp
            )
        )
    }

    fun logout() {
        channel.trySend(GalvaEvent.Logout)
    }

    private suspend fun logoutInternal() {
        clearPushTokenInternal()
        identity.logout()
        operationManager.clearAllOperation()
        operationManager.recordOperation(
            APIOperation.CreateAnonymousId(
                identity.current.anonymousId, identity.current.obfuscatedAccountId
            )
        )
    }

    fun getInAppMessage(): Flow<Message> {
        return inAppMessageManager.messages
    }

    fun showMessage(activity: Activity, message: Message) {
        inAppMessageManager.showMessage(activity, message)
    }

    val currentUserId: String
        get() = identity.current.userId ?: identity.current.anonymousId

    val obfuscatedAccountId: String
        get() = identity.current.obfuscatedAccountId

    val isAnonymous: Boolean
        get() = identity.current.userId.isNullOrEmpty()

    fun setLogLevel(level: LogLevel) {
        logger.setLogLevel(level)
    }

    fun setEnableLogging(enableLogging: Boolean) {
        logger.setEnableLogging(enableLogging)
    }

    fun isGalvaNotification(message: Map<String, String>) =
        NotificationUtils.isCommunicationNotification(message)

    fun handlePush(message: Map<String, String>) {
        channel.trySend(
            GalvaEvent.TrackNotificationPresented(
                message, DateTimeFormatUtils.format(
                    java.util.Calendar.getInstance()
                )
            )
        )
        sdkNotificationHandler.handleNotification(message)
    }


    private fun log(level: LogLevel, message: String) {
        when (level) {
            LogLevel.NONE -> return
            LogLevel.ERROR -> logger.error(message)
            LogLevel.WARN -> logger.warn(message)
            LogLevel.INFO -> logger.info(message)
            LogLevel.DEBUG -> logger.debug(message)
            LogLevel.VERBOSE -> logger.verbose(message)
        }
    }

    companion object {
        @JvmStatic
        val instance: Galva by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { Galva(logger = defaultLogger()) }

        private fun defaultLogger(): Logger = Logger.create(
            tag = "Galva",
            sink = AndroidLogSink(),
            level = LogLevel.WARN,
            enabled = true,
        )

        @JvmStatic
        fun configure(context: Context, configuration: Configuration) =
            instance.configure(context, configuration)


    }

    internal sealed class GalvaEvent {
        data class Identify(
            val userId: String, val email: String?, val obfuscatedAccountId: String?
        ) : GalvaEvent()

        data class SetPushToken(val token: String) : GalvaEvent()
        data object ClearPushToken : GalvaEvent()
        data class UpdateProperties(val properties: List<ProfileProperty>) : GalvaEvent()

        data class TrackNotificationPresented(val message: Map<String, String>, val timestamp: String) :
            GalvaEvent()

        object Logout : GalvaEvent()
    }
}