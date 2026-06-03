package io.galva.sdk.impl.billing

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import io.galva.billing.model.BillingConnection
import io.galva.common.logger.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PlayBillingConnection(
    private val billingClient: BillingClient,
    private val logger: Logger,
    private val connectTimeout: Duration = 5.seconds,
    private val maxRetries: Int = 5,
    private val baseBackoff: Duration = 500.milliseconds,
    private val maxBackoff: Duration = 30.seconds,
) : BillingConnection {

    private val mutex = Mutex()

    @Volatile
    private var connectedDeferred: CompletableDeferred<Result<Unit>>? = null

    @Volatile
    private var attempts = 0

    override val isConnected: Boolean get() = billingClient.isReady

    /** Suspends until billing client is connected. Throws on irrecoverable failure. */
    override suspend fun ensureConnected() {
        if (isConnected) return

        val deferred = mutex.withLock {
            // Re-check after acquiring lock — another caller may have completed
            if (isConnected) return@withLock null
            connectedDeferred?.takeIf { !it.isCompleted } ?: startConnection()
        } ?: return

        val result = withTimeoutOrNull(connectTimeout) { deferred.await() } ?: Result.failure(
            IllegalStateException("Billing connection timed out")
        )

        result.getOrThrow()
    }

    private fun startConnection(): CompletableDeferred<Result<Unit>> {
        val deferred = CompletableDeferred<Result<Unit>>()
        connectedDeferred = deferred

        logger.debug { "Starting BillingClient connection (attempt ${attempts + 1})" }
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    attempts = 0
                    logger.info { "BillingClient connected" }
                    deferred.complete(Result.success(Unit))
                } else {
                    attempts++
                    val msg =
                        "BillingClient setup failed: ${result.responseCode} ${result.debugMessage}"
                    logger.warn { msg }
                    if (attempts <= maxRetries) {
                        // Reset for retry on next ensureConnected()
                        connectedDeferred = null
                    }
                    deferred.complete(Result.failure(IllegalStateException(msg)))
                }
            }

            override fun onBillingServiceDisconnected() {
                logger.warn { "BillingClient disconnected — will reconnect on next ensureConnected()" }
                connectedDeferred = null                              // next call re-connects
            }
        })

        return deferred
    }

    /** Wait + retry helper for callers wanting to keep trying through transient failures. */
    override suspend fun ensureConnectedWithRetry() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            try {
                ensureConnected()
                return
            } catch (t: Throwable) {
                attempt++
                if (attempt > maxRetries) throw t
                val backoff =
                    (baseBackoff * (1 shl (attempt - 1).coerceAtMost(10))).coerceAtMost(maxBackoff)
                logger.debug { "Connection retry $attempt/$maxRetries after ${backoff}" }
                delay(backoff)
            }
        }
    }

    override fun endConnection() {
        logger.info { "Ending BillingClient connection" }
        billingClient.endConnection()
        connectedDeferred = null
    }
}