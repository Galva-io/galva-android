package io.galva.sdk.impl.billing

import com.android.billingclient.api.ProductDetails
import io.galva.billing.model.ProductType
import io.galva.billing.model.RecurrenceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayBillingProductDetailsMapperTest {


    private val mapper = PlayBillingProductDetailsMapper()

    @Test
    fun `empty input returns empty catalog`() {
        val catalog = mapper.map(emptyList())
        assertEquals(0, catalog.products.size)
    }

    @Test
    fun `subscription with single offer maps fully`() {
        val details = listOf(
            stubProductDetails(
                sku = "premium",
                type = "subs",
                subscriptionOffers = listOf(stubSubOffer("monthly", "t1", "intro")),
            ),
        )

        val catalog = mapper.map(details)
        val product = catalog.products.single()

        assertEquals("premium", product.product.sku)
        assertEquals(ProductType.SUBSCRIPTION, product.product.type)
        assertEquals(1, product.basePlans.size)

        val plan = product.basePlans.single()
        assertEquals("monthly", plan.basePlan.id)
        assertEquals("premium", plan.basePlan.productSku)
        assertEquals(1, plan.offers.size)

        val offer = plan.offers.single()
        assertEquals("t1", offer.offer.offerToken)
        assertEquals("intro", offer.offer.offerId)
        assertEquals(1, offer.pricingPhases.size)
    }

    @Test
    fun `subscription with multiple offers per base plan`() {
        val details = listOf(
            stubProductDetails(
                sku = "premium",
                type = "subs",
                subscriptionOffers = listOf(
                    stubSubOffer("monthly", "t1", "intro"),
                    stubSubOffer("monthly", "t2", "promo"),
                    stubSubOffer("yearly", "t3", "intro"),
                ),
            ),
        )

        val catalog = mapper.map(details)
        val product = catalog.products.single()

        assertEquals(2, product.basePlans.size)
        val monthly = product.basePlans.first { it.basePlan.id == "monthly" }
        assertEquals(2, monthly.offers.size)
        assertEquals(setOf("t1", "t2"), monthly.offers.map { it.offer.offerToken }.toSet())

        val yearly = product.basePlans.first { it.basePlan.id == "yearly" }
        assertEquals(1, yearly.offers.size)
        assertEquals("t3", yearly.offers.single().offer.offerToken)
    }

    @Test
    fun `one-time product maps as synthetic base plan`() {
        val details = listOf(
            stubProductDetails(
                sku = "consumable",
                type = "inapp",
                oneTimeOffer = stubOneTime(priceMicros = 99_000_000L, formatted = "$0.99"),
            ),
        )

        val catalog = mapper.map(details)
        val product = catalog.products.single()

        assertEquals(ProductType.ONE_TIME, product.product.type)
        val basePlan = product.basePlans.single()
        assertEquals("consumable", basePlan.basePlan.id)

        val offer = basePlan.offers.single()
        assertEquals("consumable_onetime", offer.offer.offerToken)
        assertNull(offer.offer.offerId)

        val phase = offer.pricingPhases.single()
        assertEquals(99_000_000L, phase.priceMicros)
        assertEquals("$0.99", phase.priceFormatted)
        assertEquals(RecurrenceMode.NON_RECURRING, phase.recurrenceMode)
    }

    @Test
    fun `pricing phase sequence preserved by index`() {
        val phases = listOf(
            stubPhase(priceMicros = 0L, period = "P14D", cycles = 1),
            stubPhase(priceMicros = 4_990_000L, period = "P1M", cycles = 3),
            stubPhase(priceMicros = 9_990_000L, period = "P1M", cycles = 0),
        )
        val details = listOf(
            stubProductDetails(
                sku = "premium",
                type = "subs",
                subscriptionOffers = listOf(
                    stubSubOffer("monthly", "t1", "intro", phases = phases),
                ),
            ),
        )

        val catalog = mapper.map(details)
        val mappedPhases =
            catalog.products.single().basePlans.single().offers.single().pricingPhases

        assertEquals(listOf(0, 1, 2), mappedPhases.map { it.sequence })
        assertEquals(listOf(0L, 4_990_000L, 9_990_000L), mappedPhases.map { it.priceMicros })
    }

    @Test
    fun `recurrence mode mapped correctly`() {
        val details = listOf(
            stubProductDetails(
                sku = "p",
                type = "subs",
                subscriptionOffers = listOf(
                    stubSubOffer(
                        "bp", "t", null, phases = listOf(
                            stubPhase(recurrenceMode = ProductDetails.RecurrenceMode.INFINITE_RECURRING),
                            stubPhase(recurrenceMode = ProductDetails.RecurrenceMode.FINITE_RECURRING),
                            stubPhase(recurrenceMode = ProductDetails.RecurrenceMode.NON_RECURRING),
                        )
                    ),
                ),
            ),
        )

        val catalog = mapper.map(details)
        val mappedModes =
            catalog.products.single().basePlans.single().offers.single().pricingPhases.map { it.recurrenceMode }

        assertEquals(
            listOf(
                RecurrenceMode.INFINITE_RECURRING,
                RecurrenceMode.FINITE_RECURRING,
                RecurrenceMode.NON_RECURRING,
            ),
            mappedModes,
        )
    }

    @Test
    fun `unknown recurrence mode falls back to NON_RECURRING`() {
        val details = listOf(
            stubProductDetails(
                sku = "p", type = "subs",
                subscriptionOffers = listOf(
                    stubSubOffer("bp", "t", null, phases = listOf(stubPhase(recurrenceMode = 99))),
                ),
            ),
        )

        val catalog = mapper.map(details)
        val phase =
            catalog.products.single().basePlans.single().offers.single().pricingPhases.single()
        assertEquals(RecurrenceMode.NON_RECURRING, phase.recurrenceMode)
    }

    @Test
    fun `offer tags pass through`() {
        val details = listOf(
            stubProductDetails(
                sku = "premium",
                type = "subs",
                subscriptionOffers = listOf(
                    stubSubOffer("monthly", "t1", "intro", tags = listOf("free-trial", "regional")),
                ),
            ),
        )

        val catalog = mapper.map(details)
        val offer = catalog.products.single().basePlans.single().offers.single()
        assertEquals(listOf("free-trial", "regional"), offer.offer.tags)
    }

    @Test
    fun `multiple products in one call`() {
        val details = listOf(
            stubProductDetails(
                sku = "sub",
                type = "subs",
                subscriptionOffers = listOf(stubSubOffer("m", "t1", "intro")),
            ),
            stubProductDetails(
                sku = "oneoff",
                type = "inapp",
                oneTimeOffer = stubOneTime(priceMicros = 100_000L),
            ),
        )

        val catalog = mapper.map(details)
        assertEquals(2, catalog.products.size)
        assertEquals(setOf("sub", "oneoff"), catalog.products.map { it.product.sku }.toSet())
    }

    @Test
    fun `subscription with no offers produces empty basePlans`() {
        val details = listOf(
            stubProductDetails(sku = "p", type = "subs", subscriptionOffers = emptyList()),
        )

        val catalog = mapper.map(details)
        assertEquals(0, catalog.products.single().basePlans.size)

    }
}