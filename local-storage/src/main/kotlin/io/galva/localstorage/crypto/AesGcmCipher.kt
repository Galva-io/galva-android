package io.galva.localstorage.crypto

import android.util.Base64
import io.galva.localstorage.core.KeyProvider
import javax.crypto.spec.GCMParameterSpec

class AesGcmCipher(private val keyProvider: KeyProvider) : Cipher {

    override fun encrypt(plaintext: String, aad: ByteArray?): String {
        val cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION).apply {
            init(javax.crypto.Cipher.ENCRYPT_MODE, keyProvider.getOrCreate())
            aad?.let { updateAAD(it) }
        }
        val iv = cipher.iv                                                   // 12 bytes, random
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val packed = ByteArray(IV_LEN + ct.size).also {
            iv.copyInto(it, 0)
            ct.copyInto(it, IV_LEN)
        }
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    override fun decrypt(ciphertext: String, aad: ByteArray?): String {
        val packed = Base64.decode(ciphertext, Base64.NO_WRAP)
        require(packed.size > IV_LEN) { "ciphertext blob too short" }
        val iv = packed.copyOfRange(0, IV_LEN)
        val ct = packed.copyOfRange(IV_LEN, packed.size)
        val cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION).apply {
            init(
                javax.crypto.Cipher.DECRYPT_MODE,
                keyProvider.getOrCreate(),
                GCMParameterSpec(TAG_BITS, iv)
            )
            aad?.let { updateAAD(it) }
        }
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val TAG_BITS = 128
    }
}