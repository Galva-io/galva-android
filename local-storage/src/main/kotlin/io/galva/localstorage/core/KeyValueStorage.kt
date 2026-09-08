package io.galva.localstorage.core

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

interface KeyValueStorage {
    // ── Primitives ───────────────────────────────────────────────────
    suspend fun putString(key: String, value: String?)
    suspend fun getString(key: String): String?
    fun observeString(key: String): Flow<String?>

    suspend fun putInt(key: String, value: Int)
    suspend fun getInt(key: String, defaultValue: Int = 0): Int
    fun observeInt(key: String, defaultValue: Int = 0): Flow<Int>

    suspend fun putLong(key: String, value: Long)
    suspend fun getLong(key: String, defaultValue: Long = 0L): Long
    fun observeLong(key: String, defaultValue: Long = 0L): Flow<Long>

    suspend fun putBoolean(key: String, value: Boolean)
    suspend fun getBoolean(key: String, defaultValue: Boolean = false): Boolean
    fun observeBoolean(key: String, defaultValue: Boolean = false): Flow<Boolean>

    // ── Object (KSerializer) ─────────────────────────────────────────
    suspend fun <T> putObject(key: String, value: T, serializer: KSerializer<T>)
    suspend fun <T> getObject(key: String, serializer: KSerializer<T>): T?
    fun <T> observeObject(key: String, serializer: KSerializer<T>): Flow<T?>

    // ── Lifecycle ───────────────────────────────────────────────────
    suspend fun contains(key: String): Boolean
    suspend fun remove(key: String)
    suspend fun clear()
}

inline fun <reified T> KeyValueStorage.observeObject(key: String): Flow<T?> =
    observeObject(key, serializer())

suspend inline fun <reified T> KeyValueStorage.putObject(key: String, value: T) =
    putObject(key, value, serializer())

suspend inline fun <reified T> KeyValueStorage.getObject(key: String): T? =
    getObject(key, serializer())