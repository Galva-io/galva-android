package io.galva.sdk.impl.billing

import com.android.billingclient.api.ProductDetails
import java.util.concurrent.ConcurrentHashMap

internal interface ProductDetailsCache {
    fun get(sku: String): ProductDetails?
    fun replace(details: List<ProductDetails>)
    fun clear()
}

internal class InMemoryProductDetailsCache : ProductDetailsCache {
    private val cache = ConcurrentHashMap<String, ProductDetails>()
    override fun get(sku: String): ProductDetails? = cache[sku]
    override fun replace(details: List<ProductDetails>) {
        cache.clear()
        details.forEach { cache[it.productId] = it }
    }

    override fun clear() = cache.clear()
}