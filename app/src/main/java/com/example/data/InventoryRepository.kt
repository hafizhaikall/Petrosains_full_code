package com.example.data

import android.content.Context
import android.net.Uri
import com.example.sync.CloudStockResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

class InventoryRepository(
    private val dao: InventoryDao,
    val sessionManager: SessionManager,
    val assetDb: AssetDbHelper,          // Direct access to inventory.db
    var syncManager: com.example.sync.SyncManager? = null
) {
    // ---- Auth ----
    suspend fun login(username: String, password: String): User? {
        val u = username.trim()
        val p = password.trim()
        if (u.isEmpty() || p.isEmpty()) return null

        // 1. Check Room DAO with credentials query
        val roomUser = dao.getUserByCredentials(u, p)
        if (roomUser != null) {
            sessionManager.saveSession(roomUser.id, roomUser.username)
            return roomUser
        }

        // 2. Check assetDb users table in inventory.db
        val isValid = assetDb.authenticateUser(u, p)
        if (isValid) {
            val user = User(id = UUID.randomUUID().toString(), username = u, name = u.replaceFirstChar { it.uppercase() }, password = p)
            dao.insertUser(user)
            sessionManager.saveSession(user.id, user.username)
            return user
        }

        // 3. Check Firebase Firestore 'users' collection
        val isCloudValid = syncManager?.authenticateCloudUser(u, p) ?: false
        if (isCloudValid) {
            val user = User(id = UUID.randomUUID().toString(), username = u, name = u.replaceFirstChar { it.uppercase() }, password = p)
            dao.insertUser(user)
            sessionManager.saveSession(user.id, user.username)
            return user
        }

        // Invalid credentials - do NOT bypass or auto-login
        return null
    }

    suspend fun registerUser(username: String, name: String) {
        dao.insertUser(User(id = UUID.randomUUID().toString(), username = username, name = name))
    }

    // ---- Stores & Database Seeding ----
    suspend fun initializeStores() {
        val storeNames = assetDb.getStoreNames()
        if (storeNames.isNotEmpty()) {
            dao.insertStores(storeNames.map { name ->
                Store(id = name, name = name.lowercase().split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }, isOfflineFirst = false)
            })
        } else {
            // Fallback if db is empty
            dao.insertStores(listOf(
                Store("STORE 1",       "Store 1",       isOfflineFirst = false),
                Store("MAKER STUDIO",  "Maker Studio",  isOfflineFirst = false),
                Store("CHEMICAL ROOM", "Chemical Room", isOfflineFirst = true),
                Store("CHILLAX",       "Chillax",       isOfflineFirst = true)
            ))
        }

        // Seed all inventory items into Room items table so Room queries and flows contain all data
        val allItems = assetDb.getAllItems()
        if (allItems.isNotEmpty()) {
            dao.insertItems(allItems.map { inv ->
                Item(
                    id = inv.item_code,
                    name = inv.item_name,
                    category = inv.category,
                    description = inv.specification,
                    assetType = inv.asset_type,
                    specificLocation = inv.specific_location,
                    storageLocation = inv.storage_location
                )
            })
        }

        // Seed default admin user into Room if not present
        val adminUser = dao.getUserByCredentials("admin", "admin123")
        if (adminUser == null) {
            dao.insertUser(User(id = "1", username = "admin", name = "Admin", password = "admin123"))
        }
    }

    fun getStores(): Flow<List<Store>> = dao.getStores()

    // ---- Inventory items (from asset db) ----
    suspend fun getAllInventoryItems(): List<InventoryItem> = assetDb.getAllItems()
    suspend fun getInventoryItemsByStore(storeId: String): List<InventoryItem> = assetDb.getItemsByStore(storeId)
    suspend fun searchInventoryItems(query: String): List<InventoryItem> = assetDb.searchItems(query)
    suspend fun getInventoryItemByCode(itemCode: String): InventoryItem? = assetDb.getItemByCode(itemCode)

    suspend fun getItemDetails(itemCode: String): InventoryItem? {
        val trimmed = itemCode.trim()
        if (trimmed.isEmpty()) return null
        val dbItem = assetDb.getItemByCode(trimmed)
        if (dbItem != null) return dbItem

        val roomItem = dao.getItemByCode(trimmed) ?: dao.getItemByCodeIgnoreCase(trimmed)
        if (roomItem != null) {
            return InventoryItem(
                item_code = roomItem.id,
                item_name = roomItem.name,
                category = roomItem.category,
                asset_type = roomItem.assetType,
                storage_location = roomItem.storageLocation,
                specific_location = roomItem.specificLocation,
                specification = roomItem.description,
                available_quantity = 1
            )
        }
        return null
    }

    fun getItemsByStoreRoom(selectedStoreRoom: String): Flow<List<Item>> =
        dao.getItemsByStoreRoom(selectedStoreRoom)

    fun searchItemsByStoreRoom(selectedStoreRoom: String, query: String): Flow<List<Item>> =
        dao.searchItemsByStoreRoom(selectedStoreRoom, query)

    /**
     * When an "Item Code" is scanned, queries the database to retrieve:
     * - Item Name
     * - Asset Type
     * - Specific Location / Rack
     * Also saves/caches the item in Room DAO.
     */
    suspend fun scanItemByCode(itemCode: String): ScannedItemResult? {
        val trimmed = itemCode.trim()
        if (trimmed.isEmpty()) return null

        // 1. Primary lookup directly in inventory_master table in inventory.db
        val scanned = assetDb.getScannedItemByCode(trimmed)
        if (scanned != null) {
            dao.insertItem(
                Item(
                    id = scanned.itemCode,
                    name = scanned.itemName,
                    category = scanned.category,
                    description = "",
                    assetType = scanned.assetType,
                    specificLocation = scanned.specificLocation,
                    storageLocation = scanned.storageLocation
                )
            )
            return scanned
        }

        // 2. Fallback to Room DAO
        val roomItem = dao.getItemByCode(trimmed) ?: dao.getItemByCodeIgnoreCase(trimmed)
        if (roomItem != null) {
            return ScannedItemResult(
                itemCode = roomItem.id,
                itemName = roomItem.name,
                assetType = roomItem.assetType,
                specificLocation = roomItem.specificLocation,
                storageLocation = roomItem.storageLocation,
                category = roomItem.category
            )
        }

        return null
    }

    // ---- Legacy flows (for AI scan / transactions) ----
    fun getItems(): Flow<List<Item>> = dao.getItems()
    fun searchItems(query: String): Flow<List<Item>> = dao.searchItems(query)
    fun getInventoryForStore(storeId: String): Flow<List<Inventory>> = dao.getInventoryForStore(storeId)
    fun getTransactions(): Flow<List<TransactionLog>> = dao.getTransactions()
    fun getPendingSyncCount(): Flow<Int> = dao.getTransactionsCountBySyncStatus(SyncStatus.PENDING)

    // ---- Manual Entry with Item Code Validation and Strict Location Check ----
    suspend fun manualEntry(
        itemCodeOrName: String,
        quantity: Int,
        type: TransactionType,
        storeId: String,
        userId: String = "",
        status: String = "",
        reason: String = ""
    ): TransactionOperationResult {
        val trimmed = itemCodeOrName.trim()
        if (trimmed.isEmpty()) {
            return TransactionOperationResult(EntryResult.ITEM_NOT_FOUND, message = "Item Not Found")
        }

        // First query database for entered Item Code or Name
        val invItem = assetDb.getItemByCode(trimmed)
            ?: assetDb.findByName(trimmed)
            ?: run {
                val roomItem = dao.getItemByCode(trimmed) ?: dao.getItemByName(trimmed)
                if (roomItem != null) assetDb.findByName(roomItem.name) else null
            }

        // If it does not exist, block entry and return ITEM_NOT_FOUND
        if (invItem == null) {
            return TransactionOperationResult(EntryResult.ITEM_NOT_FOUND, message = "Item Not Found")
        }

        val officialStore = invItem.storage_location.trim()
        val currentStore = storeId.trim()

        // Strict Location Validation: Compare user's currently selected location against official Storage Location in inventory_master
        if (officialStore.isNotEmpty() && currentStore.isNotEmpty() && !officialStore.equals(currentStore, ignoreCase = true)) {
            val errorMsg = "Please go to $officialStore.You enter the wrong store for this items"
            return TransactionOperationResult(
                status = EntryResult.WRONG_STORE,
                correctStore = officialStore,
                message = errorMsg
            )
        }

        val targetStore = officialStore.ifEmpty { currentStore.ifEmpty { "CHILLAX" } }
        val user = if (userId.isNotBlank()) userId else "admin"

        val resultingStatus = if (status.isNotBlank()) status else invItem.status.ifEmpty { "Active" }
        if (status.isNotBlank()) {
            assetDb.updateStatus(invItem.item_code, status)
        }

        val isReduction = type in listOf(
            TransactionType.OUT,
            TransactionType.CHECK_OUT,
            TransactionType.LOST,
            TransactionType.DAMAGE,
            TransactionType.TRANSFER
        )
        val isAddition = type in listOf(
            TransactionType.IN,
            TransactionType.CHECK_IN,
            TransactionType.RETURN
        )

        if (isReduction) {
            if (invItem.available_quantity < quantity) {
                return TransactionOperationResult(EntryResult.INSUFFICIENT_STOCK, message = "Insufficient inventory")
            }
            val newQty = (invItem.available_quantity - quantity).coerceAtLeast(0)
            assetDb.updateQuantity(invItem.item_code, newQty)
            dao.subtractInventoryQuantity(invItem.item_code, targetStore, quantity)
            recordTransaction(invItem.item_name, invItem.item_code, quantity, targetStore, user, EntryMethod.MANUAL, type, resultingStatus, reason)
        } else if (isAddition) {
            val newQty = invItem.available_quantity + quantity
            assetDb.updateQuantity(invItem.item_code, newQty)
            dao.addInventoryQuantity(invItem.item_code, targetStore, quantity)
            recordTransaction(invItem.item_name, invItem.item_code, quantity, targetStore, user, EntryMethod.MANUAL, type, resultingStatus, reason)
        } else {
            // ADJUSTMENT or other
            if (quantity != 0) {
                val newQty = (invItem.available_quantity + quantity).coerceAtLeast(0)
                assetDb.updateQuantity(invItem.item_code, newQty)
                if (quantity > 0) {
                    dao.addInventoryQuantity(invItem.item_code, targetStore, quantity)
                } else {
                    dao.subtractInventoryQuantity(invItem.item_code, targetStore, -quantity)
                }
            }
            recordTransaction(invItem.item_name, invItem.item_code, quantity, targetStore, user, EntryMethod.MANUAL, type, resultingStatus, reason)
        }
        return TransactionOperationResult(EntryResult.SUCCESS)
    }

    // ---- Check-in / Check-out with Strict Location Check ----
    suspend fun checkInItem(itemNameOrCode: String, quantity: Int, storeId: String, userId: String, method: EntryMethod, reason: String = ""): TransactionOperationResult {
        val trimmed = itemNameOrCode.trim()
        val invItem = assetDb.getItemByCode(trimmed)
            ?: assetDb.findByName(trimmed)
            ?: run {
                val roomItem = dao.getItemByCode(trimmed) ?: dao.getItemByName(trimmed)
                if (roomItem != null) assetDb.findByName(roomItem.name) else null
            }

        if (invItem == null) {
            return TransactionOperationResult(EntryResult.ITEM_NOT_FOUND, message = "Item Not Found")
        }

        val officialStore = invItem.storage_location.trim()
        val currentStore = storeId.trim()

        // Strict Location Validation
        if (officialStore.isNotEmpty() && currentStore.isNotEmpty() && !officialStore.equals(currentStore, ignoreCase = true)) {
            val errorMsg = "Please go to $officialStore.You enter the wrong store for this items"
            return TransactionOperationResult(
                status = EntryResult.WRONG_STORE,
                correctStore = officialStore,
                message = errorMsg
            )
        }

        val targetStore = officialStore.ifEmpty { currentStore.ifEmpty { "CHILLAX" } }
        val user = if (userId.isNotBlank()) userId else "admin"

        val newQty = invItem.available_quantity + quantity
        val resultingStatus = invItem.status.ifEmpty { "Active" }
        assetDb.updateQuantity(invItem.item_code, newQty)
        dao.addInventoryQuantity(invItem.item_code, targetStore, quantity)
        recordTransaction(invItem.item_name, invItem.item_code, quantity, targetStore, user, method, TransactionType.IN, resultingStatus, reason)
        dao.markActiveCheckoutsReturned(invItem.item_code, invItem.item_name, user)
        return TransactionOperationResult(EntryResult.SUCCESS)
    }

    suspend fun getActiveCheckedOutQuantity(itemNameOrCode: String, userId: String): Int {
        val trimmed = itemNameOrCode.trim()
        if (trimmed.isEmpty()) return 0
        val invItem = assetDb.getItemByCode(trimmed) ?: assetDb.findByName(trimmed)
        val code = invItem?.item_code ?: trimmed
        val alt = invItem?.item_name ?: trimmed
        val effectiveUser = if (userId.isNotBlank()) userId else "admin"
        val netQty = dao.getActiveCheckedOutQuantity(code, alt, effectiveUser)
        if (netQty > 0) return netQty
        return dao.getCheckedOutQuantityByStatus(code, alt, effectiveUser).coerceAtLeast(0)
    }

    /**
     * Executes a partial return:
     * - Adds returned quantity to available stock and logs status 'Available'
     * - Logs separate audit records in stock_history/transactions for damaged, missing, and disposed quantities
     * - Disposed items write off master inventory permanently via disposeQuantity
     */
    suspend fun executePartialReturn(
        itemNameOrCode: String,
        returnedQuantity: Int,
        damagedQuantity: Int = 0,
        missingQuantity: Int = 0,
        disposedQuantity: Int = 0,
        storeId: String,
        userId: String,
        method: EntryMethod,
        reason: String = ""
    ): TransactionOperationResult {
        val trimmed = itemNameOrCode.trim()
        val invItem = assetDb.getItemByCode(trimmed)
            ?: assetDb.findByName(trimmed)
            ?: run {
                val roomItem = dao.getItemByCode(trimmed) ?: dao.getItemByName(trimmed)
                if (roomItem != null) assetDb.findByName(roomItem.name) else null
            }

        if (invItem == null) {
            return TransactionOperationResult(EntryResult.ITEM_NOT_FOUND, message = "Item Not Found")
        }

        val officialStore = invItem.storage_location.trim()
        val currentStore = storeId.trim()

        if (officialStore.isNotEmpty() && currentStore.isNotEmpty() && !officialStore.equals(currentStore, ignoreCase = true)) {
            val errorMsg = "Please go to $officialStore.You enter the wrong store for this items"
            return TransactionOperationResult(
                status = EntryResult.WRONG_STORE,
                correctStore = officialStore,
                message = errorMsg
            )
        }

        val targetStore = officialStore.ifEmpty { currentStore.ifEmpty { "CHILLAX" } }
        val user = if (userId.isNotBlank()) userId else "admin"

        // 1. Returned quantity -> Available
        if (returnedQuantity > 0) {
            val newQty = invItem.available_quantity + returnedQuantity
            assetDb.updateQuantity(invItem.item_code, newQty)
            dao.addInventoryQuantity(invItem.item_code, targetStore, returnedQuantity)
            recordTransaction(
                itemName = invItem.item_name,
                itemCode = invItem.item_code,
                quantity = returnedQuantity,
                storeId = targetStore,
                userId = user,
                method = method,
                type = TransactionType.IN,
                status = "Available",
                reason = if (reason.isNotBlank()) reason else "Returned item"
            )
        }

        // 2. Damaged quantity -> Log exception (is_resolved = 0)
        if (damagedQuantity > 0) {
            assetDb.updateStatus(invItem.item_code, "Damaged and Under Maintenance")
            recordTransaction(
                itemName = invItem.item_name,
                itemCode = invItem.item_code,
                quantity = damagedQuantity,
                storeId = targetStore,
                userId = user,
                method = method,
                type = TransactionType.DAMAGE,
                status = "Damaged and Under Maintenance",
                reason = "Partial return: Damaged & Under Maintenance"
            )
        }

        // 3. Missing quantity -> Log exception (is_resolved = 0)
        if (missingQuantity > 0) {
            assetDb.updateStatus(invItem.item_code, "Missing and Lost")
            recordTransaction(
                itemName = invItem.item_name,
                itemCode = invItem.item_code,
                quantity = missingQuantity,
                storeId = targetStore,
                userId = user,
                method = method,
                type = TransactionType.LOST,
                status = "Missing and Lost",
                reason = "Partial return: Missing & Lost"
            )
        }

        // 4. Disposed quantity -> Write off total inventory & log transaction
        if (disposedQuantity > 0) {
            assetDb.disposeQuantity(invItem.item_code, disposedQuantity)
            assetDb.updateStatus(invItem.item_code, "Disposed")
            recordTransaction(
                itemName = invItem.item_name,
                itemCode = invItem.item_code,
                quantity = disposedQuantity,
                storeId = targetStore,
                userId = user,
                method = method,
                type = TransactionType.LOST,
                status = "Disposed",
                reason = "Partial return: Disposed (Write-off)"
            )
        }

        dao.markActiveCheckoutsReturned(invItem.item_code, invItem.item_name, user)

        return TransactionOperationResult(EntryResult.SUCCESS)
    }

    /**
     * Backward-compatible overload for single status partial returns.
     */
    suspend fun executePartialReturn(
        itemNameOrCode: String,
        returnedQuantity: Int,
        unreturnedQuantity: Int,
        unreturnedStatus: String,
        storeId: String,
        userId: String,
        method: EntryMethod,
        reason: String = ""
    ): TransactionOperationResult {
        val isDamaged = unreturnedStatus.contains("Damaged", ignoreCase = true)
        val isDisposed = unreturnedStatus.contains("Disposed", ignoreCase = true)
        val isMissing = !isDamaged && !isDisposed
        return executePartialReturn(
            itemNameOrCode = itemNameOrCode,
            returnedQuantity = returnedQuantity,
            damagedQuantity = if (isDamaged) unreturnedQuantity else 0,
            missingQuantity = if (isMissing) unreturnedQuantity else 0,
            disposedQuantity = if (isDisposed) unreturnedQuantity else 0,
            storeId = storeId,
            userId = userId,
            method = method,
            reason = reason
        )
    }

    suspend fun checkOutItem(
        itemNameOrCode: String,
        quantity: Int,
        storeId: String,
        userId: String,
        method: EntryMethod,
        reason: String = "",
        expectedReturnDate: Long = 0L
    ): TransactionOperationResult {
        val trimmed = itemNameOrCode.trim()
        val invItem = assetDb.getItemByCode(trimmed)
            ?: assetDb.findByName(trimmed)
            ?: run {
                val roomItem = dao.getItemByCode(trimmed) ?: dao.getItemByName(trimmed)
                if (roomItem != null) assetDb.findByName(roomItem.name) else null
            }

        if (invItem == null) {
            return TransactionOperationResult(EntryResult.ITEM_NOT_FOUND, message = "Item Not Found")
        }

        val officialStore = invItem.storage_location.trim()
        val currentStore = storeId.trim()

        // Strict Location Validation
        if (officialStore.isNotEmpty() && currentStore.isNotEmpty() && !officialStore.equals(currentStore, ignoreCase = true)) {
            val errorMsg = "Please go to $officialStore.You enter the wrong store for this items"
            return TransactionOperationResult(
                status = EntryResult.WRONG_STORE,
                correctStore = officialStore,
                message = errorMsg
            )
        }

        val targetStore = officialStore.ifEmpty { currentStore.ifEmpty { "CHILLAX" } }
        val user = if (userId.isNotBlank()) userId else "admin"

        if (invItem.available_quantity < quantity) {
            return TransactionOperationResult(EntryResult.INSUFFICIENT_STOCK, message = "Insufficient inventory")
        }
        val newQty = (invItem.available_quantity - quantity).coerceAtLeast(0)

        val resultingStatus = "Checked Out"
        assetDb.updateQuantity(invItem.item_code, newQty)
        dao.subtractInventoryQuantity(invItem.item_code, targetStore, quantity)
        recordTransaction(
            itemName = invItem.item_name,
            itemCode = invItem.item_code,
            quantity = quantity,
            storeId = targetStore,
            userId = user,
            method = method,
            type = TransactionType.OUT,
            status = resultingStatus,
            reason = reason,
            expectedReturnDate = expectedReturnDate
        )
        return TransactionOperationResult(EntryResult.SUCCESS)
    }

    /**
     * Updates an item's status in inventory_master and logs an audit record in stock_history.
     */
    suspend fun updateItemStatus(
        itemCode: String,
        newStatus: String,
        storeId: String = "",
        userId: String = ""
    ): Boolean {
        val trimmed = itemCode.trim()
        if (trimmed.isEmpty()) return false
        val item = assetDb.getItemByCode(trimmed) ?: return false
        assetDb.updateStatus(trimmed, newStatus)
        val effectiveStore = storeId.ifEmpty { item.storage_location.ifEmpty { "CHILLAX" } }
        val effectiveUser = if (userId.isNotBlank()) userId else "admin"
        // Audit record in stock_history table
        recordTransaction(
            itemName = item.item_name,
            itemCode = item.item_code,
            quantity = 0,
            storeId = effectiveStore,
            userId = effectiveUser,
            method = EntryMethod.MANUAL,
            type = TransactionType.ADJUSTMENT,
            status = newStatus,
            reason = "Status changed to $newStatus"
        )
        syncManager?.pushItemUpdate(item.item_code)
        return true
    }

    private suspend fun recordTransaction(
        itemName: String,
        itemCode: String,
        quantity: Int,
        storeId: String,
        userId: String,
        method: EntryMethod,
        type: TransactionType,
        status: String = "",
        reason: String = "",
        expectedReturnDate: Long = 0L
    ) {
        val resolvedCode = itemCode.ifEmpty {
            dao.getItemByName(itemName)?.id ?: UUID.randomUUID().toString()
        }
        var item = dao.getItemByCode(resolvedCode)
        if (item == null) {
            item = Item(id = resolvedCode, name = itemName)
            dao.insertItem(item)
        }
        val effectiveUser = if (userId.isNotBlank()) userId else "admin"
        val txId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val tx = TransactionLog(
            id = txId,
            itemId = item.id,
            storeId = storeId,
            userId = effectiveUser,
            quantityChange = quantity,
            type = type,
            method = method,
            timestamp = now,
            syncStatus = SyncStatus.PENDING,
            scanned_by = effectiveUser,
            status = status,
            transaction_reason = reason,
            expected_return_date = expectedReturnDate,
            is_pending_sync = true
        )
        dao.insertTransaction(tx)

        // Also record to stock_history table for offline-first Room persistence
        val stockHistory = StockHistory(
            id = txId,
            itemId = item.id,
            storeId = storeId,
            userId = effectiveUser,
            quantityChange = quantity,
            type = type,
            method = method,
            timestamp = now,
            syncStatus = SyncStatus.PENDING,
            scanned_by = effectiveUser,
            status = status,
            transaction_reason = reason,
            expected_return_date = expectedReturnDate,
            is_pending_sync = true
        )
        dao.insertStockHistory(stockHistory)
    }

    suspend fun syncTransactions() {
        val pending = dao.getTransactionsBySyncStatus(SyncStatus.PENDING)
        for (tx in pending) {
            syncManager?.pushTransaction(tx)
        }
        if (pending.isNotEmpty()) {
            dao.updateTransactions(pending.map { it.copy(syncStatus = SyncStatus.SYNCED) })
        }
        // Also push all local transactions to ensure central stock_history has every record
        val allLocalTransactions = dao.getAllTransactions()
        for (tx in allLocalTransactions) {
            syncManager?.pushTransaction(tx)
        }
        // Also push all current items to cloud to ensure full synchronization
        val all = assetDb.getAllItems()
        for (item in all) {
            syncManager?.pushItemUpdate(item.item_code)
        }
        syncManager?.recordSyncSuccess()
    }

    fun getTransactionsByItemCode(itemCode: String): Flow<List<TransactionLog>> =
        dao.getTransactionsByItemCode(itemCode)

    suspend fun getAllTransactions(): List<TransactionLog> = dao.getAllTransactions()

    fun getActiveCheckoutsForUser(currentUser: String): Flow<List<ActiveCheckoutItem>> =
        dao.getActiveCheckoutsForUser(currentUser)

    // ---- Global Issue Tracker ----
    fun getActiveExceptions(): Flow<List<ExceptionItem>> =
        dao.getActiveExceptions()

    suspend fun resolveExceptionItem(
        exception: ExceptionItem,
        resolutionStatus: String,
        resolutionReason: String,
        resolvedBy: String
    ): TransactionOperationResult = withContext(Dispatchers.IO) {
        val effectiveUser = resolvedBy.ifBlank { "admin" }
        val targetStore = exception.storeId.ifEmpty { "CHILLAX" }
        val isReturnToInventory = resolutionStatus.contains("Found", ignoreCase = true) ||
                resolutionStatus.contains("Fixed", ignoreCase = true)
        val isDisposed = resolutionStatus.contains("Disposed", ignoreCase = true)

        if (isReturnToInventory) {
            assetDb.addQuantity(exception.itemId, exception.quantity)
            dao.addInventoryQuantity(exception.itemId, targetStore, exception.quantity)
            assetDb.updateStatus(exception.itemId, "Active")
            val reasonMsg = buildString {
                append("Issue resolved by $effectiveUser: ${exception.status} -> $resolutionStatus")
                if (resolutionReason.isNotBlank()) append(" ($resolutionReason)")
            }
            recordTransaction(
                itemName = exception.itemName,
                itemCode = exception.itemId,
                quantity = exception.quantity,
                storeId = targetStore,
                userId = effectiveUser,
                method = EntryMethod.MANUAL,
                type = TransactionType.IN,
                status = resolutionStatus,
                reason = reasonMsg
            )
        } else if (isDisposed) {
            assetDb.disposeQuantity(exception.itemId, exception.quantity)
            assetDb.updateStatus(exception.itemId, "Disposed")
            val reasonMsg = buildString {
                append("Issue written off by $effectiveUser: ${exception.status} -> Disposed (Write-off)")
                if (resolutionReason.isNotBlank()) append(" ($resolutionReason)")
            }
            recordTransaction(
                itemName = exception.itemName,
                itemCode = exception.itemId,
                quantity = exception.quantity,
                storeId = targetStore,
                userId = effectiveUser,
                method = EntryMethod.MANUAL,
                type = TransactionType.LOST,
                status = "Disposed (Write-off)",
                reason = reasonMsg
            )
        }

        // Mark original exception transaction resolved so it exits the active exceptions list
        dao.markTransactionResolved(exception.id)
        syncManager?.pushItemUpdate(exception.itemId)

        TransactionOperationResult(EntryResult.SUCCESS)
    }

    suspend fun exportTransactionHistoryCsv(
        context: Context,
        storeId: String? = null
    ): com.example.util.CsvExportResult = withContext(Dispatchers.IO) {
        val allTx = dao.getAllTransactions()
        val filtered = if (!storeId.isNullOrBlank() && !storeId.equals("All Stores", ignoreCase = true)) {
            allTx.filter { it.storeId.equals(storeId, ignoreCase = true) }
        } else {
            allTx
        }
        val storeLabel = if (!storeId.isNullOrBlank()) storeId else "All_Stores"
        com.example.util.CsvExporter.exportHistoryReport(context, filtered, storeLabel)
    }
}
