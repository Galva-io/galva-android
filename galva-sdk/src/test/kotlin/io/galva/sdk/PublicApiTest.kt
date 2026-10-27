package io.galva.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import io.galva.common.logger.LogLevel as InternalLogLevel
import io.galva.iam.WorkflowType as InternalWorkflowType

/**
 * Apps compile against galva-sdk alone: the internal modules are only on their runtime classpath,
 * so no type of theirs may appear in the public API (api/galva-sdk.api, updated by `apiDump`).
 */
class PublicApiTest {

    @Test
    fun `the public API exposes no internal module or serialization type`() {
        // Unit tests run from the module directory.
        val api = File("api/galva-sdk.api").readText()
        val leaks = INTERNAL_TYPE.findAll(api).map { it.value }.toSortedSet()
        assertTrue("internal types in api/galva-sdk.api: $leaks", leaks.isEmpty())
    }

    @Test
    fun `public enums mirror the internal ones they map to by name`() {
        assertEquals(InternalLogLevel.entries.map { it.name }, LogLevel.entries.map { it.name })
        assertEquals(InternalWorkflowType.entries.map { it.name }, WorkflowType.entries.map { it.name })
    }

    private companion object {
        val INTERNAL_TYPE = Regex(
            """io/galva/(core|common|iam|identity|network|billing|localstorage|operation_queue)/[\w/$]+""" +
                """|kotlinx/serialization/[\w/$]+""",
        )
    }
}
