package io.galva.billing.local

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import io.galva.billing.model.BasePlan
import io.galva.billing.model.BasePlanWithOffersModel
import io.galva.billing.model.FullCatalog
import io.galva.billing.model.Offer
import io.galva.billing.model.OfferWithPhasesModel
import io.galva.billing.model.PricingPhase
import io.galva.billing.model.Product
import io.galva.billing.model.ProductCatalog
import io.galva.billing.model.ProductType
import io.galva.billing.model.RecurrenceMode
import io.galva.billing.store.CatalogStore
import io.galva.common.logger.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class SqliteCatalogStore(
    private val helper: BillingSqliteHelper,
    private val logger: Logger,
    private val json: Json = Json.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CatalogStore {
    private val invalidations = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    // ── Reads ───────────────────────────────────────────────────────────────

    override suspend fun load(): FullCatalog = withContext(ioDispatcher) {
        loadInternal()
    }

    override fun observe(): Flow<FullCatalog> = invalidations.onStart { emit(Unit) }
        .mapLatest { withContext(ioDispatcher) { loadInternal() } }

    override fun observeProduct(sku: String): Flow<ProductCatalog?> {
        return invalidations.onStart { emit(Unit) }.mapLatest {
            withContext(ioDispatcher) {
                val product = getProduct(sku)
                if (product != null) {
                    println("Galva Product found for SKU $sku: ${product.title}")
                    val basePlans = loadBasePlansForProduct(helper.readableDatabase, sku)
                    println("Galva Loaded ${basePlans.size} base plans for product SKU $sku")
                    ProductCatalog(product, basePlans)
                } else {
                    println("Galva No product found for SKU $sku")
                    null
                }
            }
            }
    }

    override suspend fun getProductWithOffers(sku: String): ProductCatalog? {
        return withContext(ioDispatcher) {
            val product = getProduct(sku)
            if (product != null) {
                println("Galva Product found for SKU $sku: ${product.title}")
                val basePlans = loadBasePlansForProduct(helper.readableDatabase, sku)
                println("Galva Loaded ${basePlans.size} base plans for product SKU $sku")
                ProductCatalog(product, basePlans)
            } else {
                println("Galva No product found for SKU $sku")
                null
            }
        }
    }

    override suspend fun getProduct(sku: String): Product? = withContext(ioDispatcher) {
        val db = helper.readableDatabase
        db.rawQuery(
            "SELECT sku, title, description, type FROM products WHERE sku = ? LIMIT 1",
            arrayOf(sku),
        ).use { cursor ->
            if (cursor.moveToFirst()) readProduct(cursor) else null
        }
    }

    override suspend fun getOffer(token: String): Offer? = withContext(ioDispatcher) {
        val db = helper.readableDatabase
        db.rawQuery(
            "SELECT offer_token, offer_id, base_plan_id, tags FROM offers WHERE offer_token = ? LIMIT 1",
            arrayOf(token),
        ).use { cursor ->
            if (cursor.moveToFirst()) readOffer(cursor) else null
        }
    }

    override suspend fun getOfferForPlan(basePlanId: String, offerId: String): Offer? {
        val db = helper.readableDatabase
        return  db.rawQuery(
            """SELECT offer_token, offer_id, base_plan_id, tags FROM offers
               WHERE base_plan_id = ? AND offer_id = ? LIMIT 1""",
            arrayOf(basePlanId, offerId),
        ).use { cursor ->
            if (cursor.moveToFirst()) readOffer(cursor) else null
        }
    }

    private fun loadInternal(): FullCatalog {
        val db = helper.readableDatabase
        val products = mutableListOf<ProductCatalog>()

        // Load all products
        db.rawQuery("SELECT sku, title, description, type FROM products ORDER BY sku", null)
            .use { productCursor ->
                while (productCursor.moveToNext()) {
                    val product = readProduct(productCursor)

                    // Load base plans for this product
                    val basePlans = loadBasePlansForProduct(db, product.sku)

                    products.add(ProductCatalog(product, basePlans))
                }
            }

        return FullCatalog(products)
    }

    private fun loadBasePlansForProduct(
        db: SQLiteDatabase,
        productSku: String,
    ): List<BasePlanWithOffersModel> {
        val basePlans = mutableListOf<BasePlanWithOffersModel>()

        db.rawQuery(
            "SELECT id, product_sku, tags FROM base_plans WHERE product_sku = ? ORDER BY id",
            arrayOf(productSku),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val basePlanId = cursor.getString(0)
                val tags = decodeTags(cursor.getString(2))
                val basePlan = BasePlan(id = basePlanId, productSku = productSku, tags = tags)
                val offers = loadOffersForBasePlan(db, basePlanId, productSku)
                basePlans.add(BasePlanWithOffersModel(basePlan, offers))
            }
        }

        return basePlans
    }

    private fun loadOffersForBasePlan(
        db: SQLiteDatabase,
        basePlanId: String,
        productSku: String,
    ): List<OfferWithPhasesModel> {
        val offers = mutableListOf<OfferWithPhasesModel>()

        db.rawQuery(
            """SELECT offer_token, offer_id, base_plan_id, tags,product_sku FROM offers
               WHERE base_plan_id = ? AND product_sku = ?
               ORDER BY offer_token""",
            arrayOf(basePlanId, productSku),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val offer = readOffer(cursor)
                val phases = loadPhasesForOffer(db, productSku,basePlanId,offer.offerToken)
                offers.add(OfferWithPhasesModel(offer, phases))
            }
        }

        return offers
    }

    private fun loadPhasesForOffer(db: SQLiteDatabase,productId:String,basePlanId: String, offerToken: String): List<PricingPhase> {
        val phases = mutableListOf<PricingPhase>()

        db.rawQuery(
            """SELECT id, offer_token,base_plan_id,product_sku, sequence, price_micros, price_formatted,
                      currency_code, billing_period, billing_cycle_count, recurrence_mode
               FROM pricing_phases WHERE offer_token = ? and product_sku = ? and base_plan_id = ?  ORDER BY sequence ASC""",
            arrayOf(offerToken,productId,basePlanId),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                phases.add(
                    PricingPhase(
                        id = cursor.getString(0),
                        offerToken = cursor.getString(1),
                        basePlanId = cursor.getString(2),
                        productId = cursor.getString(3),
                        sequence = cursor.getInt(4),
                        priceMicros = cursor.getLong(5),
                        priceFormatted = cursor.getString(6),
                        currencyCode = cursor.getString(7),
                        billingPeriod = cursor.getString(8),
                        billingCycleCount = cursor.getInt(9),
                        recurrenceMode = RecurrenceMode.valueOf(cursor.getString(10)),
                    )
                )
            }
        }

        return phases
    }

    private fun readProduct(cursor: Cursor) = Product(
        sku = cursor.getString(0),
        title = cursor.getString(1),
        description = cursor.getString(2),
        type = ProductType.valueOf(cursor.getString(3)),
    )

    private fun readOffer(cursor: Cursor) = Offer(
        offerToken = cursor.getString(0),
        offerId = if (cursor.isNull(1)) null else cursor.getString(1),
        basePlanId = cursor.getString(2),
        tags = decodeTags(cursor.getString(3)),
    )

    private fun decodeTags(jsonString: String): List<String> = runCatching {
        json.decodeFromString(ListSerializer(String.Companion.serializer()), jsonString)
    }.getOrDefault(emptyList())

    private fun encodeTags(tags: List<String>): String =
        json.encodeToString(ListSerializer(String.serializer()), tags)

    // ── Writes ──────────────────────────────────────────────────────────────

    override suspend fun replaceAll(catalog: FullCatalog) = withContext(ioDispatcher) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            // Clear all (CASCADE deletes children)
            db.execSQL("DELETE FROM products")

            catalog.products.forEach { productCatalog ->
                insertProduct(db, productCatalog.product)
                productCatalog.basePlans.forEach { bp ->
                    insertBasePlan(db, bp.basePlan)
                    bp.offers.forEach { offer ->
                        insertOffer(db, offer.offer, productSku = productCatalog.product.sku)
                        offer.pricingPhases.forEach { phase ->
                            Log.e("GalvaBilling", "Inserting phase ${phase.id} for offer ${offer.offer.offerToken} and product ${productCatalog.product.sku}")
                            insertPhase(db,bp.basePlan.productSku,offer, phase)
                        }
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        invalidations.tryEmit(Unit)
        logger.debug { "Catalog replaced: ${catalog.products.size} products" }
    }

    override suspend fun clear() {
        withContext(ioDispatcher) {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM products")
                db.setTransactionSuccessful()
            }finally {
                db.endTransaction()
            }
            invalidations.tryEmit(Unit)
        }
    }

    private fun insertProduct(db: SQLiteDatabase, product: Product) {
        val values = ContentValues().apply {
            put("sku", product.sku)
            put("title", product.title)
            put("description", product.description)
            put("type", product.type.name)
        }
        db.insertWithOnConflict("products", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun insertBasePlan(db: SQLiteDatabase, basePlan: BasePlan) {
        val values = ContentValues().apply {
            put("id", basePlan.id)
            put("product_sku", basePlan.productSku)
            put("tags", encodeTags(basePlan.tags))
        }
        db.insertWithOnConflict("base_plans", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun insertOffer(db: SQLiteDatabase, offer: Offer, productSku: String) {
        val values = ContentValues().apply {
            put("offer_token", offer.offerToken)
            if (offer.offerId != null) put("offer_id", offer.offerId) else putNull("offer_id")
            put("base_plan_id", offer.basePlanId)
            put("product_sku", productSku)
            put("tags", encodeTags(offer.tags))
        }
        db.insertWithOnConflict("offers", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun insertPhase(db: SQLiteDatabase, productId:String,offer: OfferWithPhasesModel, phase: PricingPhase) {
        val values = ContentValues().apply {
            put("id", phase.id)
            put("offer_token", phase.offerToken)
            put("base_plan_id",offer.offer.basePlanId)
            put("product_sku",productId)
            put("sequence", phase.sequence)
            put("price_micros", phase.priceMicros)
            put("price_formatted", phase.priceFormatted)
            put("currency_code", phase.currencyCode)
            put("billing_period", phase.billingPeriod)
            put("billing_cycle_count", phase.billingCycleCount)
            put("recurrence_mode", phase.recurrenceMode.name)
        }
        db.insertWithOnConflict("pricing_phases", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }
}