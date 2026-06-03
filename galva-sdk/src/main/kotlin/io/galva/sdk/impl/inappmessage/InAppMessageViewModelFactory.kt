package io.galva.sdk.impl.inappmessage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.galva.billing.BillingManager
import io.galva.network.service.IAMService

class InAppMessageViewModelFactory(private val billingManager: BillingManager,
    private val iamService: IAMService) :
    ViewModelProvider.Factory{
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(InAppMessageViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return InAppMessageViewModel(billingManager,iamService) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}