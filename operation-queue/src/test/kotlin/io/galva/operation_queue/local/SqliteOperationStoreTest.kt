package io.galva.operation_queue.local

import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import io.galva.common.logger.NoOpLogger
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SqliteOperationStoreTest {
    private lateinit var helper: OperationsSqliteHelper
    private lateinit var store: SqliteOperationStore
    private val nowMs = 1_700_000_000_000L

    @Before
    fun setUp() {
        helper = OperationsSqliteHelper(ApplicationProvider.getApplicationContext())
        store = SqliteOperationStore(
            helper = helper,
            logger = NoOpLogger,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
    }

    @After
    fun tearDown() {
        helper.writableDatabase.execSQL("DELETE FROM operations")
        helper.close()
    }

    // ── insert ──────────────────────────────────────────────────────────────

    @Test
    fun `insert persists operation`() = runTest {
        store.insert(makeOp("op1"))

        val claimed = store.claimBatch(10, "tok", nowMs)
        assertEquals(1, claimed.size)
        assertEquals("op1", claimed.single().id)
    }

    @Test
    fun `insert ignores duplicate id`() = runTest {
        store.insert(makeOp("op1", type = "first"))
        store.insert(makeOp("op1", type = "second"))                  // ignored

        val claimed = store.claimBatch(10, "tok", nowMs)
        assertEquals(1, claimed.size)
        assertEquals("first", claimed.single().type)
    }

    @Test
    fun `insert all fields round-trip`() = runTest {
        val op = StoredOperation(
            id = "op1",
            type = "Identify",
            payload = """{"userId":"42"}""",
            createdAt = nowMs,
            retryCount = 3,
        )
        store.insert(op)

        val claimed = store.claimBatch(10, "tok", nowMs).single()
        assertEquals(op, claimed)
    }

    // ── claimBatch ──────────────────────────────────────────────────────────

    @Test
    fun `claimBatch empty store returns empty`() = runTest {
        val claimed = store.claimBatch(10, "tok", nowMs)
        assertTrue(claimed.isEmpty())
    }

    @Test
    fun `claimBatch returns up to limit`() = runTest {
        repeat(15) { store.insert(makeOp("op_$it", createdAt = it.toLong())) }

        val claimed = store.claimBatch(10, "tok", nowMs)
        assertEquals(10, claimed.size)
    }

    @Test
    fun `claimBatch returns oldest first`() = runTest {
        store.insert(makeOp("c", createdAt = 300L))
        store.insert(makeOp("a", createdAt = 100L))
        store.insert(makeOp("b", createdAt = 200L))

        val claimed = store.claimBatch(10, "tok", nowMs)
        assertEquals(listOf("a", "b", "c"), claimed.map { it.id })
    }

    @Test
    fun `claimBatch locks rows preventing re-claim`() = runTest {
        store.insert(makeOp("op_a"))
        store.insert(makeOp("op_b"))

        val first = store.claimBatch(10, "tok1", nowMs)
        assertEquals(2, first.size)

        val second = store.claimBatch(10, "tok2", nowMs)
        assertTrue(second.isEmpty())                                  // locked
    }

    @Test
    fun `claimBatch with limit smaller than available leaves rest`() = runTest {
        repeat(5) { store.insert(makeOp("op_$it", createdAt = it.toLong())) }

        val firstBatch = store.claimBatch(3, "tok1", nowMs)
        assertEquals(3, firstBatch.size)

        // Other unlocked rows still available with a fresh claim
        val secondBatch = store.claimBatch(3, "tok1", nowMs)
        assertEquals(2, secondBatch.size)
        assertEquals(setOf("op_3", "op_4"), secondBatch.map { it.id }.toSet())
    }

    // ── delete ──────────────────────────────────────────────────────────────

    @Test
    fun `delete removes operations permanently`() = runTest {
        store.insert(makeOp("op_a"))
        val claimed = store.claimBatch(10, "tok", nowMs)
        store.delete(claimed.map { it.id })

        store.releaseAllLocks()                                       // reset locks
        val remaining = store.claimBatch(10, "tok", nowMs)
        assertTrue(remaining.isEmpty())
    }

    @Test
    fun `delete with empty ids is noop`() = runTest {
        store.insert(makeOp("op_a"))

        store.delete(emptyList())

        val claimed = store.claimBatch(10, "tok", nowMs)
        assertEquals(1, claimed.size)
    }

    @Test
    fun `delete only removes listed ids`() = runTest {
        store.insert(makeOp("op_a"))
        store.insert(makeOp("op_b"))
        store.insert(makeOp("op_c"))

        val claimed = store.claimBatch(10, "tok", nowMs)
        store.delete(listOf("op_a", "op_c"))

        store.releaseAllLocks()
        val remaining = store.claimBatch(10, "tok2", nowMs)
        assertEquals(listOf("op_b"), remaining.map { it.id })
    }

    @Test
    fun `delete large list chunked correctly`() = runTest {
        repeat(800) { store.insert(makeOp("op_$it", createdAt = it.toLong())) }
        val claimed = store.claimBatch(800, "tok", nowMs)
        assertEquals(800, claimed.size)

        store.delete(claimed.map { it.id })

        store.releaseAllLocks()
        val remaining = store.claimBatch(800, "tok2", nowMs)
        assertTrue(remaining.isEmpty())
    }

    // ── release ─────────────────────────────────────────────────────────────

    @Test
    fun `release unlocks and increments retry count`() = runTest {
        store.insert(makeOp("op_a"))
        val claimed = store.claimBatch(10, "tok", nowMs)
        assertEquals(0, claimed.single().retryCount)

        store.release(claimed.map { it.id })

        val reclaimed = store.claimBatch(10, "tok2", nowMs).single()
        assertEquals(1, reclaimed.retryCount)
    }

    @Test
    fun `release multiple times accumulates retry count`() = runTest {
        store.insert(makeOp("op_a"))

        repeat(3) {
            val claimed = store.claimBatch(10, "tok", nowMs)
            store.release(claimed.map { it.id })
        }

        val final = store.claimBatch(10, "tok", nowMs).single()
        assertEquals(3, final.retryCount)
    }

    @Test
    fun `release with empty ids is noop`() = runTest {
        store.insert(makeOp("op_a"))
        store.claimBatch(10, "tok", nowMs)

        store.release(emptyList())

        val reclaim = store.claimBatch(10, "tok2", nowMs)
        assertTrue(reclaim.isEmpty())
    }

    // ── releaseAllLocks ─────────────────────────────────────────────────────

    @Test
    fun `releaseAllLocks unlocks every locked row`() = runTest {
        store.insert(makeOp("op_a"))
        store.insert(makeOp("op_b"))
        store.claimBatch(10, "tok", nowMs)

        store.releaseAllLocks()

        val reclaimed = store.claimBatch(10, "tok2", nowMs)
        assertEquals(2, reclaimed.size)
    }

    @Test
    fun `releaseAllLocks does not increment retry count`() = runTest {
        store.insert(makeOp("op_a"))
        store.claimBatch(10, "tok", nowMs)

        store.releaseAllLocks()

        val reclaimed = store.claimBatch(10, "tok2", nowMs).single()
        assertEquals(0, reclaimed.retryCount)
    }

    @Test
    fun `releaseAllLocks on empty store is noop`() = runTest {
        store.releaseAllLocks()                                       // no crash
    }

    // ── pendingCount ────────────────────────────────────────────────────────

    @Test
    fun `pendingCount starts at zero`() = runTest {
        store.pendingCount().test {
            assertEquals(0, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pendingCount increments on insert`() = runTest {
        store.pendingCount().test {
            assertEquals(0, awaitItem())

            store.insert(makeOp("op1"))
            assertEquals(1, awaitItem())

            store.insert(makeOp("op2"))
            assertEquals(2, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pendingCount decrements on claim`() = runTest {
        store.insert(makeOp("op1"))
        store.insert(makeOp("op2"))

        store.pendingCount().test {
            assertEquals(2, awaitItem())

            store.claimBatch(10, "tok", nowMs)
            assertEquals(0, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pendingCount increments on release`() = runTest {
        store.insert(makeOp("op1"))
        val claimed = store.claimBatch(10, "tok", nowMs)

        store.pendingCount().test {
            assertEquals(0, awaitItem())

            store.release(claimed.map { it.id })
            assertEquals(1, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pendingCount increments on releaseAllLocks`() = runTest {
        store.insert(makeOp("op1"))
        store.insert(makeOp("op2"))
        store.claimBatch(10, "tok", nowMs)

        store.pendingCount().test {
            assertEquals(0, awaitItem())

            store.releaseAllLocks()
            assertEquals(2, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pendingCount does not emit on delete of locked rows`() = runTest {
        store.insert(makeOp("op1"))
        val claimed = store.claimBatch(10, "tok", nowMs)

        store.pendingCount().test {
            assertEquals(0, awaitItem())

            store.delete(claimed.map { it.id })
            expectNoEvents()                                          // locked rows weren't pending

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pendingCount resets on clearAll`() = runTest {
        store.insert(makeOp("op1"))
        store.insert(makeOp("op2"))

        store.pendingCount().test {
            assertEquals(2, awaitItem())

            store.clearAll()
            assertEquals(0, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pendingCount filters duplicates`() = runTest {
        store.pendingCount().test {
            assertEquals(0, awaitItem())

            // No-op operations shouldn't emit
            store.release(emptyList())
            store.delete(emptyList())
            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── clearAll ────────────────────────────────────────────────────────────

    @Test
    fun `clearAll wipes everything`() = runTest {
        store.insert(makeOp("op1"))
        store.insert(makeOp("op2"))
        store.claimBatch(10, "tok", nowMs)                            // some locked

        store.clearAll()

        store.releaseAllLocks()
        val remaining = store.claimBatch(10, "tok", nowMs)
        assertTrue(remaining.isEmpty())
    }

    @Test
    fun `clearAll on empty store is noop`() = runTest {
        store.clearAll()
        store.pendingCount().test {
            assertEquals(0, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── concurrent access ──────────────────────────────────────────────────

    @Test
    fun `two consumers cannot claim same row`() = runTest {
        store.insert(makeOp("op1"))

        val first = store.claimBatch(10, "tok1", nowMs)
        val second = store.claimBatch(10, "tok2", nowMs)

        assertEquals(1, first.size)
        assertTrue(second.isEmpty())
    }

    @Test
    fun `insert and claim interleaved`() = runTest {
        store.insert(makeOp("op1", createdAt = 100L))
        val first = store.claimBatch(10, "tok", nowMs)
        assertEquals(1, first.size)

        store.insert(makeOp("op2", createdAt = 200L))
        val second = store.claimBatch(10, "tok", nowMs)
        assertEquals(listOf("op2"), second.map { it.id })
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun makeOp(
        id: String,
        type: String = "Identify",
        payload: String = """{"id":"$id"}""",
        createdAt: Long = nowMs,
        retryCount: Int = 0,
    ) = StoredOperation(id, type, payload, createdAt, retryCount)
}