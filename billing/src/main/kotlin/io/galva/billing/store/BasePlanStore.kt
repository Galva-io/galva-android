package io.galva.billing.store

import io.galva.billing.model.BasePlan
import kotlinx.coroutines.flow.Flow

interface BasePlanStore {
    fun observeForProduct(productSku: String): Flow<List<BasePlan>>
    suspend fun get(id: String): BasePlan?
    suspend fun replaceForProduct(productSku: String, plans: List<BasePlan>)
}