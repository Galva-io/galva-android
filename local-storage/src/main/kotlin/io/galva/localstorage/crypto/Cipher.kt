package io.galva.localstorage.crypto

interface Cipher {
    fun encrypt(plaintext: String, aad: ByteArray? = null): String
    fun decrypt(ciphertext: String, aad: ByteArray? = null): String
}