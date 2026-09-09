package com.example.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.AppDatabase
import com.example.data.AssetDbHelper
import com.example.data.EntryMethod
import com.example.data.SyncStatus
import com.example.data.TransactionLog
import com.example.data.TransactionType
import com.example.sync.SyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Background worker that automatically audits checked-out items and flags
 * them as 'Missing' if the current date is more than 14 days past their expected return date.
 */
class OverdueAuditWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val TAG = "OverdueAuditWorker"
        const val WORK_NAME = "OverdueAuditWork"
        const val OVERDUE_REASON = "Auto-flagged: 14 days overdue"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val database = AppDatabase.getDatabase(applicationContext)
            val dao = database.inventoryDao()
            val assetDb = AssetDbHelper(applicationContext)
            val syncManager = SyncManager(applicationContext, assetDb, dao)
            val currentTime = System.currentTimeMillis()

            Log.i(TAG, "Starting overdue item audit check at timestamp: $currentTime")

            val overdueItems = dao.getOverdueCheckedOutItems(currentTime)
            Log.i(TAG, "Found ${overdueItems.size} overdue items to audit")

            for (item in overdueItems) {
                // 1. Update its Status in inventory_master to 'Missing'
                assetDb.updateStatus(item.itemId, "Missing", currentTime)

                // Update original transaction status so it's not repeatedly flagged
                dao.updateTransactionStatus(item.id, "Missing")

                // 2. Insert a new row into stock_history containing:
                // - scanned_by username
                // - new 'Missing' status
                // - transaction_reason 'Auto-flagged: 14 days overdue'
                val effectiveUser = item.scanned_by.ifBlank { item.userId.ifBlank { "System" } }
                val auditRecord = TransactionLog(
                    id = UUID.randomUUID().toString(),
                    itemId = item.itemId,
                    storeId = item.storeId.ifBlank { "CHILLAX" },
                    userId = effectiveUser,
                    quantityChange = 0,
                    type = TransactionType.LOST,
                    method = EntryMethod.MANUAL,
                    timestamp = currentTime,
                    syncStatus = SyncStatus.SYNCED,
                    scanned_by = effectiveUser,
                    status = "Missing",
                    transaction_reason = OVERDUE_REASON,
                    expected_return_date = item.expected_return_date
                )

                dao.insertTransaction(auditRecord)
                syncManager.pushTransaction(auditRecord)
                syncManager.pushItemUpdate(item.itemId)

                Log.i(TAG, "Successfully flagged item ${item.itemId} as Missing (Overdue audit record ${auditRecord.id} created)")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to complete overdue items audit", e)
            Result.retry()
        }
    }
}
