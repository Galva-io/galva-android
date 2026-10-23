package io.galva.billing.store

import io.galva.common.InternalGalvaApi
import io.galva.billing.model.PricingPhase
import kotlinx.coroutines.flow.Flow

@InternalGalvaApi
interface PricingPhaseStore {
    fun observeForOffer(offerToken: String): Flow<List<PricingPhase>>
    suspend fun findForOffer(offerToken: String): List<PricingPhase>
    suspend fun replaceForOffer(offerToken: String, phases: List<PricingPhase>)
}