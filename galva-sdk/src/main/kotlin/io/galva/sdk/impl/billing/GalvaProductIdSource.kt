package io.galva.sdk.impl.billing

import io.galva.billing.source.ProductIdSource
import io.galva.common.utils.JsonUtils
import io.galva.localstorage.core.KeyValueStorage
import io.galva.network.request.products.ListProductRequest
import io.galva.network.response.ListProductResponse
import io.galva.network.service.ProductService
import io.galva.network.service.ServiceResult
import io.galva.sdk.impl.store.SdkInitializeConfigStore
import kotlinx.coroutines.flow.mapLatest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class GalvaProductIdSource(
    private val configStore: SdkInitializeConfigStore
) : ProductIdSource {
    override suspend fun fetchProductIds(): List<String> {
      return configStore.currentConfig()?.data?.playStoreConfig?.productIds ?: emptyList()
    }

}