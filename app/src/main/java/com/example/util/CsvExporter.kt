package com.example.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.data.InventoryItem
import com.example.data.TransactionLog
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CsvExportResult(
    val success: Boolean,
    val fileName: String,
    val itemCount: Int,
    val savedPathDescription: String,
    val shareIntent: Intent? = null,
    val errorMessage: String? = null
)

object CsvExporter {

    /**
     * Generates a clean, RFC-4180 standard CSV containing all inventory items.
     * Column 'Total Quantity' represents the original/starting stock amount.
     * Column 'Quantity Available' represents the current live stock amount.
     */
    fun generateInventoryCsv(items: List<InventoryItem>): String {
        val sb = StringBuilder()

        // CSV Header row
        val headers = listOf(
            "Item Code",
            "Item Name",
            "Category",
            "Sub Category",
            "Asset Type",
            "Storage Location",
            "Specific Location / Rack",
            "Total Quantity",
            "Quantity Available",
            "Unit",
            "Stock Status",
            "Specification",
            "Owner / PIC",
            "Last Stocktake Date",
            "Remarks"
        )
        sb.appendLine(headers.joinToString(",") { escapeCsv(it) })

        // Data rows
        for (item in items) {
            val dynamicStatus = when {
                item.available_quantity == 0 -> "OUT OF STOCK"
                item.available_quantity in 1..5 -> "LOW STOCK"
                item.status.isNotBlank() -> item.status.uppercase()
                else -> "AVAILABLE"
            }

            val startingStock = if (item.initial_quantity > 0) item.initial_quantity else item.total_quantity
            val currentLiveStock = item.available_quantity

            val row = listOf(
                item.item_code,
                item.item_name,
                item.category,
                item.sub_category,
                item.asset_type,
                item.storage_location,
                item.specific_location,
                startingStock.toString(),
                currentLiveStock.toString(),
                item.unit,
                dynamicStatus,
                item.specification,
                item.owner_pic,
                item.last_stocktake_date,
                item.remarks
            )
            sb.appendLine(row.joinToString(",") { escapeCsv(it) })
        }

        return sb.toString()
    }

    /**
     * Generates a standalone CSV audit trail for transaction logs (stock history).
     * Includes Item, Reason, Scanned By, and Timestamp as requested.
     */
    fun generateHistoryCsv(transactions: List<TransactionLog>): String {
        val sb = StringBuilder()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        val headers = listOf(
            "Transaction ID",
            "Timestamp",
            "Item",
            "Store Location",
            "Transaction Type",
            "Quantity Change",
            "Scanned By",
            "Reason",
            "Status",
            "Entry Method"
        )
        sb.appendLine(headers.joinToString(",") { escapeCsv(it) })

        for (tx in transactions) {
            val dateStr = try {
                dateFormat.format(Date(tx.timestamp))
            } catch (_: Exception) {
                tx.timestamp.toString()
            }

            val row = listOf(
                tx.id,
                dateStr,
                tx.itemId,
                tx.storeId,
                tx.type.name,
                tx.quantityChange.toString(),
                tx.scanned_by,
                tx.transaction_reason,
                tx.status,
                tx.method.name
            )
            sb.appendLine(row.joinToString(",") { escapeCsv(it) })
        }

        return sb.toString()
    }

    /**
     * Exports the transaction history records as a standalone CSV file.
     */
    fun exportHistoryReport(
        context: Context,
        transactions: List<TransactionLog>,
        storeName: String = "All_Stores"
    ): CsvExportResult {
        try {
            val sdf = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
            val dateStr = sdf.format(Date())
            val sanitizedStore = storeName.replace("[^a-zA-Z0-9_]".toRegex(), "_")
            val fileName = "StockHistory_${sanitizedStore}_$dateStr.csv"
            val csvContent = generateHistoryCsv(transactions)
            val csvBytes = csvContent.toByteArray(Charsets.UTF_8)

            val exportDir = File(context.cacheDir, "exports")
            exportDir.mkdirs()
            val exportFile = File(exportDir, fileName)
            FileOutputStream(exportFile).use { fos ->
                fos.write(csvBytes)
            }

            var publicSavedPath = "Downloads/$fileName"
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { os ->
                            os.write(csvBytes)
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val pubDownloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (pubDownloadDir.exists() || pubDownloadDir.mkdirs()) {
                        val pubFile = File(pubDownloadDir, fileName)
                        FileOutputStream(pubFile).use { fos ->
                            fos.write(csvBytes)
                        }
                        publicSavedPath = pubFile.absolutePath
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                publicSavedPath = "Internal storage / Shared"
            }

            val fileUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                exportFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, fileUri)
                putExtra(Intent.EXTRA_SUBJECT, "Stock History Audit Trail - $storeName")
                putExtra(Intent.EXTRA_TEXT, "Attached is the complete transaction log audit trail for $storeName (${transactions.size} records).")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooserIntent = Intent.createChooser(shareIntent, "Open Stock History in Excel / Sheets or Share")
            chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            return CsvExportResult(
                success = true,
                fileName = fileName,
                itemCount = transactions.size,
                savedPathDescription = publicSavedPath,
                shareIntent = chooserIntent
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return CsvExportResult(
                success = false,
                fileName = "",
                itemCount = transactions.size,
                savedPathDescription = "",
                errorMessage = e.localizedMessage ?: "Failed to export history CSV"
            )
        }
    }

    /**
     * Exports the inventory items as a CSV file to the device's public Downloads directory
     * and constructs an Intent to open in Excel/Google Sheets or share.
     */
    fun exportInventoryReport(
        context: Context,
        items: List<InventoryItem>,
        storeName: String = "CHILLAX"
    ): CsvExportResult {
        try {
            val sdf = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
            val dateStr = sdf.format(Date())
            val sanitizedStore = storeName.replace("[^a-zA-Z0-9_]".toRegex(), "_")
            val fileName = "Inventory_${sanitizedStore}_$dateStr.csv"
            val csvContent = generateInventoryCsv(items)
            val csvBytes = csvContent.toByteArray(Charsets.UTF_8)

            // 1. Save to app cache / files directory for FileProvider sharing
            val exportDir = File(context.cacheDir, "exports")
            exportDir.mkdirs()
            val exportFile = File(exportDir, fileName)
            FileOutputStream(exportFile).use { fos ->
                fos.write(csvBytes)
            }

            // 2. Also save to public Downloads folder so it persists in the phone's Download directory
            var publicSavedPath = "Downloads/$fileName"
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { os ->
                            os.write(csvBytes)
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val pubDownloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (pubDownloadDir.exists() || pubDownloadDir.mkdirs()) {
                        val pubFile = File(pubDownloadDir, fileName)
                        FileOutputStream(pubFile).use { fos ->
                            fos.write(csvBytes)
                        }
                        publicSavedPath = pubFile.absolutePath
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // If public Downloads failed, exportFile is still fully usable
                publicSavedPath = "Internal storage / Shared"
            }

            // 3. Create Share & Open Intent via FileProvider
            val fileUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                exportFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, fileUri)
                putExtra(Intent.EXTRA_SUBJECT, "Inventory Status Report - $storeName")
                putExtra(Intent.EXTRA_TEXT, "Attached is the latest inventory status report for $storeName (${items.size} items).")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooserIntent = Intent.createChooser(shareIntent, "Open in Excel / Google Sheets or Share")
            chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            return CsvExportResult(
                success = true,
                fileName = fileName,
                itemCount = items.size,
                savedPathDescription = publicSavedPath,
                shareIntent = chooserIntent
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return CsvExportResult(
                success = false,
                fileName = "",
                itemCount = items.size,
                savedPathDescription = "",
                errorMessage = e.localizedMessage ?: "Failed to export CSV"
            )
        }
    }

    private fun escapeCsv(value: String): String {
        val clean = value.replace("\r", " ").replace("\n", " ").trim()
        return if (clean.contains(",") || clean.contains("\"") || clean.contains(";")) {
            "\"" + clean.replace("\"", "\"\"") + "\""
        } else {
            clean
        }
    }
}
