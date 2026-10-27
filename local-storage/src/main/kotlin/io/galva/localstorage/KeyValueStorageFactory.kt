package io.galva.localstorage

import io.galva.common.InternalGalvaApi
import android.content.Context
import io.galva.common.logger.Logger
import io.galva.common.logger.NoOpLogger
import io.galva.localstorage.core.KeyValueStorage
import io.galva.localstorage.crypto.AesGcmCipher
import io.galva.localstorage.crypto.KeystoreKeyProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers


@InternalGalvaApi
object KeyValueStorageFactory {

    /** Plain (unencrypted) key/value storage. */
    @JvmStatic
    @JvmOverloads
    fun create(
        context: Context,
        fileName: String = DEFAULT_FILE_NAME,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        logger: Logger = NoOpLogger,
    ): KeyValueStorage {
        return PrefKeyValueStorage(context, fileName = fileName, ioDispatcher = dispatcher, logger = logger)
    }

    /** Keystore-backed AES-GCM encrypted storage. Requires API 23+. */
    @JvmStatic
    @JvmOverloads
    fun createSecure(
        context: Context,
        fileName: String = DEFAULT_SECURE_FILE_NAME,
        keyAlias: String = DEFAULT_KEY_ALIAS,
        logger: Logger = NoOpLogger,
    ): KeyValueStorage {
        val cipher = AesGcmCipher(KeystoreKeyProvider(keyAlias))
        return SecureDataStoreKeyValueStorage(
            context = context,
            fileName = fileName,
            cipher = cipher,
            logger = logger,
        )
    }


    const val DEFAULT_FILE_NAME = "galva.kv"
    const val DEFAULT_SECURE_FILE_NAME = "galva.secure"
    const val DEFAULT_KEY_ALIAS = "galva.master"

}