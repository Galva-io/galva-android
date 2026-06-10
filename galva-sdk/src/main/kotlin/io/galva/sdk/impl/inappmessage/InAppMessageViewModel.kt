package io.galva.sdk.impl.inappmessage

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.WindowInsets
import androidx.core.graphics.Insets
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.galva.billing.BillingManager
import io.galva.billing.model.OfferWithPhasesModel
import io.galva.billing.model.PricingPhase
import io.galva.common.utils.AppInfoSource
import io.galva.common.utils.HashUtils
import io.galva.common.utils.JsonUtils
import io.galva.iam.PageContext
import io.galva.iam.SafeArea
import io.galva.network.request.APIFetchRequest
import io.galva.network.response.APIFetchResult
import io.galva.network.service.IAMService
import io.galva.network.service.ServiceResult
import io.galva.sdk.Galva
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.Locale

class InAppMessageViewModel(
    private val billingManager: BillingManager,
    private val iamService: IAMService
) : ViewModel() {
    fun launchBillingFlow(activity: Activity, productId: String, basePlanId: String, offerId: String) =
        billingManager.launchBilling(
            activity = activity,
            productId = productId,
            basePlanId = basePlanId,
            offerId = offerId,
            obfuscatedAccountId = HashUtils.hashToSafeToken(Galva.instance.obfuscatedAccountId)
        )

    private var _storefrontCountryCode: String? = null
    val storefrontCountryCode
        get() = _storefrontCountryCode

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _storefrontCountryCode = billingManager.getStorefrontCountryCode()
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
            safeArea = windowInsets?.run {
                SafeArea(
                    top = top, left = left, right = right, bottom = bottom
                )
            })
    }

    suspend fun doApiFetch(apiFetchRequest: APIFetchRequest): ServiceResult<APIFetchResult> {
        val response = iamService.apiFetch(apiFetchRequest)
        return response
    }

    @kotlinx.serialization.InternalSerializationApi
    suspend fun getProductPrice(
        productId: String, basePlanId: String, offerId: String?
    ): ProductWithOfferPrice? {
        val product = billingManager.getProductCatalog(productId)
        if(product == null){
            println("Galva InAppMessageViewModel.getProductPrice: product is null for productId=$productId")
            return null
        }
        println("Galva InAppMessageViewModel.getProductPrice: product=$product")
        val basePlan = product.basePlans.firstOrNull {
            it.basePlan.id == basePlanId
        } ?: return null
        println("Galva InAppMessageViewModel.getProductPrice: basePlan=$basePlan")
        val offer = offerId?.let {
            basePlan.offers.firstOrNull { it.offer.offerId == offerId }
        }
        println("Galva InAppMessageViewModel.getProductPrice: offer=$offer")
        val baseProductOffer = basePlan.offers.firstOrNull {
            it.offer.offerId == null
        } ?: return null
        println("Galva InAppMessageViewModel.getProductPrice: baseProductOffer=$baseProductOffer")
        val baseProductPrice = baseProductOffer.pricingPhases.firstOrNull() ?: return null
        println("Galva InAppMessageViewModel.getProductPrice: baseProductPrice=$baseProductPrice")
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
data class ProductWithOfferPrice(
    val basePrice: Long,
    val localizedBasePrice: String,
    val currencyCode: String,
    val billingPeriod:String,
    val targetOffer: List<TargetOfferPrice>
)

@kotlinx.serialization.InternalSerializationApi
@Serializable
data class TargetOfferPrice(
    val priceDisplay: String,
    val billingPeriod: String,
    val price: Long,
    val billingCycleCount:Int = 0
)