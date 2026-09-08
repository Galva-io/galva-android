package io.galva.billing.store

import io.galva.billing.model.Product
import kotlinx.coroutines.flow.Flow

interface ProductStore {
    suspend fun get(sku: String): Product?
}