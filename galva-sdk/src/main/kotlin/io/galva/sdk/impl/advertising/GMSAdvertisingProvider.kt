package io.galva.sdk.impl.advertising

import io.galva.common.utils.AdvertisingIdRetriever
import io.galva.core.protocol.operation.AdvertisingProvider

internal class GMSAdvertisingProvider(private val retriever: AdvertisingIdRetriever) : AdvertisingProvider {
    override fun adTrackingEnabled(): Boolean {
        return retriever.advertisingData?.enabled ?: false
    }

    override fun advertisingId(): String? {
        return retriever.advertisingData?.id
    }

}