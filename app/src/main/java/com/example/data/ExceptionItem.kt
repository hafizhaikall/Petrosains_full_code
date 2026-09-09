package com.example.data

import androidx.room.ColumnInfo

/**
 * Represents an active exception (Missing or Damaged item) for the global Issue Tracker.
 */
data class ExceptionItem(
    val id: String,
    val itemId: String,
    val itemName: String,
    val quantity: Int,
    val status: String,
    val timestamp: Long,
    val scannedBy: String = "",
    val reason: String = "",
    val storeId: String = "",
    @ColumnInfo(name = "is_resolved")
    val is_resolved: Int = 0
) {
    val isResolved: Int
        get() = is_resolved
}
