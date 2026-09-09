package com.example.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Opens the pre-packaged inventory.db from assets as a plain SQLite database.
 * Uses the ACTUAL column names from the db: [Item Code], [Storage Location], etc.
 * Column names are safely escaped with brackets [Column Name] to prevent runtime crashes.
 */
class AssetDbHelper(private val context: Context) {

    private val dbName = "inventory.db"
    private val dbFile: File get() = File(context.filesDir, dbName)

    private val prefs = context.getSharedPreferences("asset_db_prefs", Context.MODE_PRIVATE)
    private val DB_VERSION = 3 // Bumped to force reload of updated inventory.db with users table

    // Actual column names in the inventory_master table (with spaces, as exported)
    companion object {
        const val COL_CODE       = "Item Code"
        const val COL_NAME       = "Item Name"
        const val COL_CATEGORY   = "Category"
        const val COL_SUB_CAT    = "Sub Category"
        const val COL_ASSET_TYPE = "Asset Type"
        const val COL_STORAGE    = "Storage Location"
        const val COL_SPECIFIC   = "Specific Location / Rack"
        const val COL_TOTAL_QTY  = "Total Quantity"
        const val COL_AVAIL_QTY  = "Available Quantity"
        const val COL_UNIT       = "Unit"
        const val COL_SPEC       = "Specification"
        const val COL_STATUS     = "Status"
        const val COL_OWNER      = "Owner / PIC"
        const val COL_STOCKTAKE  = "Last Stocktake Date"
        const val COL_OUT_DATE   = "Items Out (Date)"
        const val COL_IN_DATE    = "Items In (Date)"
        const val COL_QTY_RET    = "Qty (Return)"
        const val COL_IMAGE      = "Image"
        const val COL_REMARKS    = "Remarks"
        const val COL_LAST_UPDATED = "last_updated"
        const val COL_IS_PENDING_SYNC = "is_pending_sync"
        const val TABLE          = "inventory_master"
    }

    /**
     * Copies db from assets to device storage on first run or when DB_VERSION is updated.
     * Subsequent queries reuse the existing file so user modifications are preserved.
     */
    @Synchronized
    private fun ensureDbCopied() {
        val currentVersion = prefs.getInt("db_version", 0)
        if (!dbFile.exists() || currentVersion < DB_VERSION) {
            dbFile.parentFile?.mkdirs()
            context.assets.open(dbName).use { input ->
                FileOutputStream(dbFile).use { output ->
                    input.copyTo(output)
                }
            }
            prefs.edit().putInt("db_version", DB_VERSION).apply()
        }
    }

    private fun openDb(): SQLiteDatabase {
        ensureDbCopied()
        val db = SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE
        )
        ensureLastUpdatedColumn(db)
        ensureInitialQuantityColumn(db)
        ensureIsPendingSyncColumn(db)
        return db
    }

    /**
     * Dynamically ensures the last_updated column exists in inventory_master table
     * to resolve multi-device sync conflicts.
     */
    private fun ensureLastUpdatedColumn(db: SQLiteDatabase) {
        try {
            val cursor = db.rawQuery("PRAGMA table_info([$TABLE])", null)
            var hasCol = false
            cursor.use {
                while (it.moveToNext()) {
                    val name = it.getString(1)
                    if (name.equals(COL_LAST_UPDATED, ignoreCase = true)) {
                        hasCol = true
                        break
                    }
                }
            }
            if (!hasCol) {
                db.execSQL("ALTER TABLE [$TABLE] ADD COLUMN [$COL_LAST_UPDATED] INTEGER DEFAULT 0")
            }
        } catch (e: Exception) {
            android.util.Log.w("AssetDbHelper", "Error ensuring last_updated column: ${e.message}")
        }
    }

    /**
     * Dynamically ensures the initial_quantity column exists in inventory_master table
     * to track the starting stock amount.
     */
    private fun ensureInitialQuantityColumn(db: SQLiteDatabase) {
        try {
            val cursor = db.rawQuery("PRAGMA table_info([$TABLE])", null)
            var hasCol = false
            cursor.use {
                while (it.moveToNext()) {
                    val name = it.getString(1)
                    if (name.equals("initial_quantity", ignoreCase = true)) {
                        hasCol = true
                        break
                    }
                }
            }
            if (!hasCol) {
                db.execSQL("ALTER TABLE [$TABLE] ADD COLUMN [initial_quantity] INTEGER DEFAULT 0")
                db.execSQL("UPDATE [$TABLE] SET [initial_quantity] = [$COL_TOTAL_QTY] WHERE [initial_quantity] = 0 OR [initial_quantity] IS NULL")
            }
        } catch (e: Exception) {
            android.util.Log.w("AssetDbHelper", "Error ensuring initial_quantity column: ${e.message}")
        }
    }

    /**
     * Dynamically ensures the is_pending_sync column exists in inventory_master table
     * for offline-first synchronization.
     */
    private fun ensureIsPendingSyncColumn(db: SQLiteDatabase) {
        try {
            val cursor = db.rawQuery("PRAGMA table_info([$TABLE])", null)
            var hasCol = false
            cursor.use {
                while (it.moveToNext()) {
                    val name = it.getString(1)
                    if (name.equals(COL_IS_PENDING_SYNC, ignoreCase = true)) {
                        hasCol = true
                        break
                    }
                }
            }
            if (!hasCol) {
                db.execSQL("ALTER TABLE [$TABLE] ADD COLUMN [$COL_IS_PENDING_SYNC] INTEGER DEFAULT 1")
            }
        } catch (e: Exception) {
            android.util.Log.w("AssetDbHelper", "Error ensuring is_pending_sync column: ${e.message}")
        }
    }

    // ---- Public queries (all column names escaped with brackets) ----

    /** All items, sorted by category then item name. */
    suspend fun getAllItems(): List<InventoryItem> = withContext(Dispatchers.IO) {
        val db = openDb()
        val results = mutableListOf<InventoryItem>()
        db.use {
            val cursor = it.rawQuery(
                "SELECT * FROM [$TABLE] ORDER BY [$COL_CATEGORY], [$COL_NAME]", null
            )
            cursor.use { c -> while (c.moveToNext()) results.add(cursorToItem(c)) }
        }
        results
    }

    /** Items filtered by storage location (= store ID). */
    suspend fun getItemsByStore(storeId: String): List<InventoryItem> = withContext(Dispatchers.IO) {
        val db = openDb()
        val results = mutableListOf<InventoryItem>()
        db.use {
            val trimmed = storeId.trim()
            val cursor = it.rawQuery(
                "SELECT * FROM [$TABLE] WHERE LOWER(TRIM([$COL_STORAGE])) = LOWER(?) ORDER BY [$COL_NAME]",
                arrayOf(trimmed)
            )
            cursor.use { c -> while (c.moveToNext()) results.add(cursorToItem(c)) }
        }
        results
    }

    /** Search across item name, item code, category, asset type, and location. */
    suspend fun searchItems(query: String): List<InventoryItem> = withContext(Dispatchers.IO) {
        val db = openDb()
        val results = mutableListOf<InventoryItem>()
        val like = "%${query.trim()}%"
        db.use {
            val cursor = it.rawQuery(
                """SELECT * FROM [$TABLE]
                   WHERE [$COL_NAME] LIKE ? OR [$COL_CODE] LIKE ? OR [$COL_CATEGORY] LIKE ? OR [$COL_ASSET_TYPE] LIKE ? OR [$COL_SPECIFIC] LIKE ?
                   ORDER BY [$COL_NAME]""",
                arrayOf(like, like, like, like, like)
            )
            cursor.use { c -> while (c.moveToNext()) results.add(cursorToItem(c)) }
        }
        results
    }

    /** Find an item by exact or partial name, token words, or reverse containment (for AI scan matching). */
    suspend fun findByName(name: String): InventoryItem? = withContext(Dispatchers.IO) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@withContext null
        val db = openDb()
        var item: InventoryItem? = null
        db.use {
            // 1. Direct match or code match or direct LIKE
            var cursor = it.rawQuery(
                """SELECT * FROM [$TABLE] 
                   WHERE LOWER([$COL_NAME]) = LOWER(?) 
                      OR LOWER([$COL_CODE]) = LOWER(?)
                      OR LOWER([$COL_NAME]) LIKE ?
                   LIMIT 1""",
                arrayOf(trimmed, trimmed, "%${trimmed.lowercase()}%")
            )
            cursor.use { c -> if (c.moveToFirst()) item = cursorToItem(c) }

            // 2. Reverse containment: query string contains item name (e.g. "nodemcu esp32" contains "NodeMCU")
            if (item == null) {
                cursor = it.rawQuery(
                    """SELECT * FROM [$TABLE] 
                       WHERE LOWER(?) LIKE '%' || LOWER([$COL_NAME]) || '%'
                         AND LENGTH([$COL_NAME]) >= 3
                       ORDER BY LENGTH([$COL_NAME]) DESC
                       LIMIT 1""",
                    arrayOf(trimmed)
                )
                cursor.use { c -> if (c.moveToFirst()) item = cursorToItem(c) }
            }

            // 3. Word token matching across name and specification
            if (item == null) {
                val words = trimmed.split(" ").filter { w -> w.length >= 3 }
                for (word in words) {
                    cursor = it.rawQuery(
                        """SELECT * FROM [$TABLE] 
                           WHERE LOWER([$COL_NAME]) LIKE ? OR LOWER([$COL_SPEC]) LIKE ?
                           LIMIT 1""",
                        arrayOf("%${word.lowercase()}%", "%${word.lowercase()}%")
                    )
                    cursor.use { c -> if (c.moveToFirst()) item = cursorToItem(c) }
                    if (item != null) break
                }
            }
        }
        item
    }

    /**
     * Query inventory_master table by Item Code (case-insensitive and trimmed).
     * Retrieves the item's details including Item Name, Asset Type, and Specific Location / Rack.
     */
    suspend fun getItemByCode(itemCode: String): InventoryItem? = withContext(Dispatchers.IO) {
        val db = openDb()
        var item: InventoryItem? = null
        val code = itemCode.trim()
        db.use {
            val cursor = it.rawQuery(
                "SELECT * FROM [$TABLE] WHERE LOWER(TRIM([$COL_CODE])) = LOWER(?) OR [$COL_CODE] = ? LIMIT 1",
                arrayOf(code, code)
            )
            cursor.use { c -> if (c.moveToFirst()) item = cursorToItem(c) }
        }
        item
    }

    /**
     * Specifically queries the database when an "Item Code" is scanned to retrieve:
     * - Item Name (from [$COL_NAME])
     * - Asset Type (from [$COL_ASSET_TYPE])
     * - Specific Location / Rack (from [$COL_SPECIFIC])
     */
    suspend fun getScannedItemByCode(itemCode: String): ScannedItemResult? = withContext(Dispatchers.IO) {
        val db = openDb()
        var result: ScannedItemResult? = null
        val code = itemCode.trim()
        db.use {
            val cursor = it.rawQuery(
                """SELECT [$COL_CODE], [$COL_NAME], [$COL_ASSET_TYPE], [$COL_SPECIFIC],
                          [$COL_STORAGE], [$COL_AVAIL_QTY], [$COL_TOTAL_QTY], [$COL_CATEGORY],
                          [$COL_STATUS], [$COL_UNIT]
                   FROM [$TABLE]
                   WHERE LOWER(TRIM([$COL_CODE])) = LOWER(?) OR [$COL_CODE] = ?
                   LIMIT 1""",
                arrayOf(code, code)
            )
            cursor.use { c ->
                if (c.moveToFirst()) {
                    fun str(idx: Int): String = try { c.getString(idx) ?: "" } catch (_: Exception) { "" }
                    fun int(idx: Int): Int = try { if (!c.isNull(idx)) c.getInt(idx) else 0 } catch (_: Exception) { 0 }
                    result = ScannedItemResult(
                        itemCode = str(0),
                        itemName = str(1),
                        assetType = str(2),
                        specificLocation = str(3),
                        storageLocation = str(4),
                        availableQuantity = int(5),
                        totalQuantity = int(6),
                        category = str(7),
                        status = str(8),
                        unit = str(9)
                    )
                }
            }
        }
        result
    }

    /**
     * Authenticate user credentials against the users table in inventory.db.
     */
    suspend fun authenticateUser(username: String, password: String): Boolean = withContext(Dispatchers.IO) {
        val db = openDb()
        var authenticated = false
        db.use {
            val cursor = it.rawQuery(
                "SELECT id FROM users WHERE username = ? AND password = ? LIMIT 1",
                arrayOf(username.trim(), password)
            )
            cursor.use { c -> authenticated = c.moveToFirst() }
        }
        authenticated
    }

    /** Update available quantity for an item in inventory_master. */
    suspend fun updateQuantity(itemCode: String, newQty: Int, timestamp: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val db = openDb()
        val code = itemCode.trim()
        db.use {
            it.execSQL(
                "UPDATE [$TABLE] SET [$COL_AVAIL_QTY] = ?, [$COL_LAST_UPDATED] = ?, [$COL_IS_PENDING_SYNC] = 1 WHERE LOWER(TRIM([$COL_CODE])) = LOWER(?) OR [$COL_CODE] = ?",
                arrayOf(newQty.toString(), timestamp.toString(), code, code)
            )
        }
    }

    /** Subtract quantity from an item in inventory_master table. */
    suspend fun subtractQuantity(itemCode: String, amount: Int): Int = withContext(Dispatchers.IO) {
        val item = getItemByCode(itemCode) ?: return@withContext -1
        val newQty = (item.available_quantity - amount).coerceAtLeast(0)
        updateQuantity(item.item_code, newQty)
        newQty
    }

    /** Add quantity to an item in inventory_master table. */
    suspend fun addQuantity(itemCode: String, amount: Int): Int = withContext(Dispatchers.IO) {
        val item = getItemByCode(itemCode) ?: return@withContext -1
        val newQty = item.available_quantity + amount
        updateQuantity(item.item_code, newQty)
        newQty
    }

    /** Update status for an item (e.g., 'Active', 'Under Maintenance', 'Lost', 'Disposed', 'Inactive'). */
    suspend fun updateStatus(itemCode: String, newStatus: String, timestamp: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val db = openDb()
        val code = itemCode.trim()
        db.use {
            it.execSQL(
                "UPDATE [$TABLE] SET [$COL_STATUS] = ?, [$COL_LAST_UPDATED] = ?, [$COL_IS_PENDING_SYNC] = 1 WHERE LOWER(TRIM([$COL_CODE])) = LOWER(?) OR [$COL_CODE] = ?",
                arrayOf(newStatus, timestamp.toString(), code, code)
            )
        }
    }

    /** Disposes item quantity permanently: decreases total_quantity in inventory_master. */
    suspend fun disposeQuantity(itemCode: String, amount: Int, timestamp: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val item = getItemByCode(itemCode) ?: return@withContext
        val newTotal = (item.total_quantity - amount).coerceAtLeast(0)
        val db = openDb()
        val code = itemCode.trim()
        db.use {
            it.execSQL(
                "UPDATE [$TABLE] SET [$COL_TOTAL_QTY] = ?, [$COL_LAST_UPDATED] = ?, [$COL_IS_PENDING_SYNC] = 1 WHERE LOWER(TRIM([$COL_CODE])) = LOWER(?) OR [$COL_CODE] = ?",
                arrayOf(newTotal.toString(), timestamp.toString(), code, code)
            )
        }
    }

    /** Mark an item in inventory_master as synced (is_pending_sync = 0). */
    suspend fun markItemSynced(itemCode: String) = withContext(Dispatchers.IO) {
        val db = openDb()
        val code = itemCode.trim()
        db.use {
            it.execSQL(
                "UPDATE [$TABLE] SET [$COL_IS_PENDING_SYNC] = 0 WHERE LOWER(TRIM([$COL_CODE])) = LOWER(?) OR [$COL_CODE] = ?",
                arrayOf(code, code)
            )
        }
    }

    /** Retrieve all items from inventory_master where is_pending_sync == 1. */
    suspend fun getPendingSyncItems(): List<InventoryItem> = withContext(Dispatchers.IO) {
        val db = openDb()
        val list = mutableListOf<InventoryItem>()
        db.use {
            val cursor = it.rawQuery("SELECT * FROM [$TABLE] WHERE [$COL_IS_PENDING_SYNC] = 1", null)
            cursor.use { c ->
                while (c.moveToNext()) {
                    list.add(cursorToItem(c))
                }
            }
        }
        list
    }

    /**
     * Updates an item from remote cloud data using Last-Write-Wins (LWW) conflict resolution.
     * Only updates if the incoming remoteItem.last_updated >= local item's last_updated.
     * Returns true if local DB was updated, false if remote was older or ignored.
     */
    suspend fun updateItemFromRemote(remote: InventoryItem): Boolean = withContext(Dispatchers.IO) {
        val local = getItemByCode(remote.item_code)
        val db = openDb()
        db.use {
            if (local != null) {
                if (remote.last_updated > 0L && remote.last_updated < local.last_updated) {
                    // Local change is strictly newer, reject stale remote update
                    return@withContext false
                }
                it.execSQL(
                    """UPDATE [$TABLE] 
                       SET [$COL_AVAIL_QTY] = ?, [$COL_TOTAL_QTY] = ?, [$COL_STATUS] = ?, [$COL_LAST_UPDATED] = ?
                       WHERE LOWER(TRIM([$COL_CODE])) = LOWER(?) OR [$COL_CODE] = ?""",
                    arrayOf(
                        remote.available_quantity.toString(),
                        remote.total_quantity.toString(),
                        remote.status,
                        remote.last_updated.toString(),
                        remote.item_code.trim(),
                        remote.item_code.trim()
                    )
                )
                true
            } else {
                // Item does not exist locally yet, insert remote item
                it.execSQL(
                    """INSERT OR REPLACE INTO [$TABLE] (
                        [$COL_CODE], [$COL_NAME], [$COL_CATEGORY], [$COL_SUB_CAT],
                        [$COL_ASSET_TYPE], [$COL_STORAGE], [$COL_SPECIFIC], [$COL_TOTAL_QTY],
                        [$COL_AVAIL_QTY], [$COL_UNIT], [$COL_SPEC], [$COL_STATUS],
                        [$COL_LAST_UPDATED]
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    arrayOf(
                        remote.item_code, remote.item_name, remote.category, remote.sub_category,
                        remote.asset_type, remote.storage_location, remote.specific_location,
                        remote.total_quantity.toString(), remote.available_quantity.toString(),
                        remote.unit, remote.specification, remote.status, remote.last_updated.toString()
                    )
                )
                true
            }
        }
    }

    /** Get distinct storage location names. */
    suspend fun getStoreNames(): List<String> = withContext(Dispatchers.IO) {
        val db = openDb()
        val names = mutableListOf<String>()
        db.use {
            val cursor = it.rawQuery(
                """SELECT DISTINCT [$COL_STORAGE] FROM [$TABLE]
                   WHERE [$COL_STORAGE] IS NOT NULL AND [$COL_STORAGE] != ''
                   ORDER BY [$COL_STORAGE]""",
                null
            )
            cursor.use { c ->
                while (c.moveToNext()) {
                    c.getString(0)?.let { name -> names.add(name) }
                }
            }
        }
        names
    }

    // ---- Cursor → data class ----
    private fun cursorToItem(c: Cursor): InventoryItem {
        fun str(col: String): String = try {
            val idx = c.getColumnIndex(col)
            if (idx >= 0) c.getString(idx) ?: "" else ""
        } catch (_: Exception) { "" }

        fun int(col: String): Int = try {
            val idx = c.getColumnIndex(col)
            if (idx >= 0 && !c.isNull(idx)) c.getInt(idx) else 0
        } catch (_: Exception) { 0 }

        fun longVal(col: String): Long = try {
            val idx = c.getColumnIndex(col)
            if (idx >= 0 && !c.isNull(idx)) c.getLong(idx) else 0L
        } catch (_: Exception) { 0L }

        fun boolVal(col: String): Boolean = try {
            val idx = c.getColumnIndex(col)
            if (idx >= 0 && !c.isNull(idx)) c.getInt(idx) == 1 else true
        } catch (_: Exception) { true }

        val totalQty = int(COL_TOTAL_QTY)
        val initialQty = int("initial_quantity").let { if (it > 0) it else totalQty }

        return InventoryItem(
            item_code           = str(COL_CODE),
            item_name           = str(COL_NAME),
            category            = str(COL_CATEGORY),
            sub_category        = str(COL_SUB_CAT),
            asset_type          = str(COL_ASSET_TYPE),
            storage_location    = str(COL_STORAGE),
            specific_location   = str(COL_SPECIFIC),
            total_quantity      = totalQty,
            available_quantity  = int(COL_AVAIL_QTY),
            initial_quantity    = initialQty,
            unit                = str(COL_UNIT),
            specification       = str(COL_SPEC),
            status              = str(COL_STATUS),
            owner_pic           = str(COL_OWNER),
            last_stocktake_date = str(COL_STOCKTAKE),
            items_out_date      = str(COL_OUT_DATE),
            items_in_date       = str(COL_IN_DATE),
            qty_return          = int(COL_QTY_RET),
            image               = str(COL_IMAGE),
            remarks             = str(COL_REMARKS),
            last_updated        = longVal(COL_LAST_UPDATED),
            is_pending_sync     = boolVal(COL_IS_PENDING_SYNC)
        )
    }
}
