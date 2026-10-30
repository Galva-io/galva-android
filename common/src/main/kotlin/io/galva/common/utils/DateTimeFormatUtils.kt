package io.galva.common.utils

import io.galva.common.InternalGalvaApi
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

@InternalGalvaApi
object DateTimeFormatUtils {
    // SimpleDateFormat isn't thread-safe, and building one per call is the expensive part.
    // (ThreadLocal.withInitial needs API 26; the SDK supports 24.)
    private val formatter = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    fun format(date: Calendar): String = formatter.get()?.format(date.time).orEmpty()
}
