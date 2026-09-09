package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.util.CsvExportResult
import androidx.navigation.NavController
import com.example.data.TransactionType
import com.example.viewmodel.InventoryViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: InventoryViewModel, navController: NavController) {
    val context = LocalContext.current
    val transactions by viewModel.transactions.collectAsState()
    val items by viewModel.items.collectAsState()
    val allInventoryItems by viewModel.allInventoryItems.collectAsState()
    val currentStoreId by viewModel.storeId.collectAsState()

    var isExporting by remember { mutableStateOf(false) }
    var exportResult by remember { mutableStateOf<CsvExportResult?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }

    val displayTransactions = if (currentStoreId != null) {
        val filtered = transactions.filter {
            it.storeId.equals(currentStoreId, ignoreCase = true) || it.storeId == "All Stores"
        }
        if (filtered.isNotEmpty()) filtered else transactions
    } else {
        transactions
    }

    val sdfDateOnly = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    val groupedTransactions = displayTransactions.groupBy { sdfDateOnly.format(Date(it.timestamp)) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Stock History", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (displayTransactions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.History,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No Stock History Yet",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Check in or check out items to view transaction records here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                groupedTransactions.forEach { (dateString, txs) ->
                    item {
                        Text(
                            text = dateString,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp, top = 8.dp)
                        )
                    }
                    items(txs, key = { it.id }) { tx ->
                        val roomItem = items.find { it.id == tx.itemId }
                        val invItem = allInventoryItems.find { it.item_code == tx.itemId || it.item_name == tx.itemId }
                        val itemName = roomItem?.name ?: invItem?.item_name ?: tx.itemId

                        val displayName = tx.scanned_by.ifBlank { tx.userId }
                        HistoryCard(
                            itemName = itemName,
                            itemCode = invItem?.item_code ?: roomItem?.id ?: "",
                            storeName = tx.storeId,
                            userName = displayName,
                            timestamp = tx.timestamp,
                            quantityChange = tx.quantityChange,
                            type = tx.type,
                            status = tx.status,
                            reason = tx.transaction_reason
                        )
                    }
                }
            }
        }
    }

    if (showExportDialog && exportResult != null) {
        val res = exportResult!!
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            icon = {
                Icon(
                    imageVector = if (res.success) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = if (res.success) Color(0xFF10B981) else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(44.dp)
                )
            },
            title = {
                Text(
                    text = if (res.success) "Stock History Exported!" else "Export Failed",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (res.success) {
                        Text(
                            text = "Full audit trail has been exported to CSV (item, reason, scanned_by, timestamp):",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("📄 File: ${res.fileName}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("📊 Logs Exported: ${res.itemCount} records", fontWeight = FontWeight.SemiBold, color = Color(0xFF059669))
                                Text("📁 Saved to: ${res.savedPathDescription}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        Text(
                            text = "Error: ${res.errorMessage ?: "Unknown error occurred"}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (res.success && res.shareIntent != null) {
                        Button(
                            onClick = {
                                try {
                                    context.startActivity(res.shareIntent)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                        ) {
                            Text("Open in Excel / Sheets", fontWeight = FontWeight.Bold)
                        }
                    }
                    TextButton(
                        onClick = { showExportDialog = false }
                    ) {
                        Text("Done")
                    }
                }
            }
        )
    }
}

@Composable
fun HistoryCard(
    itemName: String,
    itemCode: String,
    storeName: String,
    userName: String,
    timestamp: Long,
    quantityChange: Int,
    type: TransactionType,
    status: String = "",
    reason: String = ""
) {
    val badgeColor = when (type) {
        TransactionType.IN, TransactionType.CHECK_IN -> Color(0xFF10B981) // Green
        TransactionType.OUT, TransactionType.CHECK_OUT -> Color(0xFFEF4444) // Red
        TransactionType.RETURN -> Color(0xFF059669) // Emerald
        TransactionType.TRANSFER -> Color(0xFF8B5CF6) // Purple
        TransactionType.LOST -> Color(0xFFB91C1C) // Dark Red
        TransactionType.DAMAGE -> Color(0xFFEA580C) // Orange
        TransactionType.ADJUSTMENT -> Color(0xFF2563EB) // Blue
    }

    val badgeLabel = when (type) {
        TransactionType.CHECK_IN -> "IN"
        TransactionType.CHECK_OUT -> "OUT"
        else -> type.name
    }

    val isAddition = type in listOf(TransactionType.IN, TransactionType.CHECK_IN, TransactionType.RETURN)
    val isZero = type == TransactionType.ADJUSTMENT && quantityChange == 0
    val qtyDisplay = when {
        isZero -> "0"
        isAddition -> "+$quantityChange"
        type == TransactionType.ADJUSTMENT -> if (quantityChange > 0) "+$quantityChange" else "$quantityChange"
        else -> "-$quantityChange"
    }

    val sdfFull = SimpleDateFormat("MMMM dd, yyyy - HH:mm", Locale.getDefault())

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = itemName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (itemCode.isNotBlank() && itemCode != itemName) {
                        Text(
                            text = "Code: $itemCode",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = badgeColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = badgeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = badgeColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    if (status.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Text(
                                text = "Status: $status",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Dashed Line
            val dividerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            Canvas(modifier = Modifier.fillMaxWidth().height(1.dp)) {
                drawLine(
                    color = dividerColor,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = userName.take(1).uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Scanned by: $userName",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (reason.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Reason: $reason",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = qtyDisplay,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (isAddition) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                        contentDescription = null,
                        tint = badgeColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CalendarToday,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = sdfFull.format(Date(timestamp)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (storeName.isNotBlank()) {
                    Text(
                        text = storeName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

