package io.galva.sdk

import kotlinx.coroutines.CoroutineScope

/**
 * Marks the API that integration packages (e.g. `galva-revenuecat`) build on. Apps don't opt in:
 * they only pass the factories those packages provide, like `GalvaIntegration.revenueCat()`.
 * The Android counterpart of the iOS SDK's `@_spi(GalvaIntegration)`.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "For Galva integration packages. Apps pass the integrations those packages provide.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.CONSTRUCTOR, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class GalvaIntegrationApi

/**
 * Connects Galva to another SDK in the app (RevenueCat, …). Each integration ships as its own
 * artifact, so apps that don't use that SDK never depend on it, and plugs in through `configure`:
 *
 * ```kotlin
 * Purchases.configure(PurchasesConfiguration.Builder(this, "goog_…").build())
 * Galva.configure(this, Configuration(apiKey = "pk_…"), integrations = listOf(GalvaIntegration.revenueCat()))
 * ```
 *
 * Galva installs each integration once, after its own setup has finished, off the main thread:
 * an integration never delays app start and may call any public Galva API from [install].
 */
public class GalvaIntegration @GalvaIntegrationApi constructor(
    /** Short identifier, e.g. `"revenuecat"`. */
    public val name: String,
    /** Version of the package that provides the integration. */
    public val version: String,
    /**
     * Called once, on a background thread, after Galva is set up. Long-running work (observing
     * another SDK) should launch in [IntegrationContext.scope] and return.
     */
    internal val install: suspend (IntegrationContext) -> Unit,
) {
    /** Integration packages add their factories here, e.g. `GalvaIntegration.revenueCat()`. */
    public companion object
}

/** What Galva hands an integration when installing it. */
@GalvaIntegrationApi
public class IntegrationContext internal constructor(
    /**
     * Galva's logger, prefixed with the integration name. The app's configured log level and
     * custom `LogSink` apply, including ones set later.
     */
    public val logger: IntegrationLogger,
    /** For the integration's long-running work; lives as long as the SDK, on background threads. */
    public val scope: CoroutineScope,
)

/** Logging for integrations, through Galva's logger. Messages are built only when logged. */
@GalvaIntegrationApi
public interface IntegrationLogger {
    public fun debug(message: () -> String)
    public fun info(message: () -> String)
    public fun warn(throwable: Throwable? = null, message: () -> String)
    public fun error(throwable: Throwable? = null, message: () -> String)
}
