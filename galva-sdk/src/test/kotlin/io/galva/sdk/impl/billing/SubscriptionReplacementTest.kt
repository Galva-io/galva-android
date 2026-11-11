package io.galva.sdk.impl.billing

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which owned subscription a subscription purchase replaces, and the account id it carries. */
class SubscriptionReplacementTest {
    private fun owned(token: String, accountId: String?, time: Long) = OwnedSubscription(token, accountId, time)

    @Test
    fun `without a subscription a new one is bought with the current account id`() {
        assertEquals(
            SubscriptionReplacement(oldPurchaseToken = null, obfuscatedAccountId = "acct-1"),
            SubscriptionReplacement.select(emptyList(), "acct-1"),
        )
    }

    @Test
    fun `the subscription bought with the current account id is replaced first, even if older`() {
        val subscriptions = listOf(
            owned("mine", "acct-1", time = 1),
            owned("newer", "other", time = 2),
        )

        assertEquals(SubscriptionReplacement("mine", "acct-1"), SubscriptionReplacement.select(subscriptions, "acct-1"))
    }

    @Test
    fun `the newest of several with the current account id is replaced`() {
        val subscriptions = listOf(owned("old", "acct-1", time = 1), owned("new", "acct-1", time = 2))

        assertEquals(SubscriptionReplacement("new", "acct-1"), SubscriptionReplacement.select(subscriptions, "acct-1"))
    }

    @Test
    fun `otherwise the newest subscription is replaced with its own account id`() {
        val hashed = "a".repeat(64)
        val subscriptions = listOf(owned("old", "other", time = 1), owned("new", hashed, time = 2))

        assertEquals(SubscriptionReplacement("new", hashed), SubscriptionReplacement.select(subscriptions, "acct-1"))
    }

    @Test
    fun `a subscription without an account id is replaced without one`() {
        val subscriptions = listOf(owned("legacy", null, time = 1))

        assertEquals(SubscriptionReplacement("legacy", null), SubscriptionReplacement.select(subscriptions, "acct-1"))
        assertEquals(SubscriptionReplacement("legacy", null), SubscriptionReplacement.select(subscriptions, null))
    }
}
