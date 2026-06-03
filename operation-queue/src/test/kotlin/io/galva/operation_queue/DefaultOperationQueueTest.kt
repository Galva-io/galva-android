package io.galva.operation_queue

import io.galva.common.network.RetryPolicy
import io.galva.operation_queue.local.StoredOperation
import io.galva.operation_queue.networkgate.StreamNetworkGate
import io.galva.operation_queue.policy.SizeOrTimeoutBatchPolicy
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.collections.flatten
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi

class QueueContainer(val scope: TestScope) {
    val clock: Clock = Clock { scope.testScheduler.currentTime }
    val store = InMemoryStore()
    val network = FakeNetworkMonitor()
    val sender = RecordingSender(clock = clock)
    var retry: RetryPolicy = StubRetryPolicy()
    val tick = Channel<Unit>(Channel.CONFLATED)
    val enqueueWake = Channel<Unit>(Channel.CONFLATED)
    private var nextId = 0L
    fun build(
        maxBatchSize: Int = 10,
        flushTimeout: Duration = 1.seconds,
    ) = DefaultOperationQueue(
        store = store,
        sender = sender,
        gate = StreamNetworkGate(network),
        batchPolicy = SizeOrTimeoutBatchPolicy(
            maxBatchSize, flushTimeout
        ),
        retryPolicy = retry,
        clock = clock,
        idGen = { "id-${nextId++}" },
        transitionTrigger = OfflineToOnlineTrigger(network),
        scope = scope.backgroundScope,
        forceWake = tick,
        enqueueWake = enqueueWake
    )
}

/** Test convenience — defaults type to "default". */
private suspend fun OperationQueue.enqueueDefault(payload: String, id: String? = null) =
    enqueue(payload = payload, type = "default", id = id)

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultOperationQueueTest {
    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun `flushes immediately when batch size reaches cap`() = runTest {
        val c = QueueContainer(this);
        val q = c.build(maxBatchSize = 10).also { it.start() }

        repeat(10) { q.enqueueDefault("p$it") }
        advanceTimeBy(1.seconds + 200.milliseconds)
        assertEquals(1, c.sender.batches.size)
        assertEquals(10, c.sender.batches.first().size)
        assertEquals(0, c.store.rowCount())
        q.stop()
    }


    @Test
    fun `does not flush below cap until timeout`() = runTest {
        val c = QueueContainer(this)
        val q = c.build(maxBatchSize = 10, flushTimeout = 5.seconds).also { it.start() }

        repeat(3) { q.enqueueDefault("p$it") }
        advanceTimeBy(4.seconds)
        assertTrue(c.sender.batches.isEmpty())

        advanceTimeBy(2.seconds)
        assertEquals(listOf(3), c.sender.batches.map { it.size })
        q.stop()
    }

    @Test
    fun `drains in multiple batches when more than cap is buffered`() = runTest {
        val c = QueueContainer(this);
        val q = c.build(maxBatchSize = 10).also { it.start() }

        repeat(25) { q.enqueueDefault("p$it") }
        advanceTimeBy(2.seconds)
        assertEquals(listOf(10, 10, 5), c.sender.batches.map { it.size })
        assertEquals(0, c.store.rowCount())
        q.stop()
    }

    // ─── Manual flush ────────────────────────────────────────────────

    @Test
    fun `manual flush dispatches partial batch immediately`() = runTest {
        val c = QueueContainer(this);
        val q = c.build(flushTimeout = 30.seconds).also { it.start() }

        q.enqueueDefault("a"); q.enqueueDefault("b")
        q.flush()
        advanceTimeBy(31.seconds)

        assertEquals(listOf(2), c.sender.batches.map { it.size })
        q.stop()
    }

    // ─── Ordering ────────────────────────────────────────────────────

    @Test
    fun `dispatches in created_at order`() = runTest {
        val c = QueueContainer(this);
        val q = c.build().also { it.start() }

        q.enqueueDefault("first")
        advanceTimeBy(10.milliseconds)
        q.enqueueDefault("second")
        advanceTimeBy(10.milliseconds)
        q.enqueueDefault("third")
        q.flush()
        runCurrent()

        assertEquals(
            listOf("first", "second", "third"), c.sender.batches.flatten().map { it.payload })
        q.stop()
    }


    // ─── Network gating ──────────────────────────────────────────────

    @Test
    fun `does not dispatch while offline`() = runTest {
        val c = QueueContainer(this).apply { network.setOnline(false) }
        val q = c.build(flushTimeout = 1.seconds).also { it.start() }

        repeat(5) { q.enqueueDefault("p$it") }
        advanceTimeBy(3.seconds)
        assertTrue(c.sender.batches.isEmpty())
        assertEquals(5, c.store.rowCount())
        q.stop()
    }

    @Test
    fun `mixed types share a single batch`() = runTest {
        val c = QueueContainer(this);
        val q = c.build(maxBatchSize = 10).also { it.start() }

        q.enqueue("e1", type = "POST")
        q.enqueue("p1", type = "PUT")
        q.enqueue("e2", type = "POST")
        q.enqueue("d1", type = "DELETE")
        q.flush()
        runCurrent()

        assertEquals(1, c.sender.batches.size)
        assertEquals(listOf("e1", "p1", "e2", "d1"), c.sender.batches.first().map { it.payload })
        assertEquals(
            listOf("POST", "PUT", "POST", "DELETE"), c.sender.batches.first().map { it.type })
        q.stop()
    }

    @Test
    fun `type metadata is preserved end-to-end`() = runTest {
        val c = QueueContainer(this);
        val q = c.build().also { it.start() }

        q.enqueue("payload-1", type = "POST")
        q.enqueue("payload-2", type = "PUT", id = "fixed-id")
        q.flush()
        runCurrent()

        val sent = c.sender.batches.flatten()
        assertEquals("POST", sent.first { it.payload == "payload-1" }.type)
        assertEquals("PUT", sent.first { it.id == "fixed-id" }.type)
        q.stop()
    }

    @Test
    fun `flushes on offline-to-online transition`() = runTest {
        val c = QueueContainer(this).apply { network.setOnline(false) }
        val q = c.build(flushTimeout = 60.seconds).also { it.start() }

        repeat(4) { q.enqueueDefault("p$it") }
        advanceTimeBy(1.seconds); runCurrent()
        assertTrue(c.sender.batches.isEmpty())

        c.network.setOnline(true)
        runCurrent(); advanceTimeBy(50.milliseconds); runCurrent()

        assertEquals(1, c.sender.batches.size)
        assertEquals(4, c.sender.batches.first().size)
        assertEquals(0, c.store.rowCount())
        q.stop()
    }

    @Test
    fun `online-to-offline-to-online cycle re-flushes only on the up edge`() = runTest {
        val c = QueueContainer(this);
        val q = c.build(flushTimeout = 60.seconds).also { it.start() }

        q.enqueueDefault("a"); runCurrent()
        c.sender.batches.clear()

        c.network.setOnline(false); runCurrent()
        q.enqueueDefault("b"); runCurrent()
        assertTrue(c.sender.batches.isEmpty())

        c.network.setOnline(true);
        advanceTimeBy(20.milliseconds)
        c.network.setOnline(true); runCurrent()       // duplicate emit, ignored

        assertEquals(1, c.sender.batches.size)
        q.stop()
    }

    // ─── Retry semantics ─────────────────────────────────────────────

    @Test
    fun `failed send releases rows and bumps retry count`() = runTest {
        val c = QueueContainer(this).apply { sender.queueFailure() }
        c.retry = StubRetryPolicy(backoff = 300.milliseconds)
        val q = c.build(flushTimeout = 1.seconds).also { it.start() }
        q.enqueueDefault("a")
        advanceTimeBy(1.seconds + 100.milliseconds)
        assertEquals(1, c.sender.batches.size)
        assertEquals(1, c.store.rowCount())
        assertEquals(1, c.store.retryCountOf("id-1"))
        assertEquals(1, (c.retry as StubRetryPolicy).calls)
        q.stop()
    }

    @Test
    fun `failed batch is retried in next loop iteration`() = runTest {
        val c = QueueContainer(this).apply { sender.queueFailure() }
        c.retry = StubRetryPolicy(backoff = 500.milliseconds)
        val q = c.build(flushTimeout = 100.milliseconds).also { it.start() }

        repeat(3) { q.enqueueDefault("p$it") }
        advanceTimeBy(150.milliseconds)
        runCurrent()
        assertEquals(3, c.store.rowCount())
        advanceTimeBy(500.milliseconds)
        runCurrent()
        assertEquals(2, c.sender.batches.size)
        assertEquals(0, c.store.rowCount())
        q.stop()
    }

    @Test
    fun `backoff suspends the loop and blocks subsequent batches`() = runTest {
        val c = QueueContainer(this).apply {
            retry = StubRetryPolicy(backoff = 2.seconds)
            sender.queueFailure()
        }
        val q = c.build(flushTimeout = 100.milliseconds).also { it.start() }

        q.enqueueDefault("first")
        advanceTimeBy(150.milliseconds)
        runCurrent()
        q.enqueueDefault("second")
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(1, c.sender.batches.size)

        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(2, c.sender.batches.size)

        val (t1, t2) = c.sender.sendTimestamps
        assertTrue((t2 - t1) >= 2_000)
        q.stop()
    }

    // ─── Persistence ─────────────────────────────────────────────────

    @Test
    fun `pre-seeded rows are replayed on start`() = runTest {
        val c = QueueContainer(this)
        c.store.seed(StoredOperation("g1", "default", "default", retryCount = 0, createdAt = 1L))
        c.store.seed(StoredOperation("g2", "default", "default", retryCount = 0, createdAt = 2L))
        val q = c.build()
        q.start()
        runCurrent()
        advanceTimeBy(1.seconds + 200.milliseconds)

        assertEquals(setOf("g1", "g2"), c.sender.batches.first().map { it.id }.toSet())
        assertEquals(0, c.store.rowCount())
        q.stop()
    }

    @Test
    fun `start releases stale locks from prior process`() = runTest {
        val c = QueueContainer(this)
        c.store.seed(StoredOperation("orphan", "default", "default", 0, 0), locked = true)
        val q = c.build()
        q.start()
        runCurrent()
        advanceTimeBy(1.seconds + 200.milliseconds)
        assertEquals(1, c.sender.batches.size)
        assertEquals(0, c.store.rowCount())
        q.stop()

    }

    @Test
    fun `duplicate id is ignored`() = runTest {
        val c = QueueContainer(this)
        val q = c.build().also { it.start() }

        q.enqueue(payload = "v1", type = "default", id = "dup")
        q.enqueue(payload = "v2", type = "default", id = "dup")
        q.flush()
        runCurrent()

        assertEquals(1, c.sender.batches.flatten().size)
        assertEquals("v1", c.sender.batches.flatten().single().payload)
        q.stop()
    }

    // ─── Concurrency ─────────────────────────────────────────────────

    @Test
    fun `enqueue during in-flight send is included in next batch`() = runTest {
        val c = QueueContainer(this).apply { sender.sendDelay = 200.milliseconds }
        val q = c.build(maxBatchSize = 10, flushTimeout = 50.milliseconds).also { it.start() }

        repeat(10) { q.enqueueDefault("a$it") }
        advanceTimeBy(60.milliseconds)
        runCurrent()
        repeat(3) { q.enqueueDefault("b$it") }
        advanceTimeBy(500.milliseconds)
        runCurrent()

        assertEquals(listOf(10, 3), c.sender.batches.map { it.size })
        assertEquals(0, c.store.rowCount())
        q.stop()
    }

    // ─── Lifecycle ───────────────────────────────────────────────────

    @Test
    fun `start is idempotent`() = runTest {
        val c = QueueContainer(this)
        val q = c.build()
        q.start()
        q.start()
        q.start()

        q.enqueueDefault("a")
        q.flush()
        runCurrent()

        assertEquals(1, c.sender.batches.size)
        q.stop()
    }

    @Test
    fun `shutdown stops the consumer cleanly`() = runTest {
        val c = QueueContainer(this)
        val q = c.build().also { it.start() }

        q.enqueueDefault("a")
        q.flush()
        runCurrent()
        q.stop()

        c.sender.batches.clear()
        q.enqueueDefault("after-shutdown")
        advanceTimeBy(5.seconds)

        assertTrue(c.sender.batches.isEmpty())
        q.stop()
    }

    @Test fun `loop exits cleanly when scope cancelled`() = runTest {
        val c = QueueContainer(this); val q = c.build().also { it.start() }

        q.enqueueDefault("a")
        advanceTimeBy(1.seconds + 200.milliseconds)
        assertEquals(1, c.sender.batches.size)

        q.stop()
        // Verify no leak — backgroundScope auto-cancels
        q.enqueueDefault("a")
        advanceTimeBy(1.seconds)
        assertEquals(1, c.sender.batches.size)
    }

}