package io.galva.sdk.impl.adsvertise

import io.galva.common.utils.AdvertisingIdRetriever
import io.galva.core.protocol.operation.AdvertingProvider

class GMSAdvertisingProvider(private val retriever: AdvertisingIdRetriever) : AdvertingProvider {
    override fun adTrackingEnabled(): Boolean {
        return retriever.advertisingData?.enabled ?: false
    }

    override fun advertingId(): String? {
        return retriever.advertisingData?.id
    }

}