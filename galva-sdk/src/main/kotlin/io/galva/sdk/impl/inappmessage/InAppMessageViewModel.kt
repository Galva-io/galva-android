package io.galva.sdk.impl.inappmessage

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.WindowInsets
import androidx.core.graphics.Insets
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.galva.billing.BillingManager
import io.galva.billing.model.OfferWithPhasesModel
import io.galva.billing.model.PricingPhase
import io.galva.common.logger.Logger
import io.galva.common.utils.AppInfoSource
import io.galva.common.utils.HashUtils
import io.galva.iam.PageContext
import io.galva.iam.SafeArea
import io.galva.iam.bridge.BridgeError
import io.galva.iam.bridge.BridgeException
import io.galva.iam.bridge.PurchaseRequest
import io.galva.network.request.APIFetchRequest
import io.galva.network.response.APIFetchResult
import io.galva.network.service.IAMService
import io.galva.network.service.ServiceResult
import io.galva.sdk.Galva
import io.galva.sdk.GalvaIntegrationApi
import io.galva.sdk.GalvaPurchaseController
import io.galva.sdk.GalvaPurchaseException
import io.galva.sdk.GalvaPurchaseRequest
import io.galva.sdk.GalvaPurchaseResult
import io.galva.sdk.GalvaTransaction
import io.galva.sdk.impl.billing.PlayBillingPurchase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.Locale

internal class InAppMessageViewModel(
    private val billingManager: BillingManager,
    private val iamService: IAMService?,
    private val purchaseController: () -> GalvaPurchaseController? = { Galva.instance.purchaseController },
    private val logger: Logger = Galva.instance.logger,
    /** Reports a completed purchase to Galva right away, whichever billing layer ran it. */
    private val onPurchased: (GalvaTransaction) -> Unit = { Galva.instance.reportTransaction(it) },
) : ViewModel() {

    /**
     * Runs a purchase the message requested, through the installed [GalvaPurchaseController] or
     * else the built-in Play Billing flow, and returns the bridge answer. The controller runs on
     * the main thread, like the iOS SDK's `@MainActor` controller.
     */
    @OptIn(GalvaIntegrationApi::class)
    suspend fun purchase(activity: Activity, request: PurchaseRequest, messageId: String): Result<JsonObject> {
        val galvaRequest = GalvaPurchaseRequest(
            activity = activity,
            productId = request.productId,
            basePlanId = request.basePlanId,
            offerId = request.offerId,
            obfuscatedAccountId = purchaseAccountId(),
            messageId = messageId,
            playBillingPurchase = { purchaseWithPlayBilling(activity, request) },
        )
        val controller = purchaseController()
        val product = "${request.productId}/${request.basePlanId}" + (request.offerId?.let { "/$it" } ?: "")
        logger.info { "Purchase of $product requested via ${controller?.javaClass?.simpleName ?: "Play Billing"}" }
        return try {
            val result = withContext(Dispatchers.Main.immediate) {
                controller?.purchase(galvaRequest) ?: galvaRequest.purchaseWithPlayBilling()
            }
            logger.info {
                when (result) {
                    is GalvaPurchaseResult.Completed -> "Purchase of $product completed (order ${result.transaction.orderId})"
                    GalvaPurchaseResult.Pending -> "Purchase of $product pending: payment not finished yet"
                    GalvaPurchaseResult.Cancelled -> "Purchase of $product cancelled by the user"
                }
            }
            // A controller's billing client (e.g. RevenueCat's) doesn't reach Galva's purchase listener.
            if (result is GalvaPurchaseResult.Completed) onPurchased(result.transaction)
            Result.success(PurchaseBridgeMapper.result(result, request))
        } catch (e: CancellationException) {
            throw e
        } catch (e: GalvaPurchaseException) {
            logger.warn { "Purchase of $product failed: ${e.javaClass.simpleName} (${e.message})" }
            Result.failure(BridgeException(PurchaseBridgeMapper.error(e)))
        } catch (e: Exception) {
            logger.error(e) { "Purchase of ${request.productId} failed" }
            Result.failure(BridgeException(BridgeError(BridgeError.Code.PURCHASE_FAILED, e.message ?: e.toString())))
        }
    }

    private suspend fun purchaseWithPlayBilling(activity: Activity, request: PurchaseRequest): GalvaPurchaseResult =
        PlayBillingPurchase.await(
            billingManager.launchBilling(
                activity = activity,
                productId = request.productId,
                basePlanId = request.basePlanId,
                offerId = request.offerId,
                obfuscatedAccountId = purchaseAccountId(),
            ),
            offerRequested = request.offerId != null,
        )

    /** Value attached to Play purchases: the identity's obfuscatedAccountId, hashed. */
    private fun purchaseAccountId() = HashUtils.hashToSafeToken(Galva.instance.obfuscatedAccountId)

    private var _storefrontCountryCode: String? = null
    val storefrontCountryCode
        get() = _storefrontCountryCode

    init {
        viewModelScope.launch(Dispatchers.IO) {
            // A prefetch: on failure getPageContext asks again.
            _storefrontCountryCode = try {
                billingManager.getStorefrontCountryCode()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.debug { "Storefront country prefetch failed: ${e.message}" }
                null
            }
        }
    }

    suspend fun doGetPageContext(context: Context, windowInsets: Insets?): PageContext {
        val countryCode = storefrontCountryCode ?: billingManager.getStorefrontCountryCode()
        val app = AppInfoSource.read(context)
        return PageContext(
            bridgeProtocol = io.galva.sdk.iam.BuildConfig.JS_BRIDGE_VERSION,
            sdkVersion = "android/${io.galva.sdk.BuildConfig.SDK_VERSION}",
            platform = "android",
            appVersion = app?.version ?: "1.0.0",
            appBuild = app?.build ?: "1",
            pushAuthorization = "authorized",
            locale = Locale.getDefault().toLanguageTag(),
            storefrontCountryCode = countryCode ?: "unknown",
            appAccountToken = purchaseAccountId(),
            safeArea = windowInsets?.run {
                SafeArea(
                    top = top, left = left, right = right, bottom = bottom
                )
            })
    }

    suspend fun doApiFetch(apiFetchRequest: APIFetchRequest): ServiceResult<APIFetchResult> {
        return iamService?.apiFetch(apiFetchRequest)
            ?: ServiceResult.UnknownError(IllegalStateException("Galva is not set up"))
    }

    @kotlinx.serialization.InternalSerializationApi
    suspend fun getProductPrice(
        productId: String, basePlanId: String, offerId: String?
    ): ProductWithOfferPrice? {
        val product = billingManager.getProductCatalog(productId)
        if (product == null) {
            logger.debug { "getProductPrice: product $productId is not in the catalog" }
            return null
        }
        val basePlan = product.basePlans.firstOrNull {
            it.basePlan.id == basePlanId
        } ?: return null
        val offer = offerId?.let {
            basePlan.offers.firstOrNull { it.offer.offerId == offerId }
        }
        val baseProductOffer = basePlan.offers.firstOrNull {
            it.offer.offerId == null
        } ?: return null
        val baseProductPrice = baseProductOffer.pricingPhases.firstOrNull() ?: return null
        return ProductWithOfferPrice(
            basePrice = baseProductPrice.priceMicros,
            localizedBasePrice = baseProductPrice.priceFormatted,
            currencyCode = baseProductPrice.currencyCode,
            billingPeriod = baseProductPrice.billingPeriod,
            targetOffer = offer?.run {
                getTargetOfferPrice(this.pricingPhases)
            } ?: emptyList())
    }

    @kotlinx.serialization.InternalSerializationApi
    private fun getTargetOfferPrice(phases: List<PricingPhase>): List<TargetOfferPrice> {
        return phases.map {
            TargetOfferPrice(
                priceDisplay = it.priceFormatted,
                billingPeriod = it.billingPeriod,
                price = it.priceMicros,
                billingCycleCount = it.billingCycleCount
            )
        }
    }
}

@kotlinx.serialization.InternalSerializationApi
@Serializable
internal data class ProductWithOfferPrice(
    val basePrice: Long,
    val localizedBasePrice: String,
    val currencyCode: String,
    val billingPeriod:String,
    val targetOffer: List<TargetOfferPrice>
)

@kotlinx.serialization.InternalSerializationApi
@Serializable
internal data class TargetOfferPrice(
    val priceDisplay: String,
    val billingPeriod: String,
    val price: Long,
    val billingCycleCount:Int = 0
)