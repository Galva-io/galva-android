package io.galva.sdk.impl.billing

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

import kotlinx.serialization.json.Json

internal fun product(
    sku: String,
    title: String = "Title $sku",
    description: String = "Desc $sku",
    type: ProductType = ProductType.SUBSCRIPTION,
) = Product(sku = sku, title = title, description = description, type = type)

internal fun basePlan(
    id: String,
    productSku: String,
    tags: List<String> = emptyList(),
) = BasePlan(id = id, productSku = productSku, tags = tags)

internal fun offer(
    offerToken: String,
    basePlanId: String,
    offerId: String? = null,
    tags: List<String> = emptyList(),
) = Offer(offerToken = offerToken, offerId = offerId, basePlanId = basePlanId, tags = tags)

internal fun phase(
    id: String,
    offerToken: String,
    sequence: Int,
    productId:String = "p",
    basePlanId:String  = "bp",
    priceMicros: Long = 9_990_000L,
    priceFormatted: String = "$9.99",
    currencyCode: String = "USD",
    billingPeriod: String = "P1M",
    billingCycleCount: Int = 0,
    recurrenceMode: RecurrenceMode = RecurrenceMode.INFINITE_RECURRING,
) = PricingPhase(
    id = id, offerToken = offerToken,productId = productId, basePlanId = basePlanId, sequence = sequence,
    priceMicros = priceMicros, priceFormatted = priceFormatted,
    currencyCode = currencyCode, billingPeriod = billingPeriod,
    billingCycleCount = billingCycleCount, recurrenceMode = recurrenceMode,
)

internal fun simpleCatalog(
    productSku: String = "premium_monthly",
    basePlanId: String = "monthly",
    offerToken: String = "t1",
    offerId: String? = "intro",
): FullCatalog = FullCatalog(
    products = listOf(
        ProductCatalog(
            product = product(productSku),
            basePlans = listOf(
                BasePlanWithOffersModel(
                    basePlan = basePlan(basePlanId, productSku),
                    offers = listOf(
                        OfferWithPhasesModel(
                            offer = offer(offerToken, basePlanId, offerId),
                            pricingPhases = listOf(phase("${offerToken}_0", offerToken, 0, productId = productSku,basePlanId = basePlanId)),
                        ),
                    ),
                ),
            ),
        ),
    ),
)

internal val testJson = Json { ignoreUnknownKeys = true; explicitNulls = false }