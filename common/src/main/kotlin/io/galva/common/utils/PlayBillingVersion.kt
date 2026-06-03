package io.galva.common.utils

object PlayBillingVersion {
    val current: Int by lazy { detectVersion() }

    fun ensureMinimumVersion(minimum: Int = 8): Boolean {
        return current >= minimum
    }

    private fun detectVersion(): Int = when {
        hasClass("com.android.billingclient.api.QueryProductDetailsResult") -> 9
        // 9.0 added QueryProductDetailsResult; 8.0 returned List<ProductDetails> directly
        hasClass("com.android.billingclient.api.BillingClient\$BillingResponseCode") -> 8
        // 8.0 has BillingResponseCode as inner class
        else -> 7
    }

    private fun hasClass(name: String): Boolean = try {
        Class.forName(name); true
    } catch (e: ClassNotFoundException) { false }
}