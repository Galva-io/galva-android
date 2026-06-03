package io.galva.common.utils

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager

data class Network(
    val bluetooth: Boolean,
    val carrier: String,
    val cellular: Boolean,
    val wifi: Boolean
)

object NetworkInfoSource {
    /**
     * Reads the current network state. Requires no permissions on modern Android
     * for the connectivity portion; `carrier` may be empty without READ_PHONE_STATE.
     */
    @SuppressLint("MissingPermission")
    fun read(context: Context): Network = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeCaps = cm?.let { it.getNetworkCapabilities(it.activeNetwork) }

        Network(
            bluetooth = activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) ?: false,
            cellular = (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                ?: false),
            wifi =(activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                ?: false),
            carrier = readCarrier(context),
        )
    } catch (_: Exception) {
        Network(bluetooth = false, carrier = "", cellular = false, wifi = false)
    }

    private fun readCarrier(context: Context): String = try {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        tm?.networkOperatorName.orEmpty()
    } catch (_: Exception) {
        ""
    }
}