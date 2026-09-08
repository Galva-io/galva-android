package io.galva.sdk.impl.billing

import com.android.billingclient.api.ProductDetails
import io.galva.billing.model.BasePlan
import io.galva.billing.model.BasePlanWithOffersModel
import io.galva.billing.model.FullCatalog
import io.galva.billing.model.Offer
import io.galva.billing.model.OfferWithPhasesModel
import io.galva.billing.model.PricingPhase
import io.galva.billing.model.Product
import io.galva.billing.model.ProductCatalog
import io.galva.billing.model.ProductType
import io.galva.billing.model.RecurrenceMode

interface ProductDetailsMapper {
    fun map(details: List<ProductDetails>): FullCatalog
}

class PlayBillingProductDetailsMapper :ProductDetailsMapper{

    override  fun map(details: List<ProductDetails>): FullCatalog =
        FullCatalog(
            products = details.map { pd ->
                ProductCatalog(
                    product = Product(
                        sku = pd.productId,
                        title = pd.title,
                        description = pd.description,
                        type = when (pd.productType) {
                            "subs"  -> ProductType.SUBSCRIPTION
                            "inapp" -> ProductType.ONE_TIME
                            else    -> ProductType.ONE_TIME
                        },
                    ),
                    basePlans = buildBasePlans(pd),
                )
            },
        )


    private fun buildBasePlans(pd: ProductDetails): List<BasePlanWithOffersModel> {
        // Subscriptions: group offers by basePlanId
        val subBasePlans = pd.subscriptionOfferDetails
            ?.groupBy { it.basePlanId }
            ?.map { (basePlanId, offerDetailsList) ->
                BasePlanWithOffersModel(
                    basePlan = BasePlan(id = basePlanId, productSku = pd.productId),
                    offers = offerDetailsList.map { od ->
                        OfferWithPhasesModel(
                            offer = Offer(
                                offerToken = od.offerToken,
                                offerId = od.offerId,
                                basePlanId = basePlanId,
                                tags = od.offerTags,
                            ),
                            pricingPhases = od.pricingPhases.pricingPhaseList
                                .mapIndexed { index, phase ->
                                    PricingPhase(
                                        id = "${od.offerToken}_${pd.productId}_${basePlanId}_$index",
                                        offerToken = od.offerToken,
                                        productId = pd.productId,
                                        basePlanId = basePlanId,
                                        sequence = index,
                                        priceMicros = phase.priceAmountMicros,
                                        priceFormatted = phase.formattedPrice,
                                        currencyCode = phase.priceCurrencyCode,
                                        billingPeriod = phase.billingPeriod,
                                        billingCycleCount = phase.billingCycleCount,
                                        recurrenceMode = phase.recurrenceMode.toRecurrenceMode(),
                                    )
                                },
                        )
                    },
                )
            }
            .orEmpty()

        // One-time products: synthetic base plan + offer + single phase
        val oneTimeBasePlan = pd.oneTimePurchaseOfferDetails?.let { oneTime ->
            val syntheticOfferToken = "${pd.productId}_onetime"
            BasePlanWithOffersModel(
                basePlan = BasePlan(id = pd.productId, productSku = pd.productId),
                offers = listOf(
                    OfferWithPhasesModel(
                        offer = Offer(
                            offerToken = syntheticOfferToken,
                            offerId = null,
                            basePlanId = pd.productId,
                            tags = emptyList(),
                        ),
                        pricingPhases = listOf(
                            PricingPhase(
                                id = "${syntheticOfferToken}_0",
                                offerToken = syntheticOfferToken,
                                productId = pd.productId,
                                basePlanId = pd.productId, //same as base plan id since it's synthetic
                                sequence = 0,
                                priceMicros = oneTime.priceAmountMicros,
                                priceFormatted = oneTime.formattedPrice,
                                currencyCode = oneTime.priceCurrencyCode,
                                billingPeriod = "",
                                billingCycleCount = 0,
                                recurrenceMode = RecurrenceMode.NON_RECURRING,
                            ),
                        ),
                    ),
                ),
            )
        }

        return subBasePlans + listOfNotNull(oneTimeBasePlan)
    }

    private fun Int.toRecurrenceMode(): RecurrenceMode = when (this) {
        ProductDetails.RecurrenceMode.INFINITE_RECURRING -> RecurrenceMode.INFINITE_RECURRING
        ProductDetails.RecurrenceMode.FINITE_RECURRING   -> RecurrenceMode.FINITE_RECURRING
        ProductDetails.RecurrenceMode.NON_RECURRING      -> RecurrenceMode.NON_RECURRING
        else -> RecurrenceMode.NON_RECURRING
    }
}
