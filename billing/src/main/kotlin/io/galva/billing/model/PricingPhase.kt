package io.galva.billing.model
import kotlinx.serialization.Serializable

@Serializable
data class PricingPhase(
    val id: String,
    val offerToken: String,
    val basePlanId:String,
    val productId:String,// FK → Offer.offerToken
    val sequence: Int,
    val priceMicros: Long,
    val priceFormatted: String,
    val currencyCode: String,
    val billingPeriod: String,
    val billingCycleCount: Int,
    val recurrenceMode: RecurrenceMode,
)

@Serializable
enum class RecurrenceMode {
    INFINITE_RECURRING,
    FINITE_RECURRING,
    NON_RECURRING,
}