package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface InventoryDao {
    // ---- Users ----
    @Query("SELECT * FROM users WHERE username = :user AND password = :pass LIMIT 1")
    suspend fun getUserByCredentials(user: String, pass: String): User?

    @Query("SELECT * FROM users WHERE username = :username LIMIT 1")
    suspend fun getUserByUsername(username: String): User?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: User)

    // ---- Stores ----
    @Query("SELECT * FROM stores")
    fun getStores(): Flow<List<Store>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStores(stores: List<Store>)

    // ---- Items (for search & AI scan lookup) ----
    @Query("SELECT * FROM items")
    fun getItems(): Flow<List<Item>>

    @Query("SELECT * FROM items WHERE [Storage Location] = :selectedStoreRoom OR LOWER([Storage Location]) = LOWER(:selectedStoreRoom)")
    fun getItemsByStoreRoom(selectedStoreRoom: String): Flow<List<Item>>

    @Query("SELECT * FROM items WHERE ([Storage Location] = :selectedStoreRoom OR LOWER([Storage Location]) = LOWER(:selectedStoreRoom)) AND ([Item Name] LIKE '%' || :query || '%' OR [Category] LIKE '%' || :query || '%' OR [Item Code] LIKE '%' || :query || '%' OR [Asset Type] LIKE '%' || :query || '%' OR [Specific Location / Rack] LIKE '%' || :query || '%')")
    fun searchItemsByStoreRoom(selectedStoreRoom: String, query: String): Flow<List<Item>>

    @Query("SELECT * FROM items WHERE [Item Name] LIKE '%' || :query || '%' OR [Category] LIKE '%' || :query || '%' OR [Item Code] LIKE '%' || :query || '%' OR [Asset Type] LIKE '%' || :query || '%' OR [Specific Location / Rack] LIKE '%' || :query || '%'")
    fun searchItems(query: String): Flow<List<Item>>

    @Query("SELECT * FROM items WHERE [Item Name] = :name LIMIT 1")
    suspend fun getItemByName(name: String): Item?

    // Query item by scanned Item Code (id)
    @Query("SELECT * FROM items WHERE [Item Code] = :itemCode LIMIT 1")
    suspend fun getItemByCode(itemCode: String): Item?

    @Query("SELECT * FROM items WHERE LOWER([Item Code]) = LOWER(:itemCode) LIMIT 1")
    suspend fun getItemByCodeIgnoreCase(itemCode: String): Item?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: Item)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<Item>)

    // ---- Inventory quantity tracking ----
    @Query("SELECT * FROM inventory WHERE storeId = :storeId")
    fun getInventoryForStore(storeId: String): Flow<List<Inventory>>

    @Query("SELECT * FROM inventory WHERE itemId = :itemId AND storeId = :storeId LIMIT 1")
    suspend fun getInventory(itemId: String, storeId: String): Inventory?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInventory(inventory: Inventory)

    @Query("UPDATE inventory SET quantity = CASE WHEN quantity >= :amount THEN quantity - :amount ELSE 0 END WHERE itemId = :itemId AND storeId = :storeId")
    suspend fun subtractInventoryQuantity(itemId: String, storeId: String, amount: Int)

    @Query("UPDATE inventory SET quantity = quantity + :amount WHERE itemId = :itemId AND storeId = :storeId")
    suspend fun addInventoryQuantity(itemId: String, storeId: String, amount: Int)

    // ---- Transactions ----
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun getTransactions(): Flow<List<TransactionLog>>

    @Query("SELECT * FROM transactions WHERE itemId = :itemCode ORDER BY timestamp DESC")
    fun getTransactionsByItemCode(itemCode: String): Flow<List<TransactionLog>>

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    suspend fun getAllTransactions(): List<TransactionLog>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: TransactionLog)

    @Query("SELECT * FROM transactions WHERE syncStatus = :status")
    suspend fun getTransactionsBySyncStatus(status: SyncStatus): List<TransactionLog>

    @Query("SELECT COUNT(*) FROM transactions WHERE syncStatus = :status")
    fun getTransactionsCountBySyncStatus(status: SyncStatus): Flow<Int>

    @Update
    suspend fun updateTransactions(transactions: List<TransactionLog>)

    // ---- Overdue Checked-Out Items (14+ days past expected_return_date) ----
    @Query("""
        SELECT * FROM transactions 
        WHERE (type = 'OUT' OR type = 'CHECK_OUT') 
          AND status != 'Missing' 
          AND status != 'Returned' 
          AND expected_return_date > 0 
          AND :currentTimestamp >= (expected_return_date + 1209600000)
        ORDER BY expected_return_date ASC
    """)
    suspend fun getOverdueCheckedOutItems(currentTimestamp: Long): List<TransactionLog>

    @Query("UPDATE transactions SET status = :newStatus WHERE id = :txId")
    suspend fun updateTransactionStatus(txId: String, newStatus: String)

    // ---- Active Checked-Out Quantity for User and Item ----
    @Query("""
        SELECT COALESCE(
            (SELECT SUM(ABS(quantityChange)) FROM transactions 
             WHERE (status = 'Checked Out' OR type = 'OUT' OR type = 'CHECK_OUT') 
               AND (userId = :userId OR scanned_by = :userId OR LOWER(userId) = LOWER(:userId) OR LOWER(scanned_by) = LOWER(:userId)) 
               AND (itemId = :itemCode OR itemId = :altCode OR LOWER(itemId) = LOWER(:itemCode) OR LOWER(itemId) = LOWER(:altCode))
               AND status != 'Returned' AND status != 'Missing' AND status != 'Disposed'), 0
        ) - COALESCE(
            (SELECT SUM(ABS(quantityChange)) FROM transactions 
             WHERE (type = 'IN' OR type = 'CHECK_IN' OR type = 'RETURN') 
               AND (userId = :userId OR scanned_by = :userId OR LOWER(userId) = LOWER(:userId) OR LOWER(scanned_by) = LOWER(:userId)) 
               AND (itemId = :itemCode OR itemId = :altCode OR LOWER(itemId) = LOWER(:itemCode) OR LOWER(itemId) = LOWER(:altCode))), 0
        )
    """)
    suspend fun getActiveCheckedOutQuantity(itemCode: String, altCode: String, userId: String): Int

    @Query("""
        SELECT COALESCE(SUM(ABS(quantityChange)), 0) FROM transactions 
        WHERE status = 'Checked Out' 
          AND (userId = :userId OR scanned_by = :userId OR LOWER(userId) = LOWER(:userId) OR LOWER(scanned_by) = LOWER(:userId)) 
          AND (itemId = :itemCode OR itemId = :altCode OR LOWER(itemId) = LOWER(:itemCode) OR LOWER(itemId) = LOWER(:altCode))
    """)
    suspend fun getCheckedOutQuantityByStatus(itemCode: String, altCode: String, userId: String): Int

    @Query("""
        UPDATE transactions 
        SET status = 'Returned' 
        WHERE (type = 'OUT' OR type = 'CHECK_OUT' OR status = 'Checked Out') 
          AND (userId = :userId OR scanned_by = :userId OR LOWER(userId) = LOWER(:userId) OR LOWER(scanned_by) = LOWER(:userId)) 
          AND (itemId = :itemCode OR itemId = :altCode OR LOWER(itemId) = LOWER(:itemCode) OR LOWER(itemId) = LOWER(:altCode)) 
          AND status != 'Returned'
    """)
    suspend fun markActiveCheckoutsReturned(itemCode: String, altCode: String, userId: String)

    // ---- Active Checkouts For User (Profile Screen) ----
    @Query("""
        SELECT 
            t.id AS id,
            t.itemId AS itemId,
            COALESCE(i.[Item Name], t.itemId) AS itemName,
            ABS(t.quantityChange) AS quantity,
            t.expected_return_date AS expected_return_date,
            t.status AS status,
            t.timestamp AS timestamp
        FROM transactions t
        LEFT JOIN items i ON (t.itemId = i.[Item Code] OR LOWER(t.itemId) = LOWER(i.[Item Code]))
        WHERE (t.scanned_by = :currentUser OR t.userId = :currentUser OR LOWER(t.scanned_by) = LOWER(:currentUser) OR LOWER(t.userId) = LOWER(:currentUser))
          AND (t.status = 'Checked Out' OR t.status = 'Overdue' OR (t.type = 'OUT' AND t.status != 'Returned' AND t.status != 'Missing' AND t.status != 'Disposed'))
        ORDER BY t.timestamp DESC
    """)
    fun getActiveCheckoutsForUser(currentUser: String): Flow<List<ActiveCheckoutItem>>

    // ---- Active Exceptions For Global Issue Tracker ----
    @Query("""
        SELECT 
            t.id AS id,
            t.itemId AS itemId,
            COALESCE(i.[Item Name], t.itemId) AS itemName,
            ABS(t.quantityChange) AS quantity,
            t.status AS status,
            t.timestamp AS timestamp,
            COALESCE(NULLIF(t.scanned_by, ''), t.userId, '') AS scannedBy,
            COALESCE(t.transaction_reason, '') AS reason,
            COALESCE(t.storeId, '') AS storeId,
            t.is_resolved AS is_resolved
        FROM transactions t
        LEFT JOIN items i ON (t.itemId = i.[Item Code] OR LOWER(t.itemId) = LOWER(i.[Item Code]))
        WHERE (t.status IN ('Missing and Lost', 'Damaged and Under Maintenance', 'Missing', 'Damaged')
               OR t.status LIKE '%Missing%' OR t.status LIKE '%Damaged%')
          AND t.is_resolved = 0
        ORDER BY t.timestamp DESC
    """)
    fun getActiveExceptions(): Flow<List<ExceptionItem>>

    @Query("UPDATE transactions SET is_resolved = 1 WHERE id = :transactionId")
    suspend fun markTransactionResolved(transactionId: String)

    // ---- Offline-First Pending Sync Queries ----
    @Query("SELECT * FROM transactions WHERE is_pending_sync = 1")
    suspend fun getPendingSyncTransactions(): List<TransactionLog>

    @Query("UPDATE transactions SET is_pending_sync = 0 WHERE id = :id")
    suspend fun markTransactionSynced(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStockHistory(record: StockHistory)

    @Query("SELECT * FROM stock_history WHERE is_pending_sync = 1")
    suspend fun getPendingSyncStockHistory(): List<StockHistory>

    @Query("UPDATE stock_history SET is_pending_sync = 0 WHERE id = :id")
    suspend fun markStockHistorySynced(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInventoryMaster(item: InventoryMaster)

    @Query("SELECT * FROM inventory_master WHERE is_pending_sync = 1")
    suspend fun getPendingSyncInventoryMaster(): List<InventoryMaster>

    @Query("UPDATE inventory_master SET is_pending_sync = 0 WHERE [Item Code] = :itemCode")
    suspend fun markInventoryMasterSynced(itemCode: String)
}
