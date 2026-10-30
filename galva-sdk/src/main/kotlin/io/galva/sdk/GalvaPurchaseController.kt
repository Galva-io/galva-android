package io.galva.sdk

import android.app.Activity

/**
 * Executes the purchases Galva in-app messages request, in place of the SDK's built-in Play
 * Billing flow. Install one with [Galva.setPurchaseController].
 *
 * By default the SDK launches the Play purchase itself, with the user's obfuscated account id
 * attached, and Galva's backend acknowledges it. A billing SDK in the app (RevenueCat, an
 * in-house layer, …) then learns about the purchase only when it next queries Play, so the user's
 * entitlement may stay locked until then. A controller runs the purchase through that billing
 * layer instead, so it records the purchase and refreshes entitlements right away:
 *
 * ```kotlin
 * class BillingPurchaseController : GalvaPurchaseController {
 *     override suspend fun purchase(request: GalvaPurchaseRequest): GalvaPurchaseResult {
 *         val result = request.purchaseWithPlayBilling()
 *         if (result is GalvaPurchaseResult.Completed) MyBilling.record(result.transaction.purchaseToken)
 *         return result
 *     }
 * }
 *
 * Galva.instance.setPurchaseController(BillingPurchaseController())
 * ```
 *
 * RevenueCat apps don't write one: the `GalvaIntegration.revenueCat()` integration installs it.
 */
public interface GalvaPurchaseController {
    /**
     * Runs [request] and reports how it ended. Called on the main thread, once per purchase the
     * user starts inside a message; the message waits for the result.
     *
     * @return [GalvaPurchaseResult.Completed] with the transaction, [GalvaPurchaseResult.Pending]
     *   when the payment is not finished yet, or [GalvaPurchaseResult.Cancelled].
     * @throws GalvaPurchaseException for failures the message handles specifically (unavailable
     *   product, ineligible offer, …). Any other exception reaches the message as a generic
     *   purchase failure.
     */
    public suspend fun purchase(request: GalvaPurchaseRequest): GalvaPurchaseResult
}

/** A purchase an in-app message asked for, handed to your [GalvaPurchaseController]. */
public class GalvaPurchaseRequest @GalvaIntegrationApi constructor(
    /** The message's activity, for billing APIs that launch the purchase screen. */
    public val activity: Activity,
    /** Play product id. */
    public val productId: String,
    /** Play base plan id. */
    public val basePlanId: String,
    /** Play offer id to apply, or null for the base plan without an offer. */
    public val offerId: String?,
    /**
     * The account id Galva attributes this user's purchases to (Play's `obfuscatedAccountId`,
     * as the SDK attaches it to its own purchases). Attach it when your billing layer lets you.
     */
    public val obfuscatedAccountId: String,
    /** Id of the in-app message that requested the purchase. */
    public val messageId: String,
    private val playBillingPurchase: suspend () -> GalvaPurchaseResult,
) {
    /**
     * Runs Galva's built-in Play Billing purchase for this request: [offerId] is resolved from
     * the SDK's catalog and [obfuscatedAccountId] is attached. Use it for the purchases your
     * billing layer can't run itself.
     *
     * @throws GalvaPurchaseException when the purchase fails.
     */
    public suspend fun purchaseWithPlayBilling(): GalvaPurchaseResult = playBillingPurchase()
}

/** How a purchase run by a [GalvaPurchaseController] ended. */
public sealed class GalvaPurchaseResult {
    /** The purchase succeeded. */
    public data class Completed(public val transaction: GalvaTransaction) : GalvaPurchaseResult()

    /** Payment not finished yet (pending payment method or approval); Play delivers it later. */
    public data object Pending : GalvaPurchaseResult()

    /** The user dismissed the purchase screen. */
    public data object Cancelled : GalvaPurchaseResult()
}

/** A completed Play purchase. */
public data class GalvaTransaction(
    /** Play order id; null for some test purchases. */
    public val orderId: String?,
    public val productId: String,
    public val purchaseToken: String,
    /** Purchase time, in milliseconds since the epoch. */
    public val purchaseTime: Long,
    /** The `obfuscatedAccountId` Play recorded on the purchase, if any. */
    public val obfuscatedAccountId: String? = null,
    /** Whether Play already reports the purchase as acknowledged. */
    public val acknowledged: Boolean = false,
)

/**
 * Structured purchase failures a [GalvaPurchaseController] can throw. Each maps onto an error code
 * the in-app message handles specifically; throw [Failed] for anything else.
 */
public sealed class GalvaPurchaseException(message: String) : Exception(message) {
    /** Play doesn't sell this product, or it is not in the catalog. */
    public class ProductUnavailable(message: String = "Product unavailable") : GalvaPurchaseException(message)

    /** Purchases are not possible on this device (no Play Billing, restricted account, …). */
    public class PurchaseNotAllowed(message: String = "Purchases are not allowed") : GalvaPurchaseException(message)

    /** The user isn't eligible for the offer. */
    public class IneligibleForOffer(message: String = "Not eligible for the offer") : GalvaPurchaseException(message)

    /** The product isn't sold in the user's country. */
    public class NotAvailableInStorefront(message: String = "Not available in this storefront") :
        GalvaPurchaseException(message)

    /** Play rejected the offer. */
    public class InvalidOffer(message: String) : GalvaPurchaseException(message)

    /** The purchase could not be verified. */
    public class VerificationFailed(message: String) : GalvaPurchaseException(message)

    /** Play or the billing backend was unreachable. */
    public class NetworkError(message: String) : GalvaPurchaseException(message)

    /** No purchase result arrived in time. */
    public class Timeout(message: String = "The purchase timed out") : GalvaPurchaseException(message)

    /** Any other failure. */
    public class Failed(message: String) : GalvaPurchaseException(message)
}
