package io.galva.localstorage.core

import io.galva.common.InternalGalvaApi
import javax.crypto.SecretKey

@InternalGalvaApi
interface KeyProvider {
    fun getOrCreate(): SecretKey
    fun exists(): Boolean
    fun delete()
}