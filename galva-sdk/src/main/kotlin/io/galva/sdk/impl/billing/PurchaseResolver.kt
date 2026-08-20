package io.galva.sdk.impl.billing

import com.android.billingclient.api.Purchase
import kotlinx.coroutines.flow.Flow

interface PurchaseResolver {
     fun loadPurchases(): Flow<PlayPurchaseEvents.Event>
}