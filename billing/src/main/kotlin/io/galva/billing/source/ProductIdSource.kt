package io.galva.billing.source

import io.galva.common.InternalGalvaApi

@InternalGalvaApi
interface ProductIdSource {
    /** Fetch the product SKUs to query from Play Billing. */
    suspend fun fetchProductIds(): List<String>
}