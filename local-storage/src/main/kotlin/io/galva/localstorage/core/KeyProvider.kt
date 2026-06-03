package io.galva.localstorage.core

import javax.crypto.SecretKey

interface KeyProvider {
    fun getOrCreate(): SecretKey
    fun exists(): Boolean
    fun delete()
}