package io.galva.sdk.impl.transaction

import io.galva.common.logger.NoOpLogger
import io.galva.identity.Identity
import io.galva.network.NetworkError
import io.galva.network.request.transactions.ObserveTransactionsRequest
import io.galva.network.request.transactions.ObservedTransaction
import io.galva.network.service.ServiceResult
import io.galva.network.service.TransactionService
import io.galva.sdk.impl.billing.PlayPurchases
import io.galva.sdk.impl.billing.PlayTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionObserverTest {

    private class FakeService : TransactionService {
        val requests = mutableListOf<ObserveTransactionsRequest>()
        var result: ServiceResult<Unit> = ServiceResult.Success(Unit)

        override suspend fun observeTransactions(request: ObserveTransactionsRequest): ServiceResult<Unit> {
            requests += request
            return result
        }
    }

    private class FakePurchases(var current: List<PlayTransaction>) : PlayPurchases {
        var queries = 0
        override val updates: Flow<List<PlayTransaction>> = emptyFlow()
        override suspend fun query(): List<PlayTransaction> {
            queries++
            return current
        }
    }

    private val service = FakeService()
    private var identity = Identity(anonymousId = "anonymous-123", obfuscatedAccountId = "acc", firstCreated = false)
    private var optedOut = false
    private val observer = TransactionObserver(service, { identity }, { optedOut }, NoOpLogger)

    private fun purchase(orderId: String, time: Long, purchased: Boolean = true) =
        PlayTransaction(orderId, "token-$orderId", time, purchased)

    private val sent: List<ObservedTransaction> get() = service.requests.flatMap { it.transactions }

    @Test
    fun `reports only the newest purchased transaction`() = runTest {
        observer.onPurchasesUpdated(
            listOf(
                purchase("GPA.old", time = 1_000),
                purchase("GPA.new", time = 3_000),
                purchase("GPA.pending", time = 5_000, purchased = false),
            ),
        )

        assertEquals(listOf(ObservedTransaction("GPA.new", "token-GPA.new")), sent)
    }

    @Test
    fun `sends the current user's ids`() = runTest {
        identity = identity.copy(userId = "user-123")

        observer.onPurchasesUpdated(listOf(purchase("GPA.1", time = 1)))

        val request = service.requests.single()
        assertEquals("anonymous-123", request.anonymousId)
        assertEquals("user-123", request.endUserId)
    }

    @Test
    fun `nothing purchased sends nothing`() = runTest {
        observer.onPurchasesUpdated(listOf(purchase("GPA.pending", time = 1, purchased = false)))
        observer.sweep(FakePurchases(emptyList()))

        assertTrue(service.requests.isEmpty())
    }

    @Test
    fun `app entry queries Play and reports a purchase once per process`() = runTest {
        val play = FakePurchases(listOf(purchase("GPA.1", time = 1)))

        observer.sweep(play)
        observer.sweep(play)
        play.current = listOf(purchase("GPA.1", time = 1), purchase("GPA.1..0", time = 2)) // a renewal
        observer.sweep(play)

        assertEquals(listOf("GPA.1", "GPA.1..0"), sent.map { it.orderId })
        assertEquals(3, play.queries)
    }

    @Test
    fun `a failed report is retried next time`() = runTest {
        val play = FakePurchases(listOf(purchase("GPA.1", time = 1)))
        service.result = ServiceResult.Error(NetworkError.NoConnectivity())

        observer.sweep(play)
        service.result = ServiceResult.Success(Unit)
        observer.sweep(play)
        observer.sweep(play)

        assertEquals(2, service.requests.size)
    }

    @Test
    fun `nothing is read or sent while opted out`() = runTest {
        optedOut = true
        val play = FakePurchases(listOf(purchase("GPA.1", time = 1)))

        observer.sweep(play)
        observer.onPurchasesUpdated(play.current)

        assertTrue(service.requests.isEmpty())
        assertEquals(0, play.queries)
    }

    @Test
    fun `a message purchase is reported right away`() = runTest {
        observer.onTransactionCompleted(purchase("GPA.msg", time = 1))

        assertEquals(listOf(ObservedTransaction("GPA.msg", "token-GPA.msg")), sent)
    }

    @Test
    fun `a purchase seen by both the purchase listener and a controller is sent once`() = runTest {
        observer.onPurchasesUpdated(listOf(purchase("GPA.1", time = 1)))
        observer.onTransactionCompleted(purchase("GPA.1", time = 1))
        observer.sweep(FakePurchases(listOf(purchase("GPA.1", time = 1))))

        assertEquals(1, service.requests.size)
    }

    @Test
    fun `after reset the purchase is reported again`() = runTest {
        val play = FakePurchases(listOf(purchase("GPA.1", time = 1)))

        observer.sweep(play)
        observer.reset()
        observer.sweep(play)

        assertEquals(2, service.requests.size)
    }
}
