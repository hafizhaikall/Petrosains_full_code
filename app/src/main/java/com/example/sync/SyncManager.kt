package com.example.sync

import android.content.Context
import android.util.Log
import com.example.data.AssetDbHelper
import com.example.data.EntryMethod
import com.example.data.InventoryDao
import com.example.data.InventoryItem
import com.example.data.Item
import com.example.data.SessionManager
import com.example.data.SyncStatus
import com.example.data.TransactionLog
import com.example.data.TransactionType
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine

sealed class CloudStockResult {
    data class Success(val newQuantity: Int) : CloudStockResult()
    data class InsufficientStock(val available: Int) : CloudStockResult()
    data class Error(val message: String) : CloudStockResult()
    object Offline : CloudStockResult()
}

/**
 * SyncManager manages real-time two-way synchronization between the local SQLite
 * database (inventory_master in inventory.db and Room DAO) and Firebase Cloud Firestore.
 *
 * Conflict Resolution:
 * Uses Last-Write-Wins (LWW) based on [InventoryItem.last_updated] millisecond timestamp.
 *
 * Resilience:
 * If Firebase is not yet initialized (e.g. google-services.json not yet placed),
 * SyncManager gracefully operates in local-only mode without crashing.
 */
class SyncManager(
    private val context: Context,
    private val assetDb: AssetDbHelper,
    private val dao: InventoryDao,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    companion object {
        private const val TAG = "SyncManager"
        const val COLLECTION_INVENTORY = "inventory_master"
        const val COLLECTION_TRANSACTIONS = "transactions"
        const val COLLECTION_STOCK_HISTORY = "stock_history"
    }

    private var firestore: FirebaseFirestore? = null
    private var inventoryListenerRegistration: ListenerRegistration? = null
    private var historyListenerRegistration: ListenerRegistration? = null
    private val sessionManager = SessionManager(context)

    private val _isCloudConnected = MutableStateFlow(false)
    val isCloudConnected: StateFlow<Boolean> = _isCloudConnected.asStateFlow()

    private val _lastSyncTime = MutableStateFlow(0L)
    val lastSyncTime: StateFlow<Long> = _lastSyncTime.asStateFlow()

    init {
        initFirebaseIfAvailable()
        scope.launch {
            sessionManager.lastSyncTimeFlow.collect { stored ->
                if (stored > _lastSyncTime.value) {
                    _lastSyncTime.value = stored
                }
            }
        }
    }

    fun recordSyncSuccess(time: Long = System.currentTimeMillis()) {
        _lastSyncTime.value = time
        scope.launch {
            sessionManager.saveLastSyncTime(time)
        }
    }

    private fun initFirebaseIfAvailable() {
        try {
            if (FirebaseApp.getApps(context).isNotEmpty()) {
                firestore = FirebaseFirestore.getInstance()
                _isCloudConnected.value = true
                Log.i(TAG, "Firebase Firestore initialized successfully for cloud synchronization.")
            } else {
                Log.w(TAG, "FirebaseApp is not initialized yet. Waiting for google-services.json configuration.")
                _isCloudConnected.value = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Firebase initialization skipped or failed: ${e.message}")
            _isCloudConnected.value = false
        }
    }

    /**
     * Starts listening for real-time remote updates from Firebase Firestore.
     * When another device modifies inventory items, changes are received instantly,
     * applied to the local SQLite database if newer, and [onRemoteChange] is invoked
     * so the UI refreshes automatically.
     */
    fun startRealtimeSync(onRemoteChange: () -> Unit) {
        if (firestore == null) {
            initFirebaseIfAvailable()
        }

        val db = firestore ?: run {
            Log.d(TAG, "Firestore not available, running in local-only mode.")
            return
        }

        // 1. Listen for inventory_master updates
        if (inventoryListenerRegistration == null) {
            try {
                inventoryListenerRegistration = db.collection(COLLECTION_INVENTORY)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            Log.e(TAG, "Firestore inventory listener error: ${error.message}", error)
                            return@addSnapshotListener
                        }

                        if (snapshot == null || snapshot.isEmpty) return@addSnapshotListener

                        scope.launch {
                            var hasLocalUpdate = false
                            for (dc in snapshot.documentChanges) {
                                if (dc.type == DocumentChange.Type.ADDED || dc.type == DocumentChange.Type.MODIFIED) {
                                    val doc = dc.document
                                    val remoteItem = docToInventoryItem(doc.data)
                                    if (remoteItem != null) {
                                        val updated = assetDb.updateItemFromRemote(remoteItem)
                                        if (updated) {
                                            hasLocalUpdate = true
                                        }
                                    }
                                }
                            }

                            if (hasLocalUpdate) {
                                recordSyncSuccess()
                                onRemoteChange()
                            }
                        }
                    }
                Log.i(TAG, "Real-time snapshot listener attached to '$COLLECTION_INVENTORY'.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to attach Firestore inventory listener: ${e.message}", e)
            }
        }

        // 2. Listen for stock_history transaction updates
        if (historyListenerRegistration == null) {
            try {
                historyListenerRegistration = db.collection(COLLECTION_STOCK_HISTORY)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            Log.e(TAG, "Firestore stock_history listener error: ${error.message}", error)
                            return@addSnapshotListener
                        }

                        if (snapshot == null || snapshot.isEmpty) return@addSnapshotListener

                        scope.launch {
                            var hasLocalUpdate = false
                            for (dc in snapshot.documentChanges) {
                                if (dc.type == DocumentChange.Type.ADDED || dc.type == DocumentChange.Type.MODIFIED) {
                                    val doc = dc.document
                                    val remoteTx = docToTransactionLog(doc.id, doc.data)
                                    if (remoteTx != null) {
                                        dao.insertTransaction(remoteTx)
                                        val itemName = doc.getString("itemName")
                                        if (!itemName.isNullOrBlank() && dao.getItemByCode(remoteTx.itemId) == null) {
                                            dao.insertItem(Item(id = remoteTx.itemId, name = itemName))
                                        }
                                        hasLocalUpdate = true
                                    }
                                }
                            }

                            if (hasLocalUpdate) {
                                recordSyncSuccess()
                                onRemoteChange()
                            }
                        }
                    }
                Log.i(TAG, "Real-time snapshot listener attached to '$COLLECTION_STOCK_HISTORY'.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to attach Firestore stock_history listener: ${e.message}", e)
            }
        }
    }

    /**
     * Detaches real-time listeners to prevent leaks when app stops.
     */
    fun stopRealtimeSync() {
        inventoryListenerRegistration?.remove()
        inventoryListenerRegistration = null
        historyListenerRegistration?.remove()
        historyListenerRegistration = null
    }

    /**
     * Atomically adjusts stock directly on Firebase Cloud Firestore.
     * Uses Firestore runTransaction to prevent race conditions when multiple devices
     * check out the same item simultaneously.
     *
     * @param itemCode The unique item identifier
     * @param quantityDelta Negative for check-out (e.g. -2), positive for check-in (e.g. +2)
     * @return [CloudStockResult] indicating success with new live quantity, insufficient stock, or offline
     */
    suspend fun executeCloudStockAdjustment(
        itemCode: String,
        quantityDelta: Int
    ): CloudStockResult = suspendCancellableCoroutine { continuation ->
        val db = firestore
        if (db == null) {
            continuation.resume(CloudStockResult.Offline)
            return@suspendCancellableCoroutine
        }

        val cleanCode = itemCode.trim()
        val docRef = db.collection(COLLECTION_INVENTORY).document(cleanCode)

        db.runTransaction { transaction ->
            val snapshot = transaction.get(docRef)
            val currentAvail: Int

            if (snapshot.exists()) {
                currentAvail = (snapshot.getLong("available_quantity") ?: 0L).toInt()
            } else {
                // Seed from local SQLite if item isn't created in cloud yet
                val localItem = runBlocking { assetDb.getItemByCode(cleanCode) }
                if (localItem != null) {
                    currentAvail = localItem.available_quantity
                    val seedMap = inventoryItemToMap(localItem)
                    transaction.set(docRef, seedMap)
                } else {
                    currentAvail = 0
                }
            }

            val newAvail = currentAvail + quantityDelta
            if (newAvail < 0) {
                throw IllegalStateException("INSUFFICIENT_STOCK:$currentAvail")
            }

            val now = System.currentTimeMillis()
            transaction.update(
                docRef,
                mapOf(
                    "available_quantity" to newAvail,
                    "last_updated" to now
                )
            )
            newAvail
        }.addOnSuccessListener { newQuantity ->
            Log.d(TAG, "Atomic cloud transaction succeeded for $cleanCode: new qty = $newQuantity")
            recordSyncSuccess()
            if (continuation.isActive) {
                continuation.resume(CloudStockResult.Success(newQuantity))
            }
        }.addOnFailureListener { e ->
            Log.e(TAG, "Atomic cloud transaction failed for $cleanCode: ${e.message}")
            val msg = e.message ?: ""
            if (continuation.isActive) {
                if (msg.contains("INSUFFICIENT_STOCK")) {
                    val available = msg.substringAfter("INSUFFICIENT_STOCK:").toIntOrNull() ?: 0
                    continuation.resume(CloudStockResult.InsufficientStock(available))
                } else {
                    continuation.resume(CloudStockResult.Error(msg))
                }
            }
        }
    }

    /**
     * Authenticates a user against the cloud Firebase Firestore 'users' collection.
     * Document ID = username, field = "password".
     */
    suspend fun authenticateCloudUser(username: String, password: String): Boolean = suspendCancellableCoroutine { continuation ->
        val db = firestore
        if (db == null) {
            continuation.resume(false)
            return@suspendCancellableCoroutine
        }
        val cleanU = username.trim()
        db.collection("users").document(cleanU).get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    val pass = doc.getString("password") ?: ""
                    continuation.resume(pass == password.trim())
                } else {
                    continuation.resume(false)
                }
            }
            .addOnFailureListener {
                continuation.resume(false)
            }
    }

    /**
     * Pushes a locally updated inventory item to the cloud.
     */
    fun pushItemUpdate(itemCode: String) {
        val db = firestore ?: return
        scope.launch {
            try {
                val item = assetDb.getItemByCode(itemCode) ?: return@launch
                val map = inventoryItemToMap(item)
                db.collection(COLLECTION_INVENTORY)
                    .document(item.item_code.trim())
                    .set(map, SetOptions.merge())
                    .addOnSuccessListener {
                        Log.d(TAG, "Cloud sync: successfully pushed item ${item.item_code}")
                        recordSyncSuccess()
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Cloud sync: failed to push item ${item.item_code}: ${e.message}")
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Exception pushing item to cloud: ${e.message}")
            }
        }
    }

    /**
     * Pushes a newly recorded transaction log to the cloud stock_history collection.
     */
    fun pushTransaction(transaction: TransactionLog, itemName: String? = null) {
        val db = firestore ?: return
        scope.launch {
            try {
                val scannedBy = transaction.scanned_by.ifBlank { transaction.userId }
                val resolvedName = itemName ?: run {
                    assetDb.getItemByCode(transaction.itemId)?.item_name
                        ?: dao.getItemByCode(transaction.itemId)?.name
                        ?: ""
                }
                val map = mapOf(
                    "id" to transaction.id,
                    "itemId" to transaction.itemId,
                    "item_code" to transaction.itemId,
                    "itemName" to resolvedName,
                    "storeId" to transaction.storeId,
                    "userId" to transaction.userId,
                    "quantityChange" to transaction.quantityChange,
                    "type" to transaction.type.name,
                    "method" to transaction.method.name,
                    "timestamp" to transaction.timestamp,
                    "scanned_by" to scannedBy,
                    "status" to transaction.status,
                    "transaction_reason" to transaction.transaction_reason,
                    "expected_return_date" to transaction.expected_return_date
                )

                // Push to central stock_history collection
                db.collection(COLLECTION_STOCK_HISTORY)
                    .document(transaction.id)
                    .set(map, SetOptions.merge())
                    .addOnSuccessListener {
                        Log.d(TAG, "Cloud sync: recorded transaction ${transaction.id} in '$COLLECTION_STOCK_HISTORY'")
                        recordSyncSuccess()
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Cloud sync: failed stock_history push: ${e.message}")
                    }

                // Also update legacy transactions collection for backwards compatibility
                db.collection(COLLECTION_TRANSACTIONS)
                    .document(transaction.id)
                    .set(map, SetOptions.merge())
            } catch (e: Exception) {
                Log.e(TAG, "Exception pushing transaction to cloud: ${e.message}")
            }
        }
    }

    private fun docToTransactionLog(docId: String, data: Map<String, Any>): TransactionLog? {
        val id = (data["id"] as? String)?.ifBlank { docId } ?: docId
        val itemId = ((data["itemId"] ?: data["item_code"] ?: data["itemCode"]) as? String) ?: return null
        val storeId = (data["storeId"] as? String) ?: "CHILLAX"
        val userId = (data["userId"] as? String) ?: "admin"
        val quantityChange = (data["quantityChange"] as? Number)?.toInt() ?: 0
        val typeStr = (data["type"] as? String) ?: "OUT"
        val type = try {
            TransactionType.valueOf(typeStr.uppercase())
        } catch (e: Exception) {
            when {
                typeStr.contains("RETURN", ignoreCase = true) -> TransactionType.RETURN
                typeStr.contains("TRANSFER", ignoreCase = true) -> TransactionType.TRANSFER
                typeStr.contains("LOST", ignoreCase = true) -> TransactionType.LOST
                typeStr.contains("DAMAGE", ignoreCase = true) -> TransactionType.DAMAGE
                typeStr.contains("ADJUST", ignoreCase = true) -> TransactionType.ADJUSTMENT
                typeStr.contains("IN", ignoreCase = true) -> TransactionType.IN
                typeStr.contains("OUT", ignoreCase = true) -> TransactionType.OUT
                else -> TransactionType.OUT
            }
        }
        val methodStr = (data["method"] as? String) ?: "MANUAL"
        val method = try {
            EntryMethod.valueOf(methodStr)
        } catch (e: Exception) {
            EntryMethod.MANUAL
        }
        val timestamp = (data["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis()
        val scannedBy = ((data["scanned_by"] ?: data["scannedBy"]) as? String) ?: userId
        val status = (data["status"] as? String) ?: ""
        val reason = ((data["transaction_reason"] ?: data["transactionReason"] ?: data["reason"]) as? String) ?: ""
        val expectedReturnDate = ((data["expected_return_date"] ?: data["expectedReturnDate"]) as? Number)?.toLong() ?: 0L

        return TransactionLog(
            id = id,
            itemId = itemId,
            storeId = storeId,
            userId = userId,
            quantityChange = quantityChange,
            type = type,
            method = method,
            timestamp = timestamp,
            syncStatus = SyncStatus.SYNCED,
            scanned_by = scannedBy,
            status = status,
            transaction_reason = reason,
            expected_return_date = expectedReturnDate
        )
    }

    private fun inventoryItemToMap(item: InventoryItem): Map<String, Any> {
        val timestamp = if (item.last_updated > 0L) item.last_updated else System.currentTimeMillis()
        return mapOf(
            "item_code" to item.item_code,
            "item_name" to item.item_name,
            "category" to item.category,
            "sub_category" to item.sub_category,
            "asset_type" to item.asset_type,
            "storage_location" to item.storage_location,
            "specific_location" to item.specific_location,
            "total_quantity" to item.total_quantity,
            "available_quantity" to item.available_quantity,
            "unit" to item.unit,
            "specification" to item.specification,
            "status" to item.status,
            "owner_pic" to item.owner_pic,
            "last_stocktake_date" to item.last_stocktake_date,
            "items_out_date" to item.items_out_date,
            "items_in_date" to item.items_in_date,
            "qty_return" to item.qty_return,
            "image" to item.image,
            "remarks" to item.remarks,
            "last_updated" to timestamp
        )
    }

    private fun docToInventoryItem(data: Map<String, Any>): InventoryItem? {
        val code = data["item_code"] as? String ?: return null
        val name = data["item_name"] as? String ?: ""
        return InventoryItem(
            item_code = code,
            item_name = name,
            category = data["category"] as? String ?: "",
            sub_category = data["sub_category"] as? String ?: "",
            asset_type = data["asset_type"] as? String ?: "",
            storage_location = data["storage_location"] as? String ?: "",
            specific_location = data["specific_location"] as? String ?: "",
            total_quantity = (data["total_quantity"] as? Number)?.toInt() ?: 0,
            available_quantity = (data["available_quantity"] as? Number)?.toInt() ?: 0,
            unit = data["unit"] as? String ?: "",
            specification = data["specification"] as? String ?: "",
            status = data["status"] as? String ?: "",
            owner_pic = data["owner_pic"] as? String ?: "",
            last_stocktake_date = data["last_stocktake_date"] as? String ?: "",
            items_out_date = data["items_out_date"] as? String ?: "",
            items_in_date = data["items_in_date"] as? String ?: "",
            qty_return = (data["qty_return"] as? Number)?.toInt() ?: 0,
            image = data["image"] as? String ?: "",
            remarks = data["remarks"] as? String ?: "",
            last_updated = (data["last_updated"] as? Number)?.toLong() ?: 0L
        )
    }
}
