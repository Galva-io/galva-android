package io.galva.sdk.impl.deeplink

import android.net.Uri
import io.galva.localstorage.core.KeyValueStorage

internal interface PendingDeeplinkStore {
    suspend fun save(uri: Uri)

    suspend fun load(): Uri?

    suspend fun clear()
}

internal class LocalPendingDeeplinkStore(
    private val storage: KeyValueStorage,
) : PendingDeeplinkStore {
    override suspend fun save(uri: Uri) {
        storage.putString(KEY_PENDING_DEEPLINK, uri.toString())
    }

    override suspend fun load(): Uri? =
        storage.getString(KEY_PENDING_DEEPLINK)?.let(Uri::parse)

    override suspend fun clear() {
        storage.remove(KEY_PENDING_DEEPLINK)
    }

    private companion object {
        const val KEY_PENDING_DEEPLINK = "pending_deeplink"
    }
}
