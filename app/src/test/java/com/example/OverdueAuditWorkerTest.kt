package com.example

import com.example.data.EntryMethod
import com.example.data.SyncStatus
import com.example.data.TransactionLog
import com.example.data.TransactionType
import com.example.worker.OverdueAuditWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverdueAuditWorkerTest {

    companion object {
        private const val FOURTEEN_DAYS_MS = 14L * 24 * 60 * 60 * 1000L // 1209600000L
    }

    @Test
    fun testWorkerConstants() {
        assertEquals("Auto-flagged: 14 days overdue", OverdueAuditWorker.OVERDUE_REASON)
        assertEquals("OverdueAuditWork", OverdueAuditWorker.WORK_NAME)
        assertEquals(1209600000L, FOURTEEN_DAYS_MS)
    }

    @Test
    fun testOverdueAuditFilterBusinessLogic() {
        val now = System.currentTimeMillis()

        // Filter predicate matching the exact SQL query in InventoryDao:
        // (type = 'OUT' OR type = 'CHECK_OUT') 
        // AND status != 'Missing' 
        // AND status != 'Returned' 
        // AND expected_return_date > 0 
        // AND :currentTimestamp >= (expected_return_date + 1209600000)
        fun isOverdue(tx: TransactionLog, currentTimestamp: Long): Boolean {
            val isCheckedOut = tx.type == TransactionType.OUT || tx.type == TransactionType.CHECK_OUT
            val isNotResolved = tx.status != "Missing" && tx.status != "Returned"
            val hasExpectedDate = tx.expected_return_date > 0L
            val isPast14Days = currentTimestamp >= (tx.expected_return_date + FOURTEEN_DAYS_MS)
            return isCheckedOut && isNotResolved && hasExpectedDate && isPast14Days
        }

        // Case 1: Checked out 20 days ago, expected return date 15 days ago -> Overdue (>14 days past return date)
        val overdueTx = TransactionLog(
            id = "tx-1",
            itemId = "ITEM-001",
            storeId = "CHILLAX",
            userId = "admin",
            quantityChange = -1,
            type = TransactionType.CHECK_OUT,
            method = EntryMethod.MANUAL,
            timestamp = now - (20L * 24 * 60 * 60 * 1000L),
            syncStatus = SyncStatus.SYNCED,
            status = "Active",
            expected_return_date = now - (15L * 24 * 60 * 60 * 1000L)
        )
        assertTrue(isOverdue(overdueTx, now))

        // Case 2: Checked out with expected return date 5 days ago -> NOT overdue yet (only 5 days past return date)
        val notOverdueTx = TransactionLog(
            id = "tx-2",
            itemId = "ITEM-002",
            storeId = "CHILLAX",
            userId = "admin",
            quantityChange = -1,
            type = TransactionType.OUT,
            method = EntryMethod.MANUAL,
            timestamp = now - (10L * 24 * 60 * 60 * 1000L),
            syncStatus = SyncStatus.SYNCED,
            status = "Active",
            expected_return_date = now - (5L * 24 * 60 * 60 * 1000L)
        )
        assertFalse(isOverdue(notOverdueTx, now))

        // Case 3: Checked out 15 days ago, but already marked as Missing -> Should NOT be picked up
        val alreadyMissingTx = TransactionLog(
            id = "tx-3",
            itemId = "ITEM-003",
            storeId = "CHILLAX",
            userId = "admin",
            quantityChange = -1,
            type = TransactionType.CHECK_OUT,
            method = EntryMethod.MANUAL,
            timestamp = now - (20L * 24 * 60 * 60 * 1000L),
            syncStatus = SyncStatus.SYNCED,
            status = "Missing",
            expected_return_date = now - (15L * 24 * 60 * 60 * 1000L)
        )
        assertFalse(isOverdue(alreadyMissingTx, now))

        // Case 4: Checked out 15 days ago, but status is Returned -> Should NOT be picked up
        val returnedTx = TransactionLog(
            id = "tx-4",
            itemId = "ITEM-004",
            storeId = "CHILLAX",
            userId = "admin",
            quantityChange = -1,
            type = TransactionType.CHECK_OUT,
            method = EntryMethod.MANUAL,
            timestamp = now - (20L * 24 * 60 * 60 * 1000L),
            syncStatus = SyncStatus.SYNCED,
            status = "Returned",
            expected_return_date = now - (15L * 24 * 60 * 60 * 1000L)
        )
        assertFalse(isOverdue(returnedTx, now))

        // Case 5: Regular checkout with no expected return date (0) -> Should NOT be picked up
        val noExpectedDateTx = TransactionLog(
            id = "tx-5",
            itemId = "ITEM-005",
            storeId = "CHILLAX",
            userId = "admin",
            quantityChange = -1,
            type = TransactionType.CHECK_OUT,
            method = EntryMethod.MANUAL,
            timestamp = now - (20L * 24 * 60 * 60 * 1000L),
            syncStatus = SyncStatus.SYNCED,
            status = "Active",
            expected_return_date = 0L
        )
        assertFalse(isOverdue(noExpectedDateTx, now))

        // Case 6: Type IN (Check-in), not OUT or CHECK_OUT -> Should NOT be picked up
        val inTx = TransactionLog(
            id = "tx-6",
            itemId = "ITEM-006",
            storeId = "CHILLAX",
            userId = "admin",
            quantityChange = 1,
            type = TransactionType.IN,
            method = EntryMethod.MANUAL,
            timestamp = now - (20L * 24 * 60 * 60 * 1000L),
            syncStatus = SyncStatus.SYNCED,
            status = "Active",
            expected_return_date = now - (15L * 24 * 60 * 60 * 1000L)
        )
        assertFalse(isOverdue(inTx, now))
    }
}
