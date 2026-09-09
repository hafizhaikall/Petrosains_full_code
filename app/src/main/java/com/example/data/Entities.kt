package com.example.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

// ---- User (supports secure credential authentication) ----
@Serializable
@Entity(tableName = "users")
data class User(
    @PrimaryKey val id: String,
    val username: String,
    val name: String = "",
    val password: String = ""
)

// ---- Store (matches your real store names) ----
@Serializable
@Entity(tableName = "stores")
data class Store(
    @PrimaryKey val id: String,
    val name: String,
    val isOfflineFirst: Boolean
)

// Plain data class — Populated by AssetDbHelper from inventory.db or Room inventory_master
@Serializable
data class InventoryItem(
    val item_code: String,
    val item_name: String,
    val category: String = "",
    val sub_category: String = "",
    val asset_type: String = "",
    val storage_location: String = "",
    val specific_location: String = "",
    val total_quantity: Int = 0,
    val available_quantity: Int = 0,
    val initial_quantity: Int = 0,
    val unit: String = "",
    val specification: String = "",
    val status: String = "",
    val owner_pic: String = "",
    val last_stocktake_date: String = "",
    val items_out_date: String = "",
    val items_in_date: String = "",
    val qty_return: Int = 0,
    val image: String = "",
    val remarks: String = "",
    val last_updated: Long = 0L,
    val is_pending_sync: Boolean = true
)

// ---- Item (Room entity, supports AI scan & code lookup with column mappings) ----
@Serializable
@Entity(tableName = "items")
data class Item(
    @PrimaryKey
    @ColumnInfo(name = "Item Code")
    val id: String,

    @ColumnInfo(name = "Item Name")
    val name: String,

    @ColumnInfo(name = "Category")
    val category: String = "",

    @ColumnInfo(name = "Specification")
    val description: String = "",

    val imageUri: String? = null,

    @ColumnInfo(name = "Asset Type")
    val assetType: String = "",

    @ColumnInfo(name = "Specific Location / Rack")
    val specificLocation: String = "",

    @ColumnInfo(name = "Storage Location")
    val storageLocation: String = "",

    @ColumnInfo(name = "initial_quantity", defaultValue = "0")
    val initialQuantity: Int = 0
)

// Result model specifically for scanned Item Code queries
@Serializable
data class ScannedItemResult(
    val itemCode: String,
    val itemName: String,
    val assetType: String,
    val specificLocation: String,
    val storageLocation: String = "",
    val availableQuantity: Int = 0,
    val totalQuantity: Int = 0,
    val category: String = "",
    val status: String = "",
    val unit: String = ""
)

enum class EntryResult {
    SUCCESS,
    ITEM_NOT_FOUND,
    INSUFFICIENT_STOCK,
    WRONG_STORE
}

data class TransactionOperationResult(
    val status: EntryResult,
    val correctStore: String = "",
    val message: String = ""
)

// ---- Inventory (quantity tracking per store) ----
@Serializable
@Entity(tableName = "inventory")
data class Inventory(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val itemId: String,
    val storeId: String,
    val quantity: Int,
    val lastUpdated: Long,
    val syncStatus: SyncStatus
)

@Serializable
@Entity(tableName = "transactions")
data class TransactionLog(
    @PrimaryKey val id: String,
    val itemId: String,
    val storeId: String,
    val userId: String,
    val quantityChange: Int,
    val type: TransactionType,
    val method: EntryMethod,
    val timestamp: Long,
    val syncStatus: SyncStatus = SyncStatus.SYNCED,
    @ColumnInfo(name = "scanned_by", defaultValue = "")
    val scanned_by: String = "",
    @ColumnInfo(name = "status", defaultValue = "")
    val status: String = "",
    @ColumnInfo(name = "transaction_reason", defaultValue = "")
    val transaction_reason: String = "",
    @ColumnInfo(name = "expected_return_date", defaultValue = "0")
    val expected_return_date: Long = 0L,
    @ColumnInfo(name = "is_resolved", defaultValue = "0")
    val is_resolved: Int = 0,
    @ColumnInfo(name = "is_pending_sync", defaultValue = "1")
    val is_pending_sync: Boolean = true
)

// ---- Stock History (Room Entity with is_pending_sync flag) ----
@Serializable
@Entity(tableName = "stock_history")
data class StockHistory(
    @PrimaryKey val id: String,
    val itemId: String,
    val storeId: String = "CHILLAX",
    val userId: String = "admin",
    val quantityChange: Int = 0,
    val type: TransactionType = TransactionType.OUT,
    val method: EntryMethod = EntryMethod.MANUAL,
    val timestamp: Long = System.currentTimeMillis(),
    val syncStatus: SyncStatus = SyncStatus.SYNCED,
    @ColumnInfo(name = "scanned_by", defaultValue = "")
    val scanned_by: String = "",
    @ColumnInfo(name = "status", defaultValue = "")
    val status: String = "",
    @ColumnInfo(name = "transaction_reason", defaultValue = "")
    val transaction_reason: String = "",
    @ColumnInfo(name = "expected_return_date", defaultValue = "0")
    val expected_return_date: Long = 0L,
    @ColumnInfo(name = "is_resolved", defaultValue = "0")
    val is_resolved: Int = 0,
    @ColumnInfo(name = "is_pending_sync", defaultValue = "1")
    val is_pending_sync: Boolean = true
)

// ---- Inventory Master (Room Entity with is_pending_sync flag) ----
@Serializable
@Entity(tableName = "inventory_master")
data class InventoryMaster(
    @PrimaryKey
    @ColumnInfo(name = "Item Code")
    val item_code: String,

    @ColumnInfo(name = "Item Name")
    val item_name: String,

    @ColumnInfo(name = "Category")
    val category: String = "",

    @ColumnInfo(name = "Sub Category")
    val sub_category: String = "",

    @ColumnInfo(name = "Asset Type")
    val asset_type: String = "",

    @ColumnInfo(name = "Storage Location")
    val storage_location: String = "",

    @ColumnInfo(name = "Specific Location / Rack")
    val specific_location: String = "",

    @ColumnInfo(name = "Total Quantity")
    val total_quantity: Int = 0,

    @ColumnInfo(name = "Available Quantity")
    val available_quantity: Int = 0,

    @ColumnInfo(name = "initial_quantity")
    val initial_quantity: Int = 0,

    @ColumnInfo(name = "Unit")
    val unit: String = "",

    @ColumnInfo(name = "Specification")
    val specification: String = "",

    @ColumnInfo(name = "Status")
    val status: String = "",

    @ColumnInfo(name = "Owner / PIC")
    val owner_pic: String = "",

    @ColumnInfo(name = "Last Stocktake Date")
    val last_stocktake_date: String = "",

    @ColumnInfo(name = "Items Out (Date)")
    val items_out_date: String = "",

    @ColumnInfo(name = "Items In (Date)")
    val items_in_date: String = "",

    @ColumnInfo(name = "Qty (Return)")
    val qty_return: Int = 0,

    @ColumnInfo(name = "Image")
    val image: String = "",

    @ColumnInfo(name = "Remarks")
    val remarks: String = "",

    @ColumnInfo(name = "last_updated")
    val last_updated: Long = 0L,

    @ColumnInfo(name = "is_pending_sync", defaultValue = "1")
    val is_pending_sync: Boolean = true
)

enum class SyncStatus { SYNCED, PENDING, FAILED }

enum class TransactionType {
    OUT,
    IN,
    RETURN,
    TRANSFER,
    LOST,
    DAMAGE,
    ADJUSTMENT,
    CHECK_IN,
    CHECK_OUT
}

enum class EntryMethod { AI_SCAN, MANUAL }

object InventoryConstants {
    val OFFICIAL_TRANSACTION_TYPES = listOf(
        TransactionType.OUT,
        TransactionType.IN,
        TransactionType.RETURN,
        TransactionType.TRANSFER,
        TransactionType.LOST,
        TransactionType.DAMAGE,
        TransactionType.ADJUSTMENT
    )

    val OFFICIAL_STATUSES = listOf(
        "Active",
        "Under Maintenance",
        "Lost",
        "Disposed",
        "Inactive"
    )
}
