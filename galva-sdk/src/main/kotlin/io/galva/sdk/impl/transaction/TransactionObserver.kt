package io.galva.sdk.impl.transaction

import io.galva.common.logger.Logger
import io.galva.identity.Identity
import io.galva.network.request.transactions.ObserveTransactionsRequest
import io.galva.network.request.transactions.ObservedTransaction
import io.galva.network.service.ServiceResult
import io.galva.network.service.TransactionService
import io.galva.sdk.impl.billing.PlayPurchases
import io.galva.sdk.impl.billing.PlayTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reports the device's newest Play purchase to `POST /v1/transactions/observe`, so Galva can join
 * store notifications for it to the current user, including purchases made by the app's own paywall
 * or another billing SDK. The counterpart of iOS's `StoreKitTransactionObserver`.
 *
 * - Reports after every purchase update Galva's billing client sees ([onPurchasesUpdated]), after a
 *   purchase in an in-app message completes through any billing layer ([onTransactionCompleted]),
 *   and from the account's current purchases ([sweep]) on every app entry and on
 *   `Galva.reconcileTransactions()`.
 * - Only the newest purchase in the PURCHASED state is sent; pending ones are skipped.
 * - A purchase is reported once per process. [reset] (on logout) lets the new anonymous user report
 *   it again; a report that fails is retried on the next app entry.
 * - Nothing is read or sent while the user is opted out.
 */
internal class TransactionObserver(
    private val service: TransactionService,
    private val identity: () -> Identity?,
    private val isOptedOut: () -> Boolean,
    private val logger: Logger,
) {
    private val mutex = Mutex()

    /** `orderId|purchaseToken` of the purchases reported in this process; guarded by [mutex]. */
    private val reported = mutableSetOf<String>()

    suspend fun onPurchasesUpdated(purchases: List<PlayTransaction>) = report(purchases, "purchase update")

    /** A purchase from an in-app message, whichever billing client ran it (e.g. RevenueCat's). */
    suspend fun onTransactionCompleted(transaction: PlayTransaction) = report(listOf(transaction), "message purchase")

    /** On app entry: re-reads the account's purchases from Play. */
    suspend fun sweep(source: PlayPurchases) {
        if (isOptedOut()) {
            logger.debug { "transactions observe skipped (opted out)" }
            return
        }
        report(source.query(), "app entry")
    }

    suspend fun reset() = mutex.withLock { reported.clear() }

    private suspend fun report(purchases: List<PlayTransaction>, trigger: String) = mutex.withLock {
        if (isOptedOut()) {
            logger.debug { "transactions observe skipped (opted out)" }
            return@withLock
        }
        val latest = latestPurchased(purchases)
        if (latest == null) {
            logger.debug { "transactions observe: no purchased transaction ($trigger)" }
            return@withLock
        }
        val key = "${latest.orderId}|${latest.purchaseToken}"
        if (key in reported) {
            logger.debug { "transactions observe: ${latest.orderId} already reported ($trigger)" }
            return@withLock
        }
        val user = identity()?.takeUnless { it.isEmpty() } ?: return@withLock
        val request = ObserveTransactionsRequest(
            anonymousId = user.anonymousId,
            endUserId = user.userId,
            transactions = listOf(ObservedTransaction(latest.orderId, latest.purchaseToken)),
        )
        when (val result = service.observeTransactions(request)) {
            is ServiceResult.Success -> {
                reported += key
                logger.info { "Transaction observed: ${latest.orderId ?: "no order id"} ($trigger)" }
            }
            is ServiceResult.Error ->
                logger.info { "transactions observe skipped (offline?); retried on the next app entry" }
            is ServiceResult.Failure ->
                logger.warn { "transactions observe rejected with HTTP ${result.status}; retried on the next app entry" }
            is ServiceResult.UnknownError ->
                logger.warn(result.exception) { "transactions observe failed; retried on the next app entry" }
        }
    }

    internal companion object {
        /** The newest purchase in the PURCHASED state, or null. */
        fun latestPurchased(purchases: List<PlayTransaction>): PlayTransaction? =
            purchases.filter { it.purchased }.maxByOrNull { it.purchaseTime }
    }
}
