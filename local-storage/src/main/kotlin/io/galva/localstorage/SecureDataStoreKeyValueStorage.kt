package io.galva.localstorage

import android.content.Context
import android.content.SharedPreferences
import io.galva.localstorage.PrefKeyValueStorage.Companion.DEFAULT_JSON
import io.galva.localstorage.core.KeyValueStorage
import io.galva.localstorage.crypto.Cipher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

class SecureDataStoreKeyValueStorage(
    context: Context,
    fileName: String = "galva-secure-prefs",
    private val cipher: Cipher,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val json: Json = DEFAULT_JSON,
) : KeyValueStorage {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    override suspend fun putString(key: String, value: String?) = writeBlob(key, value)

    override suspend fun getString(key: String): String? = readBlob(key)
    override fun observeString(key: String): Flow<String?> = observeBlob(key)
    override suspend fun putInt(key: String, value: Int) {
        writeBlob(key, value.toString())
    }

    override suspend fun getInt(key: String, defaultValue: Int): Int {
        val raw = readBlob(key) ?: return defaultValue
        return raw.toIntOrNull() ?: defaultValue
    }

    override fun observeInt(
        key: String, defaultValue: Int
    ): Flow<Int> {
        return observeBlob(key).map { it?.toIntOrNull() ?: defaultValue }
    }

    override suspend fun putLong(key: String, value: Long) = writeBlob(key, value.toString())

    override suspend fun getLong(key: String, defaultValue: Long): Long =
        readBlob(key)?.toLongOrNull() ?: defaultValue

    override fun observeLong(key: String, defaultValue: Long): Flow<Long> =
        observeBlob(key).map { it?.toLongOrNull() ?: defaultValue }

    override suspend fun putBoolean(key: String, value: Boolean) =
        writeBlob(key, if (value) "1" else "0")

    override suspend fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        when (readBlob(key)) {
            "1" -> true
            "0" -> false
            else -> defaultValue
        }

    override fun observeBoolean(key: String, defaultValue: Boolean): Flow<Boolean> =
        observeBlob(key).map {
            when (it) {
                "1" -> true; "0" -> false; else -> defaultValue
            }
        }

    override suspend fun <T> putObject(key: String, value: T, serializer: KSerializer<T>) =
        writeBlob(key, json.encodeToString(serializer, value))

    override suspend fun <T> getObject(key: String, serializer: KSerializer<T>): T? {
        val raw = readBlob(key) ?: return null
        return runCatching { json.decodeFromString(serializer, raw) }.onFailure { remove(key) }
            .getOrNull()
    }

    override fun <T> observeObject(key: String, serializer: KSerializer<T>): Flow<T?> =
        observeBlob(key).map { raw ->
            println("observeObject: raw value for key '$key' is: $raw")
            raw?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
        }

    override suspend fun contains(key: String): Boolean = prefs.contains(key)

    override suspend fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override suspend fun clear() {
        prefs.edit().clear().apply()
    }

    // ── internal: encrypt/decrypt with per-key AAD ────────────────────
    private suspend fun writeBlob(key: String, value: String?) {
        println("observeObject: writeBlob $key")
        withContext(dispatcher) {
            prefs.edit().apply {
                if (value == null) {
                    this.remove(key)
                } else {
                    this.putString(key, cipher.encrypt(value, aad = aadFor(key)))
                }
            }.commit()
        }
    }

    private fun readBlob(key: String): String? {
        val raw = prefs.getString(key, null) ?: return null
        return try {
            cipher.decrypt(raw, aad = aadFor(key))
        } catch (_: Exception) {
            prefs.edit().remove(key).apply()
            null
        }
    }

    private fun observeBlob(key: String): Flow<String?> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == key) {
                trySend(readBlob(key))
            }
        }
        trySend(readBlob(key))
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun aadFor(key: String): ByteArray = key.toByteArray(Charsets.UTF_8)
}