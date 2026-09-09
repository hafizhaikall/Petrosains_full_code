package com.example.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun formatLastSyncTime(timestamp: Long): String {
    if (timestamp <= 0L) return "Last Synced: Never"
    val diffMs = System.currentTimeMillis() - timestamp
    if (diffMs in 0..59_999) return "Last Synced: Just now"

    val syncDate = Calendar.getInstance().apply { timeInMillis = timestamp }
    val now = Calendar.getInstance()

    val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    val formattedTime = timeFormat.format(Date(timestamp))

    return when {
        now.get(Calendar.YEAR) == syncDate.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == syncDate.get(Calendar.DAY_OF_YEAR) -> {
            "Last Synced: Today at $formattedTime"
        }
        now.get(Calendar.YEAR) == syncDate.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) - syncDate.get(Calendar.DAY_OF_YEAR) == 1 -> {
            "Last Synced: Yesterday at $formattedTime"
        }
        else -> {
            val dateFormat = SimpleDateFormat("dd MMM, h:mm a", Locale.getDefault())
            "Last Synced: ${dateFormat.format(Date(timestamp))}"
        }
    }
}
