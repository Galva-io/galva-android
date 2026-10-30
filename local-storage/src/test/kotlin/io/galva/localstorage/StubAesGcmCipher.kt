package io.galva.localstorage

import io.galva.localstorage.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.Cipher as JceCipher
import android.util.Base64
import javax.crypto.spec.GCMParameterSpec

internal class StubAesGcmCipher(key: SecretKey? = null) : Cipher {

    private val key: SecretKey =
        key ?: KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    override fun encrypt(plaintext: String, aad: ByteArray?): String {
        val cipher = JceCipher.getInstance("AES/GCM/NoPadding").apply {
            init(JceCipher.ENCRYPT_MODE, key)
            aad?.let { updateAAD(it) }
        }
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val packed = ByteArray(12 + ct.size).also {
            iv.copyInto(it, 0); ct.copyInto(it, 12)
        }
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    override fun decrypt(ciphertext: String, aad: ByteArray?): String {
        val packed = Base64.decode(ciphertext, Base64.NO_WRAP)
        val iv = packed.copyOfRange(0, 12)
        val ct = packed.copyOfRange(12, packed.size)
        val cipher = JceCipher.getInstance("AES/GCM/NoPadding").apply {
            init(JceCipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            aad?.let { updateAAD(it) }
        }
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }
}