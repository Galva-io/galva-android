package io.galva.network.service

import io.galva.common.InternalGalvaApi
import io.galva.network.request.transactions.ObserveTransactionsRequest

@InternalGalvaApi
interface TransactionService {

    /** `POST /v1/transactions/observe`. The response body is not read. */
    suspend fun observeTransactions(request: ObserveTransactionsRequest): ServiceResult<Unit>
}
