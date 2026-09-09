package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Room manages ONLY the app's own tables (not inventory_master which is in a separate db)
@Database(
    entities = [
        User::class,
        Store::class,
        Item::class,
        Inventory::class,
        TransactionLog::class,
        StockHistory::class,
        InventoryMaster::class
    ],
    version = 11,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun inventoryDao(): InventoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN scanned_by TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN status TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN transaction_reason TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN initial_quantity INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN expected_return_date INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN is_resolved INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN is_pending_sync INTEGER NOT NULL DEFAULT 1")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS stock_history (
                        id TEXT NOT NULL PRIMARY KEY,
                        itemId TEXT NOT NULL,
                        storeId TEXT NOT NULL,
                        userId TEXT NOT NULL,
                        quantityChange INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        method TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        syncStatus TEXT NOT NULL,
                        scanned_by TEXT NOT NULL DEFAULT '',
                        status TEXT NOT NULL DEFAULT '',
                        transaction_reason TEXT NOT NULL DEFAULT '',
                        expected_return_date INTEGER NOT NULL DEFAULT 0,
                        is_resolved INTEGER NOT NULL DEFAULT 0,
                        is_pending_sync INTEGER NOT NULL DEFAULT 1
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS inventory_master (
                        [Item Code] TEXT NOT NULL PRIMARY KEY,
                        [Item Name] TEXT NOT NULL,
                        [Category] TEXT NOT NULL DEFAULT '',
                        [Sub Category] TEXT NOT NULL DEFAULT '',
                        [Asset Type] TEXT NOT NULL DEFAULT '',
                        [Storage Location] TEXT NOT NULL DEFAULT '',
                        [Specific Location / Rack] TEXT NOT NULL DEFAULT '',
                        [Total Quantity] INTEGER NOT NULL DEFAULT 0,
                        [Available Quantity] INTEGER NOT NULL DEFAULT 0,
                        [initial_quantity] INTEGER NOT NULL DEFAULT 0,
                        [Unit] TEXT NOT NULL DEFAULT '',
                        [Specification] TEXT NOT NULL DEFAULT '',
                        [Status] TEXT NOT NULL DEFAULT '',
                        [Owner / PIC] TEXT NOT NULL DEFAULT '',
                        [Last Stocktake Date] TEXT NOT NULL DEFAULT '',
                        [Items Out (Date)] TEXT NOT NULL DEFAULT '',
                        [Items In (Date)] TEXT NOT NULL DEFAULT '',
                        [Qty (Return)] INTEGER NOT NULL DEFAULT 0,
                        [Image] TEXT NOT NULL DEFAULT '',
                        [Remarks] TEXT NOT NULL DEFAULT '',
                        [last_updated] INTEGER NOT NULL DEFAULT 0,
                        [is_pending_sync] INTEGER NOT NULL DEFAULT 1
                    )
                """.trimIndent())
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "app_database"          // Different name from inventory.db to avoid conflict
                )
                .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class Converters {
    @TypeConverter fun fromSyncStatus(status: SyncStatus) = status.name
    @TypeConverter fun toSyncStatus(value: String) = SyncStatus.valueOf(value)

    @TypeConverter fun fromTransactionType(type: TransactionType) = type.name
    @TypeConverter fun toTransactionType(value: String): TransactionType {
        return try {
            TransactionType.valueOf(value)
        } catch (e: Exception) {
            when (value.uppercase()) {
                "CHECK_IN" -> TransactionType.IN
                "CHECK_OUT" -> TransactionType.OUT
                else -> TransactionType.OUT
            }
        }
    }

    @TypeConverter fun fromEntryMethod(method: EntryMethod) = method.name
    @TypeConverter fun toEntryMethod(value: String) = EntryMethod.valueOf(value)
}
