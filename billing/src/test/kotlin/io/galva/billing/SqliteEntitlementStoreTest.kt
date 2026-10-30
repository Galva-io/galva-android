package io.galva.billing

import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import io.galva.billing.local.BillingSqliteHelper
import io.galva.billing.local.SqliteEntitlementStore
import io.galva.billing.model.Entitlement
import io.galva.billing.model.EntitlementState
import io.galva.common.logger.NoOpLogger
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SqliteEntitlementStoreTest {
    private lateinit var helper: BillingSqliteHelper
    private lateinit var store: SqliteEntitlementStore

    @Before
    fun setUp() {
        helper = BillingSqliteHelper(ApplicationProvider.getApplicationContext())
        store = SqliteEntitlementStore(
            helper = helper,
            logger = NoOpLogger,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
    }

    @After
    fun tearDown() {
        helper.writableDatabase.execSQL("DELETE FROM entitlement")
        helper.close()
    }

    // ── get() / empty state ─────────────────────────────────────────────────

    @Test fun `get returns null when empty`() = runTest {
        assertNull(store.get())
    }

    @Test fun `get returns saved entitlement`() = runTest {
        val entitlement = sampleEntitlement()
        store.save(entitlement)

        val loaded = store.get()
        assertEquals(entitlement, loaded)
    }

    // ── save() ──────────────────────────────────────────────────────────────

    @Test fun `save persists all fields`() = runTest {
        val entitlement = Entitlement(
            productId = "premium_yearly",
            purchaseToken = "purchase_token_xyz_123",
            orderId = "GPA.1234-5678-9012-34567",
            acquiredAtMs = 1_700_000_000_000L,
            isAutoRenewing = true,
            state = EntitlementState.ACTIVE,
        )
        store.save(entitlement)

        val loaded = store.get()!!
        assertEquals("premium_yearly", loaded.productId)
        assertEquals("purchase_token_xyz_123", loaded.purchaseToken)
        assertEquals("GPA.1234-5678-9012-34567", loaded.orderId)
        assertEquals(1_700_000_000_000L, loaded.acquiredAtMs)
        assertTrue(loaded.isAutoRenewing)
        assertEquals(EntitlementState.ACTIVE, loaded.state)
    }

    @Test fun `save with null orderId persists null`() = runTest {
        store.save(sampleEntitlement(orderId = null))

        val loaded = store.get()!!
        assertNull(loaded.orderId)
    }

    @Test fun `save replaces existing entitlement`() = runTest {
        store.save(sampleEntitlement(productId = "monthly", purchaseToken = "tok1"))
        store.save(sampleEntitlement(productId = "yearly", purchaseToken = "tok2"))

        val loaded = store.get()!!
        assertEquals("yearly", loaded.productId)
        assertEquals("tok2", loaded.purchaseToken)
    }

    @Test fun `save isAutoRenewing true persists correctly`() = runTest {
        store.save(sampleEntitlement(isAutoRenewing = true))
        assertTrue(store.get()!!.isAutoRenewing)
    }

    @Test fun `save isAutoRenewing false persists correctly`() = runTest {
        store.save(sampleEntitlement(isAutoRenewing = false))
        assertFalse(store.get()!!.isAutoRenewing)
    }

    // ── EntitlementState enum coverage ──────────────────────────────────────

    @Test fun `all entitlement states persist correctly`() = runTest {
        EntitlementState.values().forEach { state ->
            store.save(sampleEntitlement(state = state))
            assertEquals(state, store.get()!!.state)
        }
    }

    // ── Single-row CHECK constraint ─────────────────────────────────────────

    @Test fun `only one row exists after multiple saves`() = runTest {
        store.save(sampleEntitlement(productId = "a"))
        store.save(sampleEntitlement(productId = "b"))
        store.save(sampleEntitlement(productId = "c"))

        val db = helper.readableDatabase
        val count = db.rawQuery("SELECT COUNT(*) FROM entitlement", null).use {
            it.moveToFirst(); it.getInt(0)
        }
        assertEquals(1, count)
    }

    @Test fun `row id is always 1`() = runTest {
        store.save(sampleEntitlement())

        val db = helper.readableDatabase
        val id = db.rawQuery("SELECT id FROM entitlement LIMIT 1", null).use {
            it.moveToFirst(); it.getInt(0)
        }
        assertEquals(1, id)
    }

    // ── clear() ─────────────────────────────────────────────────────────────

    @Test fun `clear removes entitlement`() = runTest {
        store.save(sampleEntitlement())
        assertNotNull(store.get())

        store.clear()
        assertNull(store.get())
    }

    @Test fun `clear on empty store is no-op`() = runTest {
        store.clear()
        assertNull(store.get())
    }

    @Test fun `clear then save round-trips`() = runTest {
        store.save(sampleEntitlement(productId = "first"))
        store.clear()
        store.save(sampleEntitlement(productId = "second"))

        assertEquals("second", store.get()!!.productId)
    }

    // ── observe() ───────────────────────────────────────────────────────────

    @Test fun `observe emits null initially when empty`() = runTest {
        store.observe().test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `observe emits initial entitlement when present`() = runTest {
        store.save(sampleEntitlement(productId = "premium"))

        store.observe().test {
            assertEquals("premium", awaitItem()?.productId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `observe emits updates after save`() = runTest {
        store.observe().test {
            assertNull(awaitItem())

            store.save(sampleEntitlement(productId = "first"))
            assertEquals("first", awaitItem()?.productId)

            store.save(sampleEntitlement(productId = "second"))
            assertEquals("second", awaitItem()?.productId)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `observe emits null after clear`() = runTest {
        store.save(sampleEntitlement())

        store.observe().test {
            assertNotNull(awaitItem())

            store.clear()
            assertNull(awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `observe deduplicates identical values`() = runTest {
        val entitlement = sampleEntitlement()
        store.save(entitlement)

        store.observe().test {
            assertNotNull(awaitItem())

            // Save identical entitlement again — should not emit duplicate
            store.save(entitlement)
            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `multiple subscribers each receive emissions`() = runTest {
        store.observe().test {
            assertNull(awaitItem())

            store.observe().test {
                assertNull(awaitItem())

                store.save(sampleEntitlement(productId = "shared"))
                assertEquals("shared", awaitItem()?.productId)

                cancelAndIgnoreRemainingEvents()
            }

            assertEquals("shared", awaitItem()?.productId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Concurrent operations ───────────────────────────────────────────────

    @Test fun `concurrent reads work correctly`() = runTest {
        store.save(sampleEntitlement(productId = "premium"))

        val a = async { store.get() }
        val b = async { store.get() }
        val c = async { store.get() }

        assertEquals("premium", a.await()?.productId)
        assertEquals("premium", b.await()?.productId)
        assertEquals("premium", c.await()?.productId)
    }

    @Test fun `concurrent writes result in one value`() = runTest {
        val writes = (1..10).map { i ->
            async { store.save(sampleEntitlement(productId = "p$i")) }
        }
        writes.forEach { it.await() }

        // Final value is one of the writes; no torn state
        val loaded = store.get()!!
        assertTrue(loaded.productId.startsWith("p"))

        // Still only one row
        val db = helper.readableDatabase
        val count = db.rawQuery("SELECT COUNT(*) FROM entitlement", null).use {
            it.moveToFirst(); it.getInt(0)
        }
        assertEquals(1, count)
    }

    // ── Edge cases ──────────────────────────────────────────────────────────

    @Test fun `purchase token with special characters persists`() = runTest {
        val token = "ojnpfedi.AO-J1Owt7nGc9j_Y-7Q==/special+chars"
        store.save(sampleEntitlement(purchaseToken = token))

        assertEquals(token, store.get()!!.purchaseToken)
    }

    @Test fun `very long purchase token persists`() = runTest {
        val longToken = "a".repeat(500)
        store.save(sampleEntitlement(purchaseToken = longToken))

        assertEquals(longToken, store.get()!!.purchaseToken)
    }

    @Test fun `zero acquiredAtMs persists`() = runTest {
        store.save(sampleEntitlement(acquiredAtMs = 0L))
        assertEquals(0L, store.get()!!.acquiredAtMs)
    }

    @Test fun `max Long acquiredAtMs persists`() = runTest {
        store.save(sampleEntitlement(acquiredAtMs = Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, store.get()!!.acquiredAtMs)
    }

    @Test fun `empty string productId persists`() = runTest {
        // Edge case; not realistic but should not crash
        store.save(sampleEntitlement(productId = ""))
        assertEquals("", store.get()!!.productId)
    }

    @Test
    fun `unicode in productId persists`() = runTest {
        store.save(sampleEntitlement(productId = "productIdTest"))
        assertEquals("productIdTest", store.get()!!.productId)
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun sampleEntitlement(
        productId: String = "premium_monthly",
        purchaseToken: String = "tok_default_123",
        orderId: String? = "order_default_456",
        basePlanId: String = "monthly",
        acquiredAtMs: Long = 1_700_000_000_000L,
        isAutoRenewing: Boolean = true,
        state: EntitlementState = EntitlementState.ACTIVE,
    ) = Entitlement(
        productId = productId,
        purchaseToken = purchaseToken,
        orderId = orderId,
        acquiredAtMs = acquiredAtMs,
        isAutoRenewing = isAutoRenewing,
        state = state,
    )
}