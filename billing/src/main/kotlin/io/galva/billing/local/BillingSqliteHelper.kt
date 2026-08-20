package io.galva.billing.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

 class BillingSqliteHelper(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    DB_VERSION,
) {

    override fun onConfigure(db: SQLiteDatabase) {
        // Enable foreign keys for CASCADE deletes
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_PRODUCTS)
        db.execSQL(SQL_CREATE_BASE_PLANS)
        db.execSQL(SQL_CREATE_OFFERS)
        db.execSQL(SQL_CREATE_PRICING_PHASES)
        db.execSQL(SQL_INDEX_BASE_PLANS_PRODUCT)
        db.execSQL(SQL_INDEX_OFFERS_BASE_PLAN)
        db.execSQL(SQL_INDEX_PHASES_OFFER)
        db.execSQL(SQL_CREATE_ENTITLEMENT)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Simple drop+recreate strategy for v1. Replace with real migrations later.
        db.execSQL("DROP TABLE IF EXISTS pricing_phases")
        db.execSQL("DROP TABLE IF EXISTS offers")
        db.execSQL("DROP TABLE IF EXISTS base_plans")
        db.execSQL("DROP TABLE IF EXISTS products")
        db.execSQL("DROP TABLE IF EXISTS entitlement")
        onCreate(db)
    }

    companion object {
        const val DB_NAME = "galva-billing.db"
        const val DB_VERSION = 2

        private const val SQL_CREATE_PRODUCTS = """
            CREATE TABLE products (
                sku TEXT PRIMARY KEY NOT NULL,
                title TEXT NOT NULL,
                description TEXT NOT NULL,
                type TEXT NOT NULL
            )
        """

        private const val SQL_CREATE_BASE_PLANS = """
            CREATE TABLE base_plans (
                id TEXT NOT NULL,
                product_sku TEXT NOT NULL,
                tags TEXT NOT NULL DEFAULT '[]',
                PRIMARY KEY (id, product_sku),
                FOREIGN KEY (product_sku) REFERENCES products(sku) ON DELETE CASCADE
            )
        """

        private const val SQL_CREATE_OFFERS = """
            CREATE TABLE offers (
                offer_token TEXT NOT NULL,
                offer_id TEXT,
                base_plan_id TEXT NOT NULL,
                product_sku TEXT NOT NULL,
                tags TEXT NOT NULL DEFAULT '[]',
                PRIMARY KEY (offer_token,base_plan_id, product_sku),
                FOREIGN KEY (base_plan_id, product_sku)
                  REFERENCES base_plans(id, product_sku) ON DELETE CASCADE
            )
        """

        private const val SQL_CREATE_PRICING_PHASES = """
            CREATE TABLE pricing_phases (
                id TEXT PRIMARY KEY NOT NULL,
                offer_token TEXT NOT NULL,
                base_plan_id TEXT NOT NULL,
                product_sku TEXT NOT NULL,
                sequence INTEGER NOT NULL,
                price_micros INTEGER NOT NULL,
                price_formatted TEXT NOT NULL,
                currency_code TEXT NOT NULL,
                billing_period TEXT NOT NULL,
                billing_cycle_count INTEGER NOT NULL,
                recurrence_mode TEXT NOT NULL,
                FOREIGN KEY (offer_token,base_plan_id, product_sku) REFERENCES offers(offer_token,base_plan_id, product_sku) ON DELETE CASCADE
            )
        """

        private const val SQL_CREATE_ENTITLEMENT = """
            CREATE TABLE entitlement (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                product_id TEXT NOT NULL,
                purchase_token TEXT NOT NULL,
                order_id TEXT,
                acquired_at_ms INTEGER NOT NULL,
                is_auto_renewing INTEGER NOT NULL,
                state TEXT NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )
        """

        private const val SQL_INDEX_BASE_PLANS_PRODUCT =
            "CREATE INDEX idx_base_plans_product ON base_plans(product_sku)"
        private const val SQL_INDEX_OFFERS_BASE_PLAN =
            "CREATE INDEX idx_offers_base_plan ON offers(base_plan_id, product_sku)"
        private const val SQL_INDEX_PHASES_OFFER =
            "CREATE INDEX idx_phases_offer ON pricing_phases(offer_token)"
    }
}