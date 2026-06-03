package io.galva.localstorage

import android.content.Context
import android.content.SharedPreferences
import io.galva.localstorage.core.KeyValueStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

class PrefKeyValueStorage(
    context: Context,
    fileName: String = "galva-prefs",
    private val json: Json = DEFAULT_JSON,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : KeyValueStorage {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    override suspend fun getString(key: String): String? = withContext(ioDispatcher) {
        if (prefs.contains(key)) prefs.getString(key, null) else null
    }

    override suspend fun putString(key: String, value: String?){
        withContext(ioDispatcher) {
            prefs.edit().putString(key, value).commit()                  // async write
        }
    }

    override suspend fun getInt(key: String, defaultValue: Int): Int {
        return withContext(ioDispatcher) {
            if (prefs.contains(key)) prefs.getInt(key, defaultValue) else defaultValue
        }
    }

    override suspend fun putInt(key: String, value: Int){
        withContext(ioDispatcher) {
            prefs.edit().putInt(key, value).commit()
        }
    }

    override fun observeInt(key: String, defaultValue: Int): Flow<Int> {
        return callbackFlow {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
                if (changedKey == key) {
                    trySend(prefs.getInt(key, defaultValue))
                }
            }
            // Emit current value on subscribe
            trySend(prefs.getInt(key, defaultValue))
            prefs.registerOnSharedPreferenceChangeListener(listener)
            awaitClose {
                prefs.unregisterOnSharedPreferenceChangeListener(listener)
            }
        }.flowOn(ioDispatcher)
    }

    override suspend fun getLong(key: String, defaultValue: Long) = withContext(ioDispatcher) {
        if (prefs.contains(key)) prefs.getLong(key, 0L) else defaultValue
    }

    override suspend fun putLong(key: String, value: Long){
        withContext(ioDispatcher) {
            prefs.edit().putLong(key, value).commit()
        }
    }

    override fun observeLong(key: String, defaultValue: Long): Flow<Long> {
        return callbackFlow {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
                if (changedKey == key) {
                    trySend(prefs.getLong(key, defaultValue))
                }
            }
            // Emit current value on subscribe
            trySend(prefs.getLong(key, defaultValue))

            prefs.registerOnSharedPreferenceChangeListener(listener)
            awaitClose {
                prefs.unregisterOnSharedPreferenceChangeListener(listener)
            }
        }
    }

    override suspend fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        withContext(ioDispatcher) {
            prefs.getBoolean(key, defaultValue)
        }


    override suspend fun putBoolean(key: String, value: Boolean) {
        withContext(ioDispatcher) {
            prefs.edit().putBoolean(key, value).commit()
        }
    }

    override fun observeBoolean(key: String, defaultValue: Boolean): Flow<Boolean> {
        return callbackFlow {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
                if (changedKey == key) {
                    trySend(prefs.getBoolean(key, defaultValue))
                }
            }
            // Emit current value on subscribe
            trySend(prefs.getBoolean(key, defaultValue))

            prefs.registerOnSharedPreferenceChangeListener(listener)
            awaitClose {
                prefs.unregisterOnSharedPreferenceChangeListener(listener)
            }
        }.flowOn(ioDispatcher)
    }

    override suspend fun remove(key: String) {
        withContext(ioDispatcher) {
            prefs.edit().remove(key).commit()
        }
    }

    override suspend fun contains(key: String): Boolean = withContext(ioDispatcher) {
        prefs.contains(key)
    }

    override suspend fun clear() {
        withContext(ioDispatcher) {
            prefs.edit().clear().commit()
        }
    }

    override fun observeString(key: String): Flow<String?> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == key) {
                trySend(prefs.getString(key, null))
            }
        }
        // Emit current value on subscribe
        trySend(prefs.getString(key, null))

        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }.flowOn(ioDispatcher)

    override suspend fun <T> putObject(key: String, value: T, serializer: KSerializer<T>) {
        withContext(ioDispatcher) {
            val jsonString = json.encodeToString(serializer, value)
            putString(key, jsonString)
        }
    }

    override suspend fun <T> getObject(key: String, serializer: KSerializer<T>): T? =
        withContext(ioDispatcher) {
            val jsonString = getString(key) ?: return@withContext null
            try {
                json.decodeFromString(serializer, jsonString)
            } catch (e: Exception) {
                null
            }
        }

    override fun <T> observeObject(key: String, serializer: KSerializer<T>): Flow<T?> {
        return observeString(key).mapLatest { jsonString ->
            if (jsonString == null) {
                null
            } else {
                try {
                    json.decodeFromString(serializer, jsonString)
                } catch (e: Exception) {
                    null
                }
            }
        }.flowOn(ioDispatcher)
    }

    companion object {
        val DEFAULT_JSON: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = false
        }
    }
}