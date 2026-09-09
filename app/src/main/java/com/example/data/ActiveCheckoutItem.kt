package com.example.data

import androidx.room.ColumnInfo

/**
 * Represents an active item checked out by a user for display on the Profile screen.
 * Includes expected_return_date matching the column name in the SQLite database.
 */
data class ActiveCheckoutItem(
    val id: String,
    val itemId: String,
    val itemName: String,
    val quantity: Int,
    @ColumnInfo(name = "expected_return_date")
    val expected_return_date: Long = 0L,
    val status: String,
    val timestamp: Long
) {
    // Getter for backward and camelCase compatibility
    val expectedReturnDate: Long
        get() = expected_return_date
}
