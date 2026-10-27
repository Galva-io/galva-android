package io.galva.sdk

import io.galva.common.logger.LogSink
import io.galva.common.logger.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@OptIn(GalvaIntegrationApi::class)
@RunWith(RobolectricTestRunner::class)
class GalvaIntegrationTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val lines = CopyOnWriteArrayList<String>()
    private val galva = Galva(Logger.create("Galva", LogSink { _, _, message, _ -> lines += message }), scope)

    @After
    fun tearDown() = scope.cancel()

    private fun await(deferred: CompletableDeferred<*>) = runBlocking { withTimeout(5_000) { deferred.await() } }

    @Test
    fun `each integration is installed once, first of a name wins`() {
        val first = AtomicInteger()
        val duplicate = AtomicInteger()
        val installed = CompletableDeferred<Unit>()

        galva.installIntegrations(
            listOf(
                GalvaIntegration("revenuecat", "1.0.0") { first.incrementAndGet(); installed.complete(Unit) },
                GalvaIntegration("revenuecat", "2.0.0") { duplicate.incrementAndGet() },
            )
        )

        await(installed)
        assertEquals(1, first.get())
        assertEquals(0, duplicate.get())
    }

    @Test
    fun `a failing integration does not keep the others from installing`() {
        val other = CompletableDeferred<Unit>()

        galva.installIntegrations(
            listOf(
                GalvaIntegration("broken", "1.0.0") { error("boom") },
                GalvaIntegration("working", "1.0.0") { other.complete(Unit) },
            )
        )

        await(other)
    }

    @Test
    fun `the context logger prefixes lines with the integration name`() {
        val logged = CompletableDeferred<Unit>()

        galva.installIntegrations(
            listOf(GalvaIntegration("revenuecat", "1.0.0") { it.logger.info { "connected" }; logged.complete(Unit) })
        )

        await(logged)
        assertTrue("lines: $lines", lines.any { it.endsWith("[revenuecat] connected") })
    }

    @Test
    fun `setPurchaseController installs and clears the controller`() {
        val controller = object : GalvaPurchaseController {
            override suspend fun purchase(request: GalvaPurchaseRequest) = GalvaPurchaseResult.Cancelled
        }

        galva.setPurchaseController(controller)
        assertTrue(galva.purchaseController === controller)

        galva.setPurchaseController(null)
        assertEquals(null, galva.purchaseController)
    }
}
