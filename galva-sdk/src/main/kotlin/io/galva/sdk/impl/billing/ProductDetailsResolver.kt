package io.galva.sdk.impl.billing

import com.android.billingclient.api.ProductDetails

interface ProductDetailsResolver {
    suspend fun resolve(productIds: List<String>): List<ProductDetails>
}