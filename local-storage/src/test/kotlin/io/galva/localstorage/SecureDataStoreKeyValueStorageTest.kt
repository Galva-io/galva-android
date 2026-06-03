package io.galva.localstorage

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import io.galva.localstorage.core.KeyValueStorage
import io.galva.localstorage.core.getObject
import io.galva.localstorage.core.observeObject
import io.galva.localstorage.core.putObject
import io.galva.localstorage.crypto.Cipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@Serializable
internal data class Profile(val name: String, val age: Int)

@RunWith(RobolectricTestRunner::class)
class SecureDataStoreKeyValueStorageTest {
    @get:Rule
    val tempFolder = TemporaryFolder()
    private lateinit var cipher: Cipher
    private lateinit var storage: KeyValueStorage

    @Before
    fun setUp() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        cipher = StubAesGcmCipher()
        storage =
            SecureDataStoreKeyValueStorage(ApplicationProvider.getApplicationContext(), cipher = cipher, dispatcher = dispatcher)
    }

    // ── primitives ─────────────────────────────────────────────────

    @Test
    fun `String round-trip`() = runTest {
        storage.putString("token", "secret-123")
        assertEquals("secret-123", storage.getString("token"))
    }

    @Test
    fun `missing key returns null`() = runTest {
        assertNull(storage.getString("missing"))
    }

    @Test
    fun `null write removes the key`() = runTest {
        storage.putString("k", "v"); storage.putString("k", null)
        assertNull(storage.getString("k"))
    }

    @Test
    fun `Long with default and round-trip`() = runTest {
        assertEquals(7L, storage.getLong("missing", defaultValue = 7L))
        storage.putLong("k", Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, storage.getLong("k"))
    }

    @Test
    fun `Boolean with default and round-trip`() = runTest {
        assertTrue(storage.getBoolean("missing", defaultValue = true))
        storage.putBoolean("k", true); assertTrue(storage.getBoolean("k"))
        storage.putBoolean("k", false); assertFalse(storage.getBoolean("k"))
    }

    // ── objects ────────────────────────────────────────────────────

    @Test
    fun `Object round-trip via explicit serializer`() = runTest {
        val p = Profile("Ada", 28)
        storage.putObject("p", p, Profile.serializer())
        assertEquals(p, storage.getObject("p", Profile.serializer()))
    }

    @Test
    fun `Object round-trip via reified`() = runTest {
        val p = Profile("Ada", 28)
        storage.putObject("p", p)
        assertEquals(p, storage.getObject<Profile>("p"))
    }

    @Test
    fun `Object returns null on parse failure and clears entry`() = runTest {
        storage.putString("p", "not-json")        // valid string, invalid JSON for Profile
        assertNull(storage.getObject<Profile>("p"))
        assertNull(storage.getString("p"))
    }


    // ── observation ────────────────────────────────────────────────

    @Test
    fun `observeObject emits decrypted typed values`() = runTest {
        storage.observeObject<Profile>("p").test {
            assertNull(awaitItem())
            storage.putObject("p", Profile("A", 1))
            assertEquals(Profile("A", 1), awaitItem())

            storage.putObject("p", Profile("B", 2))
            assertEquals(Profile("B", 2), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

    }

    // ── lifecycle ─────────────────────────────────────────────────

    @Test
    fun `contains reflects presence`() = runTest {
        assertFalse(storage.contains("k"))
        storage.putString("k", "v")
        assertTrue(storage.contains("k"))
    }

    @Test
    fun `remove deletes a key`() = runTest {
        storage.putString("a", "1"); storage.putString("b", "2")
        storage.remove("a")
        assertNull(storage.getString("a"))
        assertEquals("2", storage.getString("b"))
    }

    @Test
    fun `clear wipes everything`() = runTest {
        storage.putString("a", "1"); storage.putLong("l", 99L); storage.putBoolean("t", true)
        storage.clear()
        assertNull(storage.getString("a"))
        assertEquals(0L, storage.getLong("l"))
        assertFalse(storage.getBoolean("t"))
    }

    @Test
    fun `survives recreating the storage with the same cipher`() = runTest {
        storage.putObject("p", Profile("X", 99))
        val again = SecureDataStoreKeyValueStorage(ApplicationProvider.getApplicationContext(), cipher = cipher)
        assertEquals(Profile("X", 99), again.getObject<Profile>("p"))
    }

    @Test
    fun `cipher with different key cannot decrypt — entry treated as missing`() = runTest {
        storage.putString("k", "v")
        val differentCipher = StubAesGcmCipher()        // fresh random key
        val other = SecureDataStoreKeyValueStorage(
            ApplicationProvider.getApplicationContext(),
            cipher = differentCipher
        )
        assertNull(other.getString("k"))
    }
}