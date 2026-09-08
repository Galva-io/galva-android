package io.galva.billing.source

interface ProductIdSource {
    /** Fetch the product SKUs to query from Play Billing. */
    suspend fun fetchProductIds(): List<String>
}