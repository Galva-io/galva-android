package io.galva.network.android

import io.galva.common.InternalGalvaApi
import io.galva.common.logger.Logger
import io.galva.common.utils.JsonUtils
import io.galva.network.Endpoint
import io.galva.network.HttpClient
import io.galva.network.NetworkError
import io.galva.network.RequestBuilder
import io.galva.network.request.transactions.ObserveTransactionsRequest
import io.galva.network.service.ServiceResult
import io.galva.network.service.TransactionService
import io.galva.network.service.toServiceResult
import io.galva.network.toUrl
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@InternalGalvaApi
class HttpAPITransactionService(
    private val baseURL: String,
    private val httpClient: HttpClient,
    private val logger: Logger,
    private val json: Json = JsonUtils.defaultJson,
) : TransactionService {
    override suspend fun observeTransactions(request: ObserveTransactionsRequest): ServiceResult<Unit> {
        return try {
            val httpRequest = RequestBuilder.post(Endpoint.Transactions.toUrl(baseURL).plus("/observe"))
                .jsonBody(json.encodeToString(request))
                .build()
            httpClient.execute(httpRequest).toServiceResult { }
        } catch (e: NetworkError) {
            ServiceResult.Error(e)
        } catch (e: Exception) {
            logger.error(e) { "transactions/observe failed" }
            ServiceResult.UnknownError(e)
        }
    }
}
