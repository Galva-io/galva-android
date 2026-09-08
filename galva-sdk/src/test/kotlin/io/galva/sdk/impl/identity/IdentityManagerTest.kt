package io.galva.sdk.impl.identity

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class IdentityManagerTest {

    private lateinit var store: io.galva.identity.IdentityStore
    private lateinit var manager: io.galva.identity.IdentityManager

    @Before
    fun setup() {
        store = FakeIdentityStore()

        manager = DefaultIdentityManager(
            store = store,
            idGenerator = FakeIdGenerator(),
        )
    }

    @Test
    fun `identify email updates state and persists`() = runTest {
        manager.initialize()
        manager.identify(
            userId = "u1",
            email = "test@email.com",
            obfuscatedAccountId =  null
        )
        val state = manager.state.value
        assertEquals("u1", state.userId)
        assertEquals("test@email.com", state.email)
        assertEquals(state, store.load())
    }

}