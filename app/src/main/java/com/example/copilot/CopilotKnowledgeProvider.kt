package com.example.copilot

import com.example.data.InventoryItem
import com.example.data.TransactionLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CopilotKnowledgeProvider {

    const val STRICT_SYSTEM_ROLE =
        "You are A.R.I.F, the Inventory Copilot. Teach the user how to use the app and analyze stock data. Your dual role is to analyze local database stock AND teach users how to use this application. If a user asks how to do something, provide short, step-by-step instructions. Do NOT use asterisk (*) symbols or markdown bold (**) in your responses; provide clean, human-readable plain text."

    const val APP_MAP = """
=== APP MAP & NAVIGATION FACTS ===
Fact: The barcode scanner is the floating button (the prominent center circular button on the bottom navigation bar).
Fact: Exception auditing is found in the History tab.
Fact: Users must select a Programme Category as a reason when checking out items.
Fact: The Dashboard displays live store alerts for Out of Stock (0 qty) and Low Stock (1 to 5 qty) items.
Fact: The Inventory / Search tab allows live catalog searching and filtering by Item Code, Name, Category, or Rack location.
Fact: The Profile & Settings tab provides standalone CSV exports for checking in Excel/Google Sheets: 'Export Inventory Catalog' and 'Export Stock History'.
Fact: Manual Entry is accessible from the Dashboard quick actions when an item barcode cannot be scanned.
Fact: Users can switch store locations at any time by tapping the Store card at the top of the Dashboard.
Fact: AI Multi-Object Camera Scan can automatically detect and count items (such as stacked cups and components) using smart edge ML.
"""

    /**
     * Generates a concise, structured text summary of the current inventory stock levels.
     */
    fun generateInventorySummary(storeId: String, items: List<InventoryItem>): String {
        if (items.isEmpty()) {
            return "Current Store ($storeId): No inventory records currently loaded in local database."
        }

        val totalSkus = items.size
        val totalQuantity = items.sumOf { it.available_quantity }
        val outOfStock = items.filter { it.available_quantity == 0 }
        val lowStock = items.filter { it.available_quantity in 1..5 }

        val sb = StringBuilder()
        sb.appendLine("=== CURRENT LIVE INVENTORY SUMMARY (Store: $storeId) ===")
        sb.appendLine("Total SKUs: $totalSkus | Total Available Stock: $totalQuantity units")
        sb.appendLine("Out of Stock (${outOfStock.size} items): ${outOfStock.take(8).joinToString { "${it.item_name} (${it.item_code})" }.ifEmpty { "None" }}")
        sb.appendLine("Low Stock (${lowStock.size} items): ${lowStock.take(8).joinToString { "${it.item_name} (${it.item_code}: ${it.available_quantity} left)" }.ifEmpty { "None" }}")

        sb.appendLine("Catalog Sample:")
        items.take(15).forEach {
            val starting = if (it.initial_quantity > 0) it.initial_quantity else it.total_quantity
            sb.appendLine("- ${it.item_code}: ${it.item_name} | Avail: ${it.available_quantity} / Starting: $starting ${it.unit} | Location: ${it.specific_location.ifBlank { "Unassigned" }} | Status: ${it.status.ifBlank { "Active" }}")
        }

        return sb.toString()
    }

    /**
     * Generates a concise text summary of recent transaction audit logs.
     */
    fun generateHistorySummary(transactions: List<TransactionLog>): String {
        if (transactions.isEmpty()) {
            return "Recent Stock History: No transactions recorded yet."
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val sb = StringBuilder()
        sb.appendLine("=== RECENT STOCK HISTORY (AUDIT TRAIL) ===")
        transactions.take(10).forEach { tx ->
            val dateStr = try { dateFormat.format(Date(tx.timestamp)) } catch (_: Exception) { tx.timestamp.toString() }
            sb.appendLine("- [$dateStr] Item: ${tx.itemId} | Type: ${tx.type.name} (${tx.quantityChange}) | By: ${tx.scanned_by.ifBlank { tx.userId }} | Reason: ${tx.transaction_reason.ifBlank { "N/A" }}")
        }
        return sb.toString()
    }

    /**
     * Builds the complete system prompt injecting role, app map, inventory stock, and history logs.
     */
    fun buildFullSystemPrompt(
        storeId: String,
        items: List<InventoryItem>,
        transactions: List<TransactionLog>
    ): String {
        val inventorySummary = generateInventorySummary(storeId, items)
        val historySummary = generateHistorySummary(transactions)

        return """
$STRICT_SYSTEM_ROLE

$APP_MAP

$inventorySummary

$historySummary

Instructions:
- When teaching how to use the app, give short, crisp, numbered step-by-step instructions referencing the facts above.
- When answering stock questions, use the exact item codes, quantities, and locations from the inventory summary.
- Keep responses friendly, helpful, and concise.
- IMPORTANT: Never use asterisk (*) symbols or markdown bold (**) in your responses. Output clean, readable plain text using standard numbers (1., 2.) or bullets (•) without asterisks.
""".trimIndent()
    }

    /**
     * Offline fallback response for common questions when internet is unavailable
     * or API key is not configured.
     */
    fun getOfflineFallbackResponse(query: String, items: List<InventoryItem>): String? {
        val q = query.lowercase().trim()
        return when {
            "how do i scan" in q || "scan an item" in q ->
                "How to Scan an Item:\n1. Tap the floating center circular button on the bottom navigation bar.\n2. Point your camera at the barcode or use AI Detection.\n3. Verify item details and select Check In or Check Out."

            "report a missing item" in q || "missing item" in q || "lost" in q ->
                "How to Report a Missing Item:\n1. Search for the item in the Inventory tab or scan its code.\n2. Tap into the Item Detail screen.\n3. Change the stock status to 'Lost' or record a transaction with reason 'Lost / Exception'.\n4. View audit records in the History tab."

            "low in stock" in q || "low stock" in q -> {
                val low = items.filter { it.available_quantity in 1..5 }
                if (low.isEmpty()) {
                    "All items currently have healthy stock levels (above 5 units)!"
                } else {
                    "Low Stock Items (1-5 units remaining):\n" +
                        low.joinToString("\n") { "• ${it.item_name} (${it.item_code}): ${it.available_quantity} ${it.unit} left at ${it.specific_location}" }
                }
            }

            "out of stock" in q -> {
                val out = items.filter { it.available_quantity == 0 }
                if (out.isEmpty()) {
                    "There are currently zero out-of-stock items!"
                } else {
                    "Out of Stock Items:\n" +
                        out.joinToString("\n") { "• ${it.item_name} (${it.item_code}) at ${it.specific_location}" }
                }
            }

            "export" in q || "csv" in q || "excel" in q || "sheet" in q ->
                "How to Export to Excel / CSV:\n1. Go to the Setting (Profile) tab on the bottom bar.\n2. Under 'Spreadsheet Exports', choose:\n   - Export Inventory Catalog (CSV) for stock levels.\n   - Export Stock History (CSV) for transaction logs.\n3. Tap to download or immediately open in Google Sheets / Excel."

            "exception audit" in q || "history" in q ->
                "Exception Auditing:\nOpen the History tab on the bottom navigation bar to inspect the audit trail with timestamps, user names, reasons, and quantity adjustments."

            else -> null
        }
    }
}
