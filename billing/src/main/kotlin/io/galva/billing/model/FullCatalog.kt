package io.galva.billing.model

data class FullCatalog(
    val products: List<ProductCatalog>,
)

internal fun FullCatalog.flatten(): FlatCatalog {
    val products = mutableListOf<Product>()
    val basePlans = mutableListOf<BasePlan>()
    val offers = mutableListOf<Offer>()
    val phases = mutableListOf<PricingPhase>()

    products.forEach { /* no-op — populated below */ }
    this.products.forEach { pc ->
        products += pc.product
        pc.basePlans.forEach { bp ->
            basePlans += bp.basePlan
            bp.offers.forEach { ow ->
                offers += ow.offer
                phases += ow.pricingPhases
            }
        }
    }
    return FlatCatalog(products, basePlans, offers, phases)
}

internal data class FlatCatalog(
    val products: List<Product>,
    val basePlans: List<BasePlan>,
    val offers: List<Offer>,
    val pricingPhases: List<PricingPhase>,
)

data class ProductCatalog(
    val product: Product,
    val basePlans: List<BasePlanWithOffersModel>,
)

data class BasePlanWithOffersModel(
    val basePlan: BasePlan,
    val offers: List<OfferWithPhasesModel>,
)

data class OfferWithPhasesModel(
    val offer: Offer,
    val pricingPhases: List<PricingPhase>,
)

fun FullCatalog.findProduct(sku: String): ProductCatalog? =
    products.firstOrNull { it.product.sku == sku }

fun FullCatalog.findBasePlan(basePlanId: String): BasePlanWithOffersModel? =
    products.firstNotNullOfOrNull { p ->
        p.basePlans.firstOrNull { it.basePlan.id == basePlanId }
    }

fun FullCatalog.findOffer(offerToken: String): OfferWithPhasesModel? =
    products.firstNotNullOfOrNull { p ->
        p.basePlans.firstNotNullOfOrNull { bp ->
            bp.offers.firstOrNull { it.offer.offerToken == offerToken }
        }
    }

/** All offers across all products. */
fun FullCatalog.allOffers(): List<OfferWithPhasesModel> =
    products.flatMap { p ->
        p.basePlans.flatMap { bp -> bp.offers }
    }

/** All pricing phases across all offers. */
fun FullCatalog.allPricingPhases(): List<PricingPhase> =
    allOffers().flatMap { it.pricingPhases }