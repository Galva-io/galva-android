package io.galva.billing.model

data class Catalog(
    val products: List<Product>,
    val basePlans: List<BasePlan>,
    val offers: List<Offer>,
    val pricingPhases: List<PricingPhase>,
)
