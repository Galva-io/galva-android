package io.galva.operation_queue.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class OperationsSqliteHelper(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    DB_VERSION,
) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_OPERATIONS)
        db.execSQL(SQL_INDEX_CREATED_AT)
        db.execSQL(SQL_INDEX_LOCK)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS operations")
        onCreate(db)
    }

    companion object {
        const val DB_NAME = "galva-operations.db"
        const val DB_VERSION = 1

        private const val SQL_CREATE_OPERATIONS = """
            CREATE TABLE operations (
                id TEXT PRIMARY KEY NOT NULL,
                created_at INTEGER NOT NULL,
                type TEXT NOT NULL,
                payload TEXT NOT NULL,
                lock_token TEXT,
                locked_at INTEGER,
                retry_count INTEGER NOT NULL DEFAULT 0,
                last_error TEXT
            )
        """

        private const val SQL_INDEX_CREATED_AT =
            "CREATE INDEX idx_operations_created_at ON operations(created_at)"
        private const val SQL_INDEX_LOCK =
            "CREATE INDEX idx_operations_lock ON operations(lock_token, locked_at)"
    }
}