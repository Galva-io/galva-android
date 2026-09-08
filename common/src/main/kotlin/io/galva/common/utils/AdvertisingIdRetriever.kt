package io.galva.common.utils

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class AdvertisingIdRetriever(context: Context, advertisingScope: CoroutineScope) {
    private val _advertisingData = MutableStateFlow<AdvertisingInfo?>(null)
    val advertisingData = _advertisingData.value
    init {
        advertisingScope.launch(Dispatchers.IO) {
            AdvertisingIdSource.getAndCacheGoogleAdvertisingId(context).run {
                _advertisingData.value = this
            }
        }
    }
}