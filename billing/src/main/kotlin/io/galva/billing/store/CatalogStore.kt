package io.galva.billing.store

import io.galva.billing.model.FullCatalog
import io.galva.billing.model.Offer
import io.galva.billing.model.Product
import io.galva.billing.model.ProductCatalog
import kotlinx.coroutines.flow.Flow

interface  CatalogStore {
    /** One-shot full catalog snapshot. */
    suspend fun load(): FullCatalog

    /** Reactive — emits whenever any part of the catalog changes. */
    fun observe(): Flow<FullCatalog>

    fun observeProduct(sku: String): Flow<ProductCatalog?>

   suspend fun getProductWithOffers(sku: String): ProductCatalog?

    /** Convenience lookups (use indexed Room queries; fast). */
    suspend fun getProduct(sku: String): Product?
    suspend fun getOffer(token: String): Offer?
    suspend fun getOfferForPlan(basePlanId:String,offerId: String): Offer?

    /** Atomic write of the full graph. Used by refresh(). */
    suspend fun replaceAll(catalog: FullCatalog)

    /** Clear everything. */
    suspend fun clear()

}