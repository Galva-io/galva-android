package io.galva.common.utils

import android.content.Context
import java.lang.reflect.InvocationTargetException

object AdvertisingIdSource {

    fun getAndCacheGoogleAdvertisingId(context: Context): AdvertisingInfo? {
        return try {

            val advertisingIdClientClass = Class.forName(
                "com.google.android.gms.ads.identifier.AdvertisingIdClient"
            )

            val getAdvertisingIdInfoMethod = advertisingIdClientClass.getMethod(
                "getAdvertisingIdInfo", Context::class.java
            )

            val advertisingInfo = getAdvertisingIdInfoMethod.invoke(
                null, context
            ) ?: return null

            val infoClass = advertisingInfo.javaClass

            val isLimitAdTrackingEnabledMethod = infoClass.getMethod(
                "isLimitAdTrackingEnabled"
            )

            val limitAdTrackingEnabled = isLimitAdTrackingEnabledMethod.invoke(
                advertisingInfo
            ) as? Boolean ?: false

            val getIdMethod = infoClass.getMethod("getId")

            val advertisingId = getIdMethod.invoke(
                advertisingInfo
            ) as? String

            AdvertisingInfo(advertisingId, limitAdTrackingEnabled.not())

        } catch (_: ClassNotFoundException) {

            null

        } catch (_: NoSuchMethodException) {


            null

        } catch (_: InvocationTargetException) {

            null

        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }
    }
}

data class AdvertisingInfo(val id: String?, val enabled: Boolean)