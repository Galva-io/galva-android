package io.galva.billing

import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import io.galva.billing.local.SqliteCatalogStore
import io.galva.billing.local.BillingSqliteHelper
import io.galva.billing.model.BasePlanWithOffersModel
import io.galva.billing.model.FullCatalog
import io.galva.billing.model.OfferWithPhasesModel
import io.galva.billing.model.PricingPhase
import io.galva.billing.model.Product
import io.galva.billing.model.ProductCatalog
import io.galva.billing.model.ProductType
import io.galva.billing.model.RecurrenceMode
import io.galva.common.logger.NoOpLogger
import io.galva.common.utils.JsonUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SqliteCatalogStoreTest {
    private lateinit var helper: BillingSqliteHelper
    private lateinit var store: SqliteCatalogStore

    @Before
    fun setUp() {
        helper = BillingSqliteHelper(ApplicationProvider.getApplicationContext())
        store = SqliteCatalogStore(
            helper = helper,
            logger = NoOpLogger,
            json = JsonUtils.defaultJson,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
    }

    @After
    fun tearDown() {
        helper.writableDatabase.execSQL("DELETE FROM products")
        helper.close()
    }

    // ── load() / empty state ───────────────────────────────────────────────

    @Test
    fun `load returns empty catalog when database is empty`() = runTest {
        val catalog = store.load()
        assertEquals(FullCatalog(emptyList()), catalog)
    }

    @Test fun `load returns persisted catalog after replaceAll`() = runTest {
        val expected = simpleCatalog()
        store.replaceAll(expected)

        val actual = store.load()
        assertEquals(expected, actual)
    }

    // ── replaceAll() ───────────────────────────────────────────────────────

    @Test fun `replaceAll persists single product with one offer`() = runTest {
        store.replaceAll(simpleCatalog(productSku = "premium_monthly", offerToken = "intro"))

        val loaded = store.load()
        assertEquals(1, loaded.products.size)
        val product = loaded.products.single().product
        assertEquals("premium_monthly", product.sku)
        assertEquals(ProductType.SUBSCRIPTION, product.type)
    }

    @Test fun `replaceAll replaces existing data`() = runTest {
        store.replaceAll(simpleCatalog(productSku = "p1"))
        store.replaceAll(simpleCatalog(productSku = "p2"))

        val skus = store.load().products.map { it.product.sku }
        assertEquals(listOf("p2"), skus)
    }

    @Test fun `replaceAll persists multiple products`() = runTest {
        val catalog = FullCatalog(
            products = listOf(
                ProductCatalog(
                    product = product("monthly"),
                    basePlans = listOf(
                        BasePlanWithOffersModel(
                            basePlan = basePlan("bp_m", "monthly"),
                            offers = listOf(
                                OfferWithPhasesModel(
                                    offer = offer("token_m", "bp_m"),
                                    pricingPhases = listOf(phase("ph_m", "token_m", 0)),
                                ),
                            ),
                        ),
                    ),
                ),
                ProductCatalog(
                    product = product("yearly"),
                    basePlans = listOf(
                        BasePlanWithOffersModel(
                            basePlan = basePlan("bp_y", "yearly"),
                            offers = listOf(
                                OfferWithPhasesModel(
                                    offer = offer("token_y", "bp_y"),
                                    pricingPhases = listOf(phase("ph_y", "token_y", 0)),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        store.replaceAll(catalog)

        val loaded = store.load()
        assertEquals(2, loaded.products.size)
        assertEquals(setOf("monthly", "yearly"), loaded.products.map { it.product.sku }.toSet())
    }

    @Test fun `replaceAll persists multiple base plans per product`() = runTest {
        val catalog = FullCatalog(
            products = listOf(
                ProductCatalog(
                    product = product("premium"),
                    basePlans = listOf(
                        BasePlanWithOffersModel(
                            basePlan = basePlan("monthly", "premium"),
                            offers = listOf(
                                OfferWithPhasesModel(offer("t_m", "monthly"), listOf(phase("ph_m", "t_m", 0))),
                            ),
                        ),
                        BasePlanWithOffersModel(
                            basePlan = basePlan("yearly", "premium"),
                            offers = listOf(
                                OfferWithPhasesModel(offer("t_y", "yearly"), listOf(phase("ph_y", "t_y", 0))),
                            ),
                        ),
                    ),
                ),
            ),
        )
        store.replaceAll(catalog)

        val loaded = store.load()
        val basePlans = loaded.products.single().basePlans
        assertEquals(2, basePlans.size)
        assertEquals(setOf("monthly", "yearly"), basePlans.map { it.basePlan.id }.toSet())
    }

    @Test fun `replaceAll persists multiple offers per base plan`() = runTest {
        val catalog = FullCatalog(
            products = listOf(
                ProductCatalog(
                    product = product("premium"),
                    basePlans = listOf(
                        BasePlanWithOffersModel(
                            basePlan = basePlan("monthly", "premium"),
                            offers = listOf(
                                OfferWithPhasesModel(offer("intro", "monthly"), listOf(phase("ph1", "intro", 0))),
                                OfferWithPhasesModel(offer("promo", "monthly"), listOf(phase("ph2", "promo", 0))),
                                OfferWithPhasesModel(offer("default", "monthly"), listOf(phase("ph3", "default", 0))),
                            ),
                        ),
                    ),
                ),
            ),
        )
        store.replaceAll(catalog)

        val offers = store.load().products.single().basePlans.single().offers
        assertEquals(3, offers.size)
        assertEquals(setOf("intro", "promo", "default"), offers.map { it.offer.offerToken }.toSet())
    }

    @Test fun `replaceAll persists multiple pricing phases ordered by sequence`() = runTest {
        val catalog = FullCatalog(
            products = listOf(
                ProductCatalog(
                    product = product("premium"),
                    basePlans = listOf(
                        BasePlanWithOffersModel(
                            basePlan = basePlan("monthly", "premium"),
                            offers = listOf(
                                OfferWithPhasesModel(
                                    offer = offer("intro", "monthly"),
                                    pricingPhases = listOf(
                                        phase("ph_2", "intro", sequence = 2, priceMicros = 9_990_000L),
                                        phase("ph_0", "intro", sequence = 0, priceMicros = 0L),
                                        phase("ph_1", "intro", sequence = 1, priceMicros = 4_990_000L),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        store.replaceAll(catalog)

        val phases = store.load().products.single().basePlans.single().offers.single().pricingPhases
        assertEquals(listOf(0, 1, 2), phases.map { it.sequence })
        assertEquals(listOf(0L, 4_990_000L, 9_990_000L), phases.map { it.priceMicros })
    }

    @Test fun `replaceAll handles empty catalog`() = runTest {
        store.replaceAll(simpleCatalog())
        store.replaceAll(FullCatalog(emptyList()))

        assertEquals(FullCatalog(emptyList()), store.load())
    }

    // ── CASCADE behavior ───────────────────────────────────────────────────

    @Test fun `removing product cascades to base plans offers and phases`() = runTest {
        store.replaceAll(simpleCatalog(productSku = "p", basePlanId = "bp", offerToken = "t"))
        assertNotNull(store.getOffer("t"))

        store.replaceAll(FullCatalog(emptyList()))
        assertNull(store.getOffer("t"))

        val db = helper.readableDatabase
        val basePlanCount = db.rawQuery(
            "SELECT COUNT(*) FROM base_plans WHERE product_sku = ?",
            arrayOf("p"),
        ).use { it.moveToFirst(); it.getInt(0) }
        assertEquals(0, basePlanCount)

        val phaseCount = db.rawQuery(
            "SELECT COUNT(*) FROM pricing_phases WHERE offer_token = ?",
            arrayOf("t"),
        ).use { it.moveToFirst(); it.getInt(0) }
        assertEquals(0, phaseCount)
    }

    @Test fun `replacing product wipes its old base plans`() = runTest {
        val first = FullCatalog(
            products = listOf(
                ProductCatalog(
                    product = product("p"),
                    basePlans = listOf(
                        BasePlanWithOffersModel(
                            basePlan = basePlan("monthly", "p"),
                            offers = listOf(OfferWithPhasesModel(offer("t1", "monthly"), listOf(phase("ph1", "t1", 0)))),
                        ),
                        BasePlanWithOffersModel(
                            basePlan = basePlan("yearly", "p"),
                            offers = listOf(OfferWithPhasesModel(offer("t2", "yearly"), listOf(phase("ph2", "t2", 0)))),
                        ),
                    ),
                ),
            ),
        )
        store.replaceAll(first)
        assertEquals(2, store.load().products.single().basePlans.size)

        val second = simpleCatalog(productSku = "p", basePlanId = "monthly", offerToken = "t1")
        store.replaceAll(second)

        val basePlans = store.load().products.single().basePlans
        assertEquals(1, basePlans.size)
        assertEquals("monthly", basePlans.single().basePlan.id)
    }

    // ── getProduct ─────────────────────────────────────────────────────────

    @Test fun `getProduct returns null when missing`() = runTest {
        assertNull(store.getProduct("missing"))
    }

    @Test fun `getProduct returns product after replace`() = runTest {
        store.replaceAll(simpleCatalog(productSku = "premium"))

        val found = store.getProduct("premium")
        assertNotNull(found)
        assertEquals("premium", found!!.sku)
        assertEquals(ProductType.SUBSCRIPTION, found.type)
    }

    @Test fun `getProduct preserves title and description`() = runTest {
        val catalog = FullCatalog(
            products = listOf(
                ProductCatalog(
                    product = Product(
                        sku = "premium",
                        title = "Premium Subscription",
                        description = "Unlock all features",
                        type = ProductType.SUBSCRIPTION,
                    ),
                    basePlans = emptyList(),
                ),
            ),
        )
        store.replaceAll(catalog)

        val found = store.getProduct("premium")!!
        assertEquals("Premium Subscription", found.title)
        assertEquals("Unlock all features", found.description)
    }

    @Test fun `getProduct returns ONE_TIME product`() = runTest {
        val catalog = FullCatalog(
            products = listOf(
                ProductCatalog(
                    product = product("consumable", type = ProductType.ONE_TIME),
                    basePlans = emptyList(),
                ),
            ),
        )
        store.replaceAll(catalog)

        val found = store.getProduct("consumable")
        assertEquals(ProductType.ONE_TIME, found?.type)
    }

    // ── getOffer ───────────────────────────────────────────────────────────

    @Test fun `getOffer returns null when missing`() = runTest {
        assertNull(store.getOffer("nonexistent"))
    }

    @Test fun `getOffer locates offer by token`() = runTest {
        store.replaceAll(simpleCatalog(offerToken = "AUj_001"))

        val found = store.getOffer("AUj_001")
        assertNotNull(found)
        assertEquals("AUj_001", found!!.offerToken)
    }

    @Test fun `getOffer with offerId returns it`() = runTest {
        store.replaceAll(simpleCatalog(offerToken = "token", offerId = "intro_offer"))

        val found = store.getOffer("token")
        assertEquals("intro_offer", found?.offerId)
    }

    @Test fun `getOffer with null offerId returns null`() = runTest {
        val catalog = FullCatalog(
            products = listOf(
                ProductCatalog(
                    product = product("p"),
                    basePlans = listOf(
                        BasePlanWithOffersModel(
                            basePlan = basePlan("bp", "p"),
                            offers = listOf(
                                OfferWithPhasesModel(
                                    offer = offer("token", "bp", offerId = null),
                                    pricingPhases = listOf(phase("ph", "token", 0)),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        store.replaceAll(catalog)

        val found = store.getOffer("token")
        assertNotNull(found)
        assertNull(found!!.offerId)
    }

    // ── observe() ──────────────────────────────────────────────────────────

    @Test fun `observe emits empty catalog initially`() = runTest {
        store.observe().test {
            assertEquals(FullCatalog(emptyList()), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `observe emits initial state then updates`() = runTest {
        store.observe().test {
            assertEquals(FullCatalog(emptyList()), awaitItem())

            store.replaceAll(simpleCatalog(productSku = "p1"))
            val first = awaitItem()
            assertEquals(1, first.products.size)
            assertEquals("p1", first.products.single().product.sku)

            store.replaceAll(simpleCatalog(productSku = "p2"))
            val second = awaitItem()
            assertEquals("p2", second.products.single().product.sku)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `observe emits empty after clear`() = runTest {
        store.replaceAll(simpleCatalog())

        store.observe().test {
            val initial = awaitItem()
            assertEquals(1, initial.products.size)

            store.clear()
            val cleared = awaitItem()
            assertEquals(0, cleared.products.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `observe reflects nested updates`() = runTest {
        store.observe().test {
            awaitItem()

            store.replaceAll(simpleCatalog(productSku = "p", offerToken = "t1"))
            var current = awaitItem()
            assertEquals(1, current.products.single().basePlans.single().offers.size)

            val updated = FullCatalog(
                products = listOf(
                    ProductCatalog(
                        product = product("p"),
                        basePlans = listOf(
                            BasePlanWithOffersModel(
                                basePlan = basePlan("monthly", "p"),
                                offers = listOf(
                                    OfferWithPhasesModel(offer("t1", "monthly"), listOf(phase("ph1", "t1", 0))),
                                    OfferWithPhasesModel(offer("t2", "monthly"), listOf(phase("ph2", "t2", 0))),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            store.replaceAll(updated)

            current = awaitItem()
            assertEquals(2, current.products.single().basePlans.single().offers.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `multiple subscribers each receive emissions`() = runTest {
        store.observe().test {
            assertEquals(FullCatalog(emptyList()), awaitItem())

            store.observe().test {
                assertEquals(FullCatalog(emptyList()), awaitItem())

                store.replaceAll(simpleCatalog(productSku = "p"))

                val innerEmission = awaitItem()
                assertEquals("p", innerEmission.products.single().product.sku)

                cancelAndIgnoreRemainingEvents()
            }

            val outerEmission = awaitItem()
            assertEquals("p", outerEmission.products.single().product.sku)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── clear() ────────────────────────────────────────────────────────────

    @Test fun `clear removes all data`() = runTest {
        store.replaceAll(simpleCatalog())
        assertEquals(1, store.load().products.size)

        store.clear()

        assertTrue(store.load().products.isEmpty())
    }

    @Test fun `clear cascades to all child tables`() = runTest {
        store.replaceAll(simpleCatalog(productSku = "p", basePlanId = "bp", offerToken = "t"))

        store.clear()

        val db = helper.readableDatabase
        val productCount = db.rawQuery("SELECT COUNT(*) FROM products", null)
            .use { it.moveToFirst(); it.getInt(0) }
        val basePlanCount = db.rawQuery("SELECT COUNT(*) FROM base_plans", null)
            .use { it.moveToFirst(); it.getInt(0) }
        val offerCount = db.rawQuery("SELECT COUNT(*) FROM offers", null)
            .use { it.moveToFirst(); it.getInt(0) }
        val phaseCount = db.rawQuery("SELECT COUNT(*) FROM pricing_phases", null)
            .use { it.moveToFirst(); it.getInt(0) }

        assertEquals(0, productCount)
        assertEquals(0, basePlanCount)
        assertEquals(0, offerCount)
        assertEquals(0, phaseCount)
    }

    @Test fun `clear on empty store is no-op`() = runTest {
        store.clear()
        assertEquals(FullCatalog(emptyList()), store.load())
    }

    // ── JSON tags persistence ──────────────────────────────────────────────

    @Test fun `base plan tags round-trip through JSON`() = runTest {
        val catalog = FullCatalog(
            products = listOf(
                ProductCatalog(
                    product = product("p"),
                    basePlans = listOf(
                        BasePlanWithOffersModel(
                            basePlan = basePlan("bp", "p", tags = listOf("recommended", "popular")),
                            offers = listOf(
                                OfferWithPhasesModel(offer("t", "bp"), listOf(phase("ph", "t", 0))),
                            ),
                        ),
                    ),
                ),
            ),
        )
        store.replaceAll(catalog)

        val loaded = store.load().products.single().basePlans.single().basePlan
        assertEquals(listOf("recommended", "popular"), loaded.tags)
    }

    @Test fun `offer tags round-trip through JSON`() = runTest {
        val catalog = FullCatalog(
            products = listOf(
                ProductCatalog(
                    product = product("p"),
                    basePlans = listOf(
                        BasePlanWithOffersModel(
                            basePlan = basePlan("bp", "p"),
                            offers = listOf(
                                OfferWithPhasesModel(
                                    offer = offer("t", "bp", tags = listOf("free-trial", "regional", "limited")),
                                    pricingPhases = listOf(phase("ph", "t", 0)),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        store.replaceAll(catalog)

        val loaded = store.load().products.single().basePlans.single().offers.single().offer
        assertEquals(listOf("free-trial", "regional", "limited"), loaded.tags)
    }

    @Test fun `empty tags round-trip correctly`() = runTest {
        store.replaceAll(simpleCatalog())

        val loaded = store.load().products.single()
        assertEquals(emptyList<String>(), loaded.basePlans.single().basePlan.tags)
        assertEquals(emptyList<String>(), loaded.basePlans.single().offers.single().offer.tags)
    }

    // ── Pricing phase details ──────────────────────────────────────────────

    @Test fun `pricing phase preserves all fields`() = runTest {
        val customPhase = PricingPhase(
            id = "ph_custom",
            offerToken = "t",
            productId = "p",
            basePlanId = "bp",
            sequence = 0,
            priceMicros = 4_990_000L,
            priceFormatted = "$4.99",
            currencyCode = "USD",
            billingPeriod = "P1M",
            billingCycleCount = 3,
            recurrenceMode = RecurrenceMode.FINITE_RECURRING,
        )
        val catalog = FullCatalog(
            products = listOf(
                ProductCatalog(
                    product = product("p"),
                    basePlans = listOf(
                        BasePlanWithOffersModel(
                            basePlan = basePlan("bp", "p"),
                            offers = listOf(
                                OfferWithPhasesModel(
                                    offer = offer("t", "bp"),
                                    pricingPhases = listOf(customPhase),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        store.replaceAll(catalog)

        val loaded = store.load().products.single().basePlans.single().offers.single().pricingPhases.single()
        assertEquals("ph_custom", loaded.id)
        assertEquals("t", loaded.offerToken)
        assertEquals(0, loaded.sequence)
        assertEquals(4_990_000L, loaded.priceMicros)
        assertEquals("$4.99", loaded.priceFormatted)
        assertEquals("USD", loaded.currencyCode)
        assertEquals("P1M", loaded.billingPeriod)
        assertEquals(3, loaded.billingCycleCount)
        assertEquals(RecurrenceMode.FINITE_RECURRING, loaded.recurrenceMode)
    }

    @Test fun `all recurrence modes persist correctly`() = runTest {
        val catalog = FullCatalog(
            products = listOf(
                ProductCatalog(
                    product = product("p"),
                    basePlans = listOf(
                        BasePlanWithOffersModel(
                            basePlan = basePlan("bp", "p"),
                            offers = listOf(
                                OfferWithPhasesModel(
                                    offer = offer("t", "bp"),
                                    pricingPhases = listOf(
                                        phase("ph_1", "t", sequence = 0, recurrenceMode = RecurrenceMode.INFINITE_RECURRING),
                                        phase("ph_2", "t", sequence = 1, recurrenceMode = RecurrenceMode.FINITE_RECURRING),
                                        phase("ph_3", "t", sequence = 2, recurrenceMode = RecurrenceMode.NON_RECURRING),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        store.replaceAll(catalog)

        val modes = store.load().products.single().basePlans.single().offers.single()
            .pricingPhases.map { it.recurrenceMode }
        assertEquals(
            listOf(
                RecurrenceMode.INFINITE_RECURRING,
                RecurrenceMode.FINITE_RECURRING,
                RecurrenceMode.NON_RECURRING,
            ),
            modes,
        )
    }

    // ── Transactions / atomicity ───────────────────────────────────────────

    @Test fun `replaceAll uses transaction — no partial state visible`() = runTest {
        store.replaceAll(simpleCatalog(productSku = "p1"))
        store.replaceAll(simpleCatalog(productSku = "p2"))

        val final = store.load()
        assertEquals("p2", final.products.single().product.sku)
        assertEquals(1, final.products.size)
    }

    // ── Concurrent operations ──────────────────────────────────────────────

    @Test fun `concurrent reads work correctly`() = runTest {
        store.replaceAll(simpleCatalog(productSku = "p", offerToken = "t"))

        val productResult = async { store.getProduct("p") }
        val offerResult = async { store.getOffer("t") }
        val catalogResult = async { store.load() }

        assertEquals("p", productResult.await()?.sku)
        assertEquals("t", offerResult.await()?.offerToken)
        assertEquals(1, catalogResult.await().products.size)
    }

    // ── Edge cases ─────────────────────────────────────────────────────────

    @Test fun `product with no base plans persists correctly`() = runTest {
        val catalog = FullCatalog(
            products = listOf(
                ProductCatalog(
                    product = product("orphan"),
                    basePlans = emptyList(),
                ),
            ),
        )
        store.replaceAll(catalog)

        val loaded = store.load()
        assertEquals(1, loaded.products.size)
        assertEquals(0, loaded.products.single().basePlans.size)
    }

    @Test fun `base plan with no offers persists correctly`() = runTest {
        val catalog = FullCatalog(
            products = listOf(
                ProductCatalog(
                    product = product("p"),
                    basePlans = listOf(
                        BasePlanWithOffersModel(
                            basePlan = basePlan("bp", "p"),
                            offers = emptyList(),
                        ),
                    ),
                ),
            ),
        )
        store.replaceAll(catalog)

        val loaded = store.load()
        assertEquals(1, loaded.products.single().basePlans.size)
        assertEquals(0, loaded.products.single().basePlans.single().offers.size)
    }

    @Test fun `large catalog persists correctly`() = runTest {
        val products = (1..50).map { i ->
            ProductCatalog(
                product = product("product_$i"),
                basePlans = listOf(
                    BasePlanWithOffersModel(
                        basePlan = basePlan("bp_$i", "product_$i"),
                        offers = listOf(
                            OfferWithPhasesModel(
                                offer = offer("token_$i", "bp_$i"),
                                pricingPhases = listOf(phase("ph_$i", "token_$i", 0)),
                            ),
                        ),
                    ),
                ),
            )
        }
        val catalog = FullCatalog(products)
        store.replaceAll(catalog)

        val loaded = store.load()
        assertEquals(50, loaded.products.size)
        val loadedSkus = loaded.products.map { it.product.sku }.toSet()
        assertTrue(loadedSkus.containsAll(setOf("product_1", "product_25", "product_50")))
    }

    @Test fun `special characters in product fields persist correctly`() = runTest {
        val catalog = FullCatalog(
            products = listOf(
                ProductCatalog(
                    product = Product(
                        sku = "premium_pro",
                        title = "Premium™ Pro — Best Value!",
                        description = "100% \"satisfaction\" guaranteed\nMulti-line description",
                        type = ProductType.SUBSCRIPTION,
                    ),
                    basePlans = emptyList(),
                ),
            ),
        )
        store.replaceAll(catalog)

        val loaded = store.getProduct("premium_pro")!!
        assertEquals("Premium™ Pro — Best Value!", loaded.title)
        assertEquals("100% \"satisfaction\" guaranteed\nMulti-line description", loaded.description)
    }

    @Test fun `tags with special characters persist correctly`() = runTest {
        val catalog = FullCatalog(
            products = listOf(
                ProductCatalog(
                    product = product("p"),
                    basePlans = listOf(
                        BasePlanWithOffersModel(
                            basePlan = basePlan("bp", "p"),
                            offers = listOf(
                                OfferWithPhasesModel(
                                    offer = offer("t", "bp", tags = listOf("tag-with-dash", "tag_with_underscore", "tag.with.dots")),
                                    pricingPhases = listOf(phase("ph", "t", 0)),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        store.replaceAll(catalog)

        val loaded = store.getOffer("t")!!
        assertEquals(listOf("tag-with-dash", "tag_with_underscore", "tag.with.dots"), loaded.tags)
    }
    // ── observeProduct() ───────────────────────────────────────────────────

    @Test fun `observeProduct emits null when product does not exist`() = runTest {
        store.observeProduct("missing").test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `observeProduct emits product when it exists`() = runTest {
        store.replaceAll(simpleCatalog(productSku = "premium", offerToken = "intro"))

        store.observeProduct("premium").test {
            val emission = awaitItem()
            assertNotNull(emission)
            assertEquals("premium", emission!!.product.sku)
            assertEquals(1, emission.basePlans.size)
            assertEquals(1, emission.basePlans.single().offers.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `observeProduct emits null then product when added`() = runTest {
        store.observeProduct("new_product").test {
            assertNull(awaitItem())

            store.replaceAll(simpleCatalog(productSku = "new_product"))
            val emission = awaitItem()
            assertNotNull(emission)
            assertEquals("new_product", emission!!.product.sku)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `observeProduct emits null when product is removed`() = runTest {
        store.replaceAll(simpleCatalog(productSku = "p"))

        store.observeProduct("p").test {
            val initial = awaitItem()
            assertNotNull(initial)

            store.replaceAll(FullCatalog(emptyList()))
            assertNull(awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `observeProduct emits null after clear`() = runTest {
        store.replaceAll(simpleCatalog(productSku = "p"))

        store.observeProduct("p").test {
            assertNotNull(awaitItem())

            store.clear()
            assertNull(awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `observeProduct emits updated product when replaced`() = runTest {
        store.replaceAll(simpleCatalog(productSku = "p", offerToken = "t1"))

        store.observeProduct("p").test {
            val initial = awaitItem()
            assertEquals(1, initial!!.basePlans.single().offers.size)

            // Replace with same product but more offers
            val updated = FullCatalog(
                products = listOf(
                    ProductCatalog(
                        product = product("p"),
                        basePlans = listOf(
                            BasePlanWithOffersModel(
                                basePlan = basePlan("monthly", "p"),
                                offers = listOf(
                                    OfferWithPhasesModel(offer("t1", "monthly"), listOf(phase("ph1", "t1", 0))),
                                    OfferWithPhasesModel(offer("t2", "monthly"), listOf(phase("ph2", "t2", 0))),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            store.replaceAll(updated)

            val updated_emission = awaitItem()
            assertEquals(2, updated_emission!!.basePlans.single().offers.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `observeProduct returns nested base plans and offers correctly`() = runTest {
        val catalog = FullCatalog(
            products = listOf(
                ProductCatalog(
                    product = product("premium"),
                    basePlans = listOf(
                        BasePlanWithOffersModel(
                            basePlan = basePlan("monthly", "premium", tags = listOf("popular")),
                            offers = listOf(
                                OfferWithPhasesModel(
                                    offer = offer("intro", "monthly", offerId = "intro_offer", tags = listOf("trial")),
                                    pricingPhases = listOf(
                                        phase("ph_0", "intro", sequence = 0, priceMicros = 0L),
                                        phase("ph_1", "intro", sequence = 1, priceMicros = 9_990_000L),
                                    ),
                                ),
                            ),
                        ),
                        BasePlanWithOffersModel(
                            basePlan = basePlan("yearly", "premium"),
                            offers = listOf(
                                OfferWithPhasesModel(
                                    offer = offer("yearly_offer", "yearly"),
                                    pricingPhases = listOf(phase("yp_0", "yearly_offer", 0, priceMicros = 99_990_000L)),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        store.replaceAll(catalog)

        store.observeProduct("premium").test {
            val emission = awaitItem()
            assertNotNull(emission)
            assertEquals(2, emission!!.basePlans.size)

            val monthly = emission.basePlans.first { it.basePlan.id == "monthly" }
            assertEquals(listOf("popular"), monthly.basePlan.tags)
            assertEquals(2, monthly.offers.single().pricingPhases.size)

            val yearly = emission.basePlans.first { it.basePlan.id == "yearly" }
            assertEquals(99_990_000L, yearly.offers.single().pricingPhases.single().priceMicros)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `observeProduct multiple subscribers each receive emissions`() = runTest {
        store.observeProduct("p").test {
            assertNull(awaitItem())

            store.observeProduct("p").test {
                assertNull(awaitItem())

                store.replaceAll(simpleCatalog(productSku = "p"))
                assertNotNull(awaitItem())

                cancelAndIgnoreRemainingEvents()
            }

            assertNotNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `observeProduct emits product with no base plans`() = runTest {
        val catalog = FullCatalog(
            products = listOf(
                ProductCatalog(
                    product = product("orphan"),
                    basePlans = emptyList(),
                ),
            ),
        )
        store.replaceAll(catalog)

        store.observeProduct("orphan").test {
            val emission = awaitItem()
            assertNotNull(emission)
            assertEquals("orphan", emission!!.product.sku)
            assertEquals(0, emission.basePlans.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `observeProduct preserves all product fields`() = runTest {
        val catalog = FullCatalog(
            products = listOf(
                ProductCatalog(
                    product = Product(
                        sku = "premium",
                        title = "Premium Subscription",
                        description = "Unlock everything",
                        type = ProductType.SUBSCRIPTION,
                    ),
                    basePlans = emptyList(),
                ),
            ),
        )
        store.replaceAll(catalog)

        store.observeProduct("premium").test {
            val emission = awaitItem()!!
            assertEquals("premium", emission.product.sku)
            assertEquals("Premium Subscription", emission.product.title)
            assertEquals("Unlock everything", emission.product.description)
            assertEquals(ProductType.SUBSCRIPTION, emission.product.type)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `observeProduct distinguishes between two different products`() = runTest {
        store.replaceAll(
            FullCatalog(
                products = listOf(
                    ProductCatalog(product = product("a"), basePlans = emptyList()),
                    ProductCatalog(product = product("b"), basePlans = emptyList()),
                ),
            ),
        )

        store.observeProduct("a").test {
            val emissionA = awaitItem()
            assertEquals("a", emissionA!!.product.sku)
            cancelAndIgnoreRemainingEvents()
        }

        store.observeProduct("b").test {
            val emissionB = awaitItem()
            assertEquals("b", emissionB!!.product.sku)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `observeProduct emits ONE_TIME product correctly`() = runTest {
        store.replaceAll(
            FullCatalog(
                products = listOf(
                    ProductCatalog(
                        product = product("consumable", type = ProductType.ONE_TIME),
                        basePlans = emptyList(),
                    ),
                ),
            ),
        )

        store.observeProduct("consumable").test {
            val emission = awaitItem()
            assertEquals(ProductType.ONE_TIME, emission!!.product.type)
            cancelAndIgnoreRemainingEvents()
        }
    }

}