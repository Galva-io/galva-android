package io.galva.billing.store

import io.galva.common.InternalGalvaApi
import io.galva.billing.model.Product
import kotlinx.coroutines.flow.Flow

@InternalGalvaApi
interface ProductStore {
    suspend fun get(sku: String): Product?
}