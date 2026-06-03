package io.galva.sdk

import android.app.Activity
import android.content.Context
import androidx.annotation.VisibleForTesting
import io.galva.sdk.BuildConfig
import io.galva.billing.BillingManager
import io.galva.common.logger.LogLevel
import io.galva.common.logger.Logger
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
import io.galva.sdk.impl.billing.DefaultBillingManager
import io.galva.sdk.impl.billing.GalvaProductIdSource
import io.galva.sdk.impl.identity.DefaultIdentityManager
import io.galva.sdk.impl.inappmessage.DefaultInAppMessagingManager
import io.galva.sdk.impl.inappmessage.ScreenMessageOverlay
import io.galva.sdk.impl.operation.DefaultOperationManager
import io.galva.sdk.impl.store.SdkInitializeConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
        val keyValueStorage = KeyValueStorageFactory.create(context)
        identityManager = DefaultIdentityManager.create(keyValueStorage, logger)
        val httpClient = OkHttpClientBuilder().apiKey(configuration.apiKey)
            .sdkVersion("android/${io.galva.sdk.BuildConfig.SDK_VERSION}").logger(logger).build()
        val identifyService = HttpAPIIdentifyService(
            baseURL = if (BuildConfig.ENVIRONMENT == "DEVELOPMENT") io.galva.sdk.core.BuildConfig.BASE_API_URL else io.galva.sdk.core.BuildConfig.BASE_API_URL_PROD,
            httpClient = httpClient,
            logger = logger,
        )

        val bundleCache = WebViewBundleCache(File(context.filesDir, "webview_bundles"))
        val bundleDownloader = WebViewBundleDownloader(
            httpClient,
            if (BuildConfig.ENVIRONMENT == "DEVELOPMENT") io.galva.sdk.iam.BuildConfig.WEBVIEW_BUNDLE_BASE_URL else io.galva.sdk.iam.BuildConfig.BASE_WEBVIEW_IAM_URL_PROD
        )
        val webViewBundleResolver = WebViewBundleResolver(bundleCache, bundleDownloader, logger)

        val sdkInitClient = HttpAPISDKService(
            baseURL = if (BuildConfig.ENVIRONMENT == "DEVELOPMENT") io.galva.sdk.core.BuildConfig.BASE_API_URL else io.galva.sdk.core.BuildConfig.BASE_API_URL_PROD,
            httpClient,
            logger
        )
        val configStore = SdkInitializeConfigStore(sdkInitClient, keyValueStorage = keyValueStorage)
        _billingManager = DefaultBillingManager.create(
            context, logger, GalvaProductIdSource(
                configStore
            ), galvaScope
        )
        _inAppMessageManager = DefaultInAppMessagingManager.create(
            logger = logger,
            identityService = identifyService,
            identityManager = identity,
            webViewBundleResolver = webViewBundleResolver,
            messageOverlay = ScreenMessageOverlay(),
            billingManager = billingManager
        )
        galvaScope.launch(Dispatchers.IO) {
            identity.initialize()
            val config = configStore.loadAndSaveConfig()
            billingManager.initialize()
            val maxBatchSize = max(config?.data?.batchCollection?.flushSize ?: 10, 10)
            val maxBatchWaitingTime = maxOf(
                config?.data?.batchCollection?.flushIntervalMs?.milliseconds ?: 10_000.milliseconds,
                10.seconds
            )
            _operationManager = DefaultOperationManager.create(
                context,
                identifyService,
                galvaScope,
                logger,
                maxBatchSize = maxBatchSize,
                maxBatchWaitTime = maxBatchWaitingTime
            ).also {
                it.startRecordOperation()
            }

            if (identity.current.firstCreated) {
                operationManager.recordOperation(
                    APIOperation.CreateAnonymousId(
                        identity.current.anonymousId, identity.current.obfuscatedAccountId
                    )
                )
            }
            config?.data?.webviewVersions?.map { webviewVersion ->
                async(Dispatchers.IO) {
                    webViewBundleResolver.doDownload(webviewVersion)
                }
            }?.awaitAll()

        }
        log(configuration.logLevel, "Galva configured (apiKey=${configuration.apiKey})")
    }

    fun identify(userId: String, email: String? = null, obfuscatedAccountId: String? = null) {
        galvaScope.launch {
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
    }


    fun updateProperties(vararg properties: ProfileProperty) {
        galvaScope.launch {
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
    }

    fun logout() {
        galvaScope.launch {
            identity.logout()
            operationManager.clearAllOperation()
            operationManager.recordOperation(APIOperation.CreateAnonymousId(identity.current.anonymousId,identity.current.obfuscatedAccountId))
        }
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
}