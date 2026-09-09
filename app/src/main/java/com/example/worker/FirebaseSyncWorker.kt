package com.example.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.AppDatabase
import com.example.data.AssetDbHelper
import com.example.sync.SyncManager
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Background worker that synchronizes pending local Room and SQLite records
 * (transactions, stock_history, and inventory_master) to Firebase Firestore
 * when network connectivity is available.
 */
class FirebaseSyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val TAG = "FirebaseSyncWorker"
        const val WORK_NAME = "FirebaseSyncWork"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Starting Firebase background sync...")
            val database = AppDatabase.getDatabase(applicationContext)
            val dao = database.inventoryDao()
            val assetDb = AssetDbHelper(applicationContext)

            // Ensure Firebase is initialized
            if (FirebaseApp.getApps(applicationContext).isEmpty()) {
                Log.w(TAG, "FirebaseApp is not initialized yet. Skipping upload.")
                return@withContext Result.retry()
            }

            val firestore = try {
                FirebaseFirestore.getInstance()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get Firestore instance: ${e.message}")
                return@withContext Result.retry()
            }

            // 1. Sync pending transactions from Room
            val pendingTransactions = dao.getPendingSyncTransactions()
            Log.d(TAG, "Found ${pendingTransactions.size} pending transactions in Room")
            for (tx in pendingTransactions) {
                try {
                    val itemName = assetDb.getItemByCode(tx.itemId)?.item_name
                        ?: dao.getItemByCode(tx.itemId)?.name
                        ?: tx.itemId

                    val map = mapOf(
                        "id" to tx.id,
                        "itemId" to tx.itemId,
                        "item_code" to tx.itemId,
                        "itemName" to itemName,
                        "storeId" to tx.storeId,
                        "userId" to tx.userId,
                        "quantityChange" to tx.quantityChange,
                        "type" to tx.type.name,
                        "method" to tx.method.name,
                        "timestamp" to tx.timestamp,
                        "scanned_by" to tx.scanned_by.ifBlank { tx.userId },
                        "status" to tx.status,
                        "transaction_reason" to tx.transaction_reason,
                        "expected_return_date" to tx.expected_return_date,
                        "is_resolved" to tx.is_resolved
                    )

                    // Upload to Firestore stock_history collection
                    firestore.collection(SyncManager.COLLECTION_STOCK_HISTORY)
                        .document(tx.id)
                        .set(map, SetOptions.merge())
                        .await()

                    // Also upload to legacy transactions collection
                    firestore.collection(SyncManager.COLLECTION_TRANSACTIONS)
                        .document(tx.id)
                        .set(map, SetOptions.merge())
                        .await()

                    // Mark as synced locally
                    dao.markTransactionSynced(tx.id)
                    dao.markStockHistorySynced(tx.id)
                    Log.d(TAG, "Successfully synced transaction ${tx.id}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to upload transaction ${tx.id}: ${e.message}")
                    throw e // Re-throw to trigger worker retry
                }
            }

            // 2. Sync pending stock_history from Room
            val pendingStockHistory = dao.getPendingSyncStockHistory()
            for (sh in pendingStockHistory) {
                try {
                    val itemName = assetDb.getItemByCode(sh.itemId)?.item_name
                        ?: dao.getItemByCode(sh.itemId)?.name
                        ?: sh.itemId

                    val map = mapOf(
                        "id" to sh.id,
                        "itemId" to sh.itemId,
                        "item_code" to sh.itemId,
                        "itemName" to itemName,
                        "storeId" to sh.storeId,
                        "userId" to sh.userId,
                        "quantityChange" to sh.quantityChange,
                        "type" to sh.type.name,
                        "method" to sh.method.name,
                        "timestamp" to sh.timestamp,
                        "scanned_by" to sh.scanned_by.ifBlank { sh.userId },
                        "status" to sh.status,
                        "transaction_reason" to sh.transaction_reason,
                        "expected_return_date" to sh.expected_return_date,
                        "is_resolved" to sh.is_resolved
                    )

                    firestore.collection(SyncManager.COLLECTION_STOCK_HISTORY)
                        .document(sh.id)
                        .set(map, SetOptions.merge())
                        .await()

                    dao.markStockHistorySynced(sh.id)
                    dao.markTransactionSynced(sh.id)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to upload stock_history ${sh.id}: ${e.message}")
                    throw e
                }
            }

            // 3. Sync pending inventory_master records from Room
            val pendingInventory = dao.getPendingSyncInventoryMaster()
            Log.d(TAG, "Found ${pendingInventory.size} pending inventory_master records in Room")
            for (item in pendingInventory) {
                try {
                    val map = mapOf(
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
                        "last_updated" to (if (item.last_updated > 0L) item.last_updated else System.currentTimeMillis())
                    )

                    firestore.collection(SyncManager.COLLECTION_INVENTORY)
                        .document(item.item_code.trim())
                        .set(map, SetOptions.merge())
                        .await()

                    dao.markInventoryMasterSynced(item.item_code)
                    assetDb.markItemSynced(item.item_code)
                    Log.d(TAG, "Successfully synced inventory item ${item.item_code}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to upload inventory item ${item.item_code}: ${e.message}")
                    throw e
                }
            }

            // 4. Sync pending items from SQLite AssetDbHelper
            val pendingAssetItems = assetDb.getPendingSyncItems()
            for (item in pendingAssetItems) {
                try {
                    val map = mapOf(
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
                        "last_updated" to (if (item.last_updated > 0L) item.last_updated else System.currentTimeMillis())
                    )

                    firestore.collection(SyncManager.COLLECTION_INVENTORY)
                        .document(item.item_code.trim())
                        .set(map, SetOptions.merge())
                        .await()

                    assetDb.markItemSynced(item.item_code)
                    dao.markInventoryMasterSynced(item.item_code)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to upload asset item ${item.item_code}: ${e.message}")
                    throw e
                }
            }

            Log.i(TAG, "Firebase background sync completed successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Firebase sync failed gracefully, worker will retry: ${e.message}", e)
            Result.retry()
        }
    }
}
