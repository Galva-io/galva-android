package io.galva.localstorage.crypto

import io.galva.common.InternalGalvaApi

@InternalGalvaApi
interface Cipher {
    fun encrypt(plaintext: String, aad: ByteArray? = null): String
    fun decrypt(ciphertext: String, aad: ByteArray? = null): String
}