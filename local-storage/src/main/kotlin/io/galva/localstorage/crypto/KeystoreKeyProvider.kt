package io.galva.localstorage.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.galva.localstorage.core.KeyProvider
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class KeystoreKeyProvider(private val alias: String) : KeyProvider {

    private val keystore: KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    /** Returns the existing key, generating one on the first call. */
    override fun getOrCreate(): SecretKey {
        keystore.getEntry(alias, null)?.let { entry ->
            return (entry as KeyStore.SecretKeyEntry).secretKey
        }
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)        // forces fresh IV each call
            .setUserAuthenticationRequired(false)         // no biometric prompt per read
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            .apply { init(spec) }
            .generateKey()
    }

    override fun exists(): Boolean = keystore.containsAlias(alias)

    /** Wipe the key — every existing ciphertext becomes unreadable. */
    override fun delete() {
        if (exists()) {
            keystore.deleteEntry(alias)
        }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
    }
}