package io.galva.billing.store

import io.galva.billing.model.Offer
import kotlinx.coroutines.flow.Flow

interface OfferStore {
    fun observeForBasePlan(basePlanId: String): Flow<List<Offer>>
    suspend fun get(offerToken: String): Offer?
    suspend fun replaceForBasePlan(basePlanId: String, offers: List<Offer>)
}