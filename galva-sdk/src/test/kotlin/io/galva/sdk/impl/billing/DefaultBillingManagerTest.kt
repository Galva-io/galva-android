package io.galva.sdk.impl.billing

import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import io.galva.billing.model.BillingLaunchState
import io.galva.billing.model.FullCatalog
import io.galva.common.logger.LogLevel
import io.galva.common.logger.Logger
import io.galva.common.logger.NoOpLogger
import io.galva.sdk.TestLogSink
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
class DefaultBillingManagerTest {
    private lateinit var connection: FakeBillingConnection
    private lateinit var idSource: FakeProductIdSource
    private lateinit var resolver: FakeProductDetailsResolver
    private lateinit var mapper: FakeProductDetailsMapper
    private lateinit var catalogStore: FakeCatalogStore
    private lateinit var productDetailsCache: FakeProductDetailsCache
    private lateinit var launcher: FakeBillingLauncher
    private lateinit var manager: DefaultBillingManager
    private lateinit var activity: Activity

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).create().get()
        connection = FakeBillingConnection()
        idSource = FakeProductIdSource()
        resolver = FakeProductDetailsResolver()
        mapper = FakeProductDetailsMapper(output = simpleCatalog())
        catalogStore = FakeCatalogStore()
        productDetailsCache = FakeProductDetailsCache()
        launcher = FakeBillingLauncher(emptyList())
        manager = makeManager()
    }

    private fun makeManager(
        scope: CoroutineScope = TestScope(),
        testDispatcher: CoroutineDispatcher = Dispatchers.Default
    ) = DefaultBillingManager(
        connection = connection,
        productIdSource = idSource,
        resolver = resolver,
        mapper = mapper,
        catalogStore = catalogStore,
        productDetailsCache = productDetailsCache,
        launcher = launcher,
        logger = NoOpLogger,
        scope = scope,
        launchDispatcher = testDispatcher
    )

    @Test
    fun `auto reconnection enabled on Play Billing 9_0`() = runTest {

        val sinkTest = TestLogSink()
        val factory = BillingClientFactory(
            activity,
            { p0, p1 -> },
            Logger.create("Test", sinkTest, LogLevel.VERBOSE),
            object : BillingCompat {
                override fun enableAutoServiceReconnection(builder: BillingClient.Builder): Boolean {
                    return true
                }

            })
        factory.create()
        // Verify reflection succeeded — log message captured
        assertTrue(sinkTest.messages.any { it.contains("Enabled auto service reconnection") })
    }

    @Test
    fun `falls back to manual reconnection on Play Billing 8_0`() = runTest {
        // Simulate 8.0 by ensuring enableAutoServiceReconnection doesn't exist
        // This is hard to test without actual 8.0 binaries; document as caveat
        val sinkTest = TestLogSink()
        val factory = BillingClientFactory(
            activity, { p0, p1 -> }, Logger.create("Test", sinkTest),
            object : BillingCompat {
                override fun enableAutoServiceReconnection(builder: BillingClient.Builder): Boolean {
                    return false
                }
            }
        )
        factory.create()
        assertTrue(sinkTest.messages.any { it.contains("Auto service reconnection not supported") })
    }

    // ── refresh() ──────────────────────────────────────────────────────────

    @Test
    fun `refresh fetches ids resolves details and persists catalog`() = runTest {
        idSource.ids = listOf("p1", "p2")
        manager.loadProducts()
        assertEquals(1, idSource.fetchCount)
        assertEquals(listOf(idSource.ids), resolver.queries)
        assertEquals(1, productDetailsCache.replaceCalls)
        assertEquals(1, catalogStore.replaceCalls)
    }

    @Test
    fun `refresh with empty ids clears catalog and cache`() = runTest {
        idSource.ids = emptyList()
        manager.loadProducts()
        assertEquals(0, resolver.queries.size)
        assertEquals(1, catalogStore.clearCalls)
        assertEquals(1, productDetailsCache.clearCalls)
    }

    @Test
    fun `refresh swallows id source failure`() = runTest {
        catalogStore.replaceAll(simpleCatalog(productSku = "existing"))
        catalogStore.replaceCalls                                  // reset note
        idSource.failure = RuntimeException("API down")

        manager.initialize()
        advanceUntilIdle()

        assertEquals(0, productDetailsCache.replaceCalls)
        assertEquals(0, catalogStore.clearCalls)
        // Existing catalog preserved
        assertEquals("existing", catalogStore.load().products.single().product.sku)
    }

    @Test
    fun `refresh swallows resolver failure`() = runTest {
        idSource.ids = listOf("p1")
        resolver.failure = RuntimeException("Play crashed")

        manager.initialize()
        advanceUntilIdle()

        assertEquals(0, productDetailsCache.replaceCalls)
        assertEquals(0, catalogStore.replaceCalls)
    }

    @Test
    fun `concurrent refresh serialized by mutex`() = runTest {
        idSource.ids = listOf("p1")
        idSource.delayMs = 500

        val a = launch { manager.loadProducts() }
        val b = launch { manager.loadProducts() }
        advanceTimeBy(50.milliseconds);
        runCurrent()

        assertEquals(1, idSource.fetchCount)

        advanceTimeBy(500.milliseconds);
        runCurrent()
        advanceUntilIdle()

        assertEquals(2, idSource.fetchCount)
        a.join(); b.join()
    }

    @Test
    fun `warmUp triggers connection`() = runTest {
        val realManager = makeManager(scope = backgroundScope)
        realManager.initialize()
        runCurrent()
        assertEquals(1, connection.ensureConnectedCalls)
    }

    // ── launchBilling() ───────────────────────────────────────────────────

    @Test
    fun `launchBilling with missing product emits Failed immediately`() = runTest {
        val states = manager.launchBilling(activity, "missing", "bpi", "t").toList()

        assertEquals(1, states.size)
        val failed = states.single() as BillingLaunchState.Failed
        assertEquals("Product not found", failed.reason)
        assertEquals(0, launcher.calls.size)
    }

    @Test
    fun `launchBilling with missing offer emits Failed`() = runTest {
        catalogStore.replaceAll(simpleCatalog(productSku = "p", offerToken = "t"))

        val states = manager.launchBilling(activity, "p", "bpi", "wrong-token").toList()

        assertEquals(1, states.size)
        assertEquals("Offer not found", (states.single() as BillingLaunchState.Failed).reason)
        assertEquals(0, launcher.calls.size)
    }

    @Test
    fun `launchBilling emits all launcher states`() = runTest {
        val expected = listOf(
            BillingLaunchState.Launching("p", "t"),
            BillingLaunchState.Showing("p", "t"),
            BillingLaunchState.Completed("p", "t", "tok", "ord", 0L, true),
        )
        launcher = FakeBillingLauncher(states = expected)
        manager = makeManager()
        catalogStore.replaceAll(simpleCatalog(productSku = "p", offerToken = "t"))

        val emitted = manager.launchBilling(activity, "p", "monthly", "intro").toList()
        assertEquals(expected, emitted)
    }

    @Test
    fun `launchBilling passes obfuscatedAccountId through`() = runTest {
        launcher = FakeBillingLauncher(states = listOf(BillingLaunchState.Launching("p", "t")))
        manager = makeManager()
        catalogStore.replaceAll(simpleCatalog(productSku = "p", offerToken = "t"))

        manager.launchBilling(activity, "p", "monthly", "intro", obfuscatedAccountId = "user-42")
            .toList()

        assertEquals(1, launcher.calls.size)
        assertEquals("user-42", launcher.calls.single().obfuscatedAccountId)
    }

    @Test
    fun `latestLaunchState reflects final emitted state`() = runTest {
        val states = listOf(
            BillingLaunchState.Launching("p", "t"),
            BillingLaunchState.Showing("p", "t"),
            BillingLaunchState.Completed("p", "t", "tok", "ord", 0L, true),
        )
        launcher = FakeBillingLauncher(states = states)
        manager = makeManager()
        catalogStore.replaceAll(simpleCatalog(productSku = "p", offerToken = "t"))

        manager.launchBilling(activity, "p", "monthly", "intro").toList()
        advanceUntilIdle()

        assertEquals(states.last(), manager.latestLaunchState.value)
    }

    @Test
    fun `latestLaunchState updated for failed launch too`() = runTest {
        val states = manager.launchBilling(activity, "missing", "bpi", "t").toList()
        advanceUntilIdle()

        assertTrue(manager.latestLaunchState.value is BillingLaunchState.Failed)
    }

    @Test
    fun `catalog flow reflects store updates`() = runTest {
        val emissions = mutableListOf<FullCatalog>()
        val job = launch { manager.catalog.take(3).collect { emissions += it } }
        runCurrent()

        catalogStore.replaceAll(simpleCatalog(productSku = "p1"))
        runCurrent()
        catalogStore.replaceAll(simpleCatalog(productSku = "p2"))
        advanceUntilIdle()

        assertEquals(
            listOf(null, "p1", "p2"),
            emissions.map { it.products.firstOrNull()?.product?.sku },
        )
        job.cancel()
    }

    // ── shutdown() ────────────────────────────────────────────────────────

    @Test
    fun `shutdown ends connection clears cache resets state`() = runTest {
        val realManager = makeManager(scope = backgroundScope)
        catalogStore.replaceAll(simpleCatalog(productSku = "p", offerToken = "t"))
        // Trigger a launch so latestLaunchState is non-null
        launcher = FakeBillingLauncher(
            states = listOf(
                BillingLaunchState.Completed(
                    "p", "t", "tok", "ord", 0L, true
                )
            )
        )
        val mgr = makeManager(scope = backgroundScope)
        mgr.launchBilling(activity, "p", "bpi", "t").toList()

        mgr.shutdown()
        runCurrent()

        assertEquals(1, connection.endConnectionCalls)
        assertEquals(1, productDetailsCache.clearCalls)
        assertNull(mgr.latestLaunchState.value)
    }

    // ── Connection-aware behavior ────────────────────────────────────────

    @Test
    fun `launch handles connection failure via launcher Failed`() = runTest {
        // Note: in production, the launcher itself handles ensureConnected — the manager doesn't.
        // This test verifies the manager just passes through whatever the launcher emits.
        launcher = FakeBillingLauncher(
            states = listOf(
                BillingLaunchState.Launching("p", "t"),
                BillingLaunchState.Failed("p", "t", "Billing not connected"),
            )
        )
        manager = makeManager()
        catalogStore.replaceAll(simpleCatalog(productSku = "p", offerToken = "t"))

        val emitted = manager.launchBilling(activity, "p", "monthly", "intro").toList()
        assertEquals(2, emitted.size)
        assertTrue(emitted.last() is BillingLaunchState.Failed)
    }
}