package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.data.EntryResult
import com.example.data.InventoryConstants
import com.example.data.InventoryItem
import com.example.data.TransactionType
import com.example.viewmodel.InventoryViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemDetailScreen(
    itemCode: String,
    viewModel: InventoryViewModel,
    navController: NavController
) {
    LaunchedEffect(itemCode) {
        viewModel.loadItemDetail(itemCode)
    }

    val item by viewModel.selectedItemDetail.collectAsState()
    val isLoading by viewModel.isLoadingDetail.collectAsState()
    val transactions by viewModel.selectedItemTransactions.collectAsState()

    var showStatusDialog by remember { mutableStateOf(false) }
    var showTransactionDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Item Details", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.padding(end = 16.dp)
                    ) {
                        Text(
                            text = itemCode,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (item == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Inventory2,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Item Not Found ($itemCode)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "This item code does not exist in inventory_master.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(onClick = { navController.popBackStack() }) {
                            Text("Go Back")
                        }
                    }
                }
            } else {
                val currentItem = item!!
                val isAvailable = currentItem.available_quantity > 0
                val stockColor = if (currentItem.available_quantity > 5) Color(0xFF10B981) else if (currentItem.available_quantity > 0) Color(0xFFF59E0B) else Color(0xFFEF4444)
                val stockBg = stockColor.copy(alpha = 0.15f)

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Spacer(modifier = Modifier.height(8.dp))

                    // Primary Header Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(20.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Text(
                                        text = currentItem.asset_type.ifEmpty { currentItem.category.ifEmpty { "Asset" } },
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = stockBg
                                ) {
                                    Text(
                                        text = if (currentItem.available_quantity > 5) "In Stock (${currentItem.available_quantity})" else if (currentItem.available_quantity > 0) "Low Stock (${currentItem.available_quantity})" else "Out of Stock (0)",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = stockColor,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = currentItem.item_name.ifEmpty { "Unnamed Item" },
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            if (currentItem.specification.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = currentItem.specification,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Quantity Overview Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "${currentItem.available_quantity}",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = stockColor
                                )
                                Text(
                                    text = "Available Qty",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Divider(modifier = Modifier.height(40.dp).width(1.dp), color = MaterialTheme.colorScheme.outlineVariant)

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "${currentItem.total_quantity}",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Total Qty",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Divider(modifier = Modifier.height(40.dp).width(1.dp), color = MaterialTheme.colorScheme.outlineVariant)

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = currentItem.unit.ifEmpty { "Unit" },
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Unit Type",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Physical Location Section
                    Text(
                        text = "Physical Location",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            DetailRow(
                                icon = Icons.Default.Store,
                                label = "Storage Location (Room)",
                                value = currentItem.storage_location.ifEmpty { "Not specified" },
                                iconColor = MaterialTheme.colorScheme.primary
                            )
                            Divider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                            DetailRow(
                                icon = Icons.Default.Place,
                                label = "Specific Location / Rack",
                                value = currentItem.specific_location.ifEmpty { "Not specified" },
                                iconColor = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Item Specifications & Categorization
                    Text(
                        text = "Classification & Details",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            DetailRow(
                                icon = Icons.Default.Category,
                                label = "Category",
                                value = currentItem.category.ifEmpty { "General" },
                                iconColor = MaterialTheme.colorScheme.primary
                            )
                            if (currentItem.sub_category.isNotBlank()) {
                                Divider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                                DetailRow(
                                    icon = Icons.Default.Folder,
                                    label = "Sub-Category",
                                    value = currentItem.sub_category,
                                    iconColor = MaterialTheme.colorScheme.primary
                                )
                            }
                            Divider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                            DetailRow(
                                icon = Icons.Default.CheckCircleOutline,
                                label = "Status",
                                value = currentItem.status.ifEmpty { "Active" },
                                iconColor = MaterialTheme.colorScheme.secondary
                            )
                            if (currentItem.owner_pic.isNotBlank()) {
                                Divider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                                DetailRow(
                                    icon = Icons.Default.Person,
                                    label = "Owner / PIC",
                                    value = currentItem.owner_pic,
                                    iconColor = MaterialTheme.colorScheme.tertiary
                                )
                            }
                            if (currentItem.remarks.isNotBlank()) {
                                Divider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                                DetailRow(
                                    icon = Icons.Default.Notes,
                                    label = "Remarks",
                                    value = currentItem.remarks,
                                    iconColor = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Action Buttons: Edit Status & Log Exception / Transaction
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showStatusDialog = true },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Edit Status")
                        }

                        Button(
                            onClick = { showTransactionDialog = true },
                            modifier = Modifier.weight(1.3f).height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Log Exception")
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Transaction History Section
                    Text(
                        text = "Transaction History",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (transactions.isEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Default.History,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "No transactions recorded for this item yet",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            transactions.forEach { tx ->
                                val badgeColor = getTransactionTypeColor(tx.type)
                                val badgeLabel = getTransactionTypeLabel(tx.type)
                                val qtyDisplay = getQuantityDisplay(tx.type, tx.quantityChange)
                                val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
                                val userDisplay = tx.scanned_by.ifBlank { tx.userId }

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = badgeColor.copy(alpha = 0.15f),
                                                modifier = Modifier.padding(end = 12.dp)
                                            ) {
                                                Text(
                                                    text = badgeLabel,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = badgeColor,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }

                                            Column {
                                                Text(
                                                    text = "Scanned by: $userDisplay",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = sdf.format(Date(tx.timestamp)),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                if (tx.status.isNotBlank()) {
                                                    Surface(
                                                        shape = RoundedCornerShape(6.dp),
                                                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                                                        modifier = Modifier.padding(top = 4.dp)
                                                    ) {
                                                        Text(
                                                            text = "Status: ${tx.status}",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            fontWeight = FontWeight.Medium,
                                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                                if (tx.transaction_reason.isNotBlank()) {
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Text(
                                                        text = "Reason: ${tx.transaction_reason}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                }
                                            }
                                        }

                                        Text(
                                            text = qtyDisplay,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = badgeColor
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                }

                // ---- Update Status Dialog ----
                if (showStatusDialog) {
                    var selectedStatus by remember { mutableStateOf(currentItem.status.ifEmpty { "Active" }) }
                    var statusExpanded by remember { mutableStateOf(false) }

                    AlertDialog(
                        onDismissRequest = { showStatusDialog = false },
                        title = { Text("Update Item Status", fontWeight = FontWeight.Bold) },
                        text = {
                            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                Text(
                                    text = "Select official status for ${currentItem.item_name}:",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(16.dp))

                                ExposedDropdownMenuBox(
                                    expanded = statusExpanded,
                                    onExpandedChange = { statusExpanded = !statusExpanded }
                                ) {
                                    OutlinedTextField(
                                        value = selectedStatus,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("Status") },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = statusExpanded) },
                                        modifier = Modifier.menuAnchor().fillMaxWidth()
                                    )
                                    ExposedDropdownMenu(
                                        expanded = statusExpanded,
                                        onDismissRequest = { statusExpanded = false }
                                    ) {
                                        InventoryConstants.OFFICIAL_STATUSES.forEach { statusOption ->
                                            DropdownMenuItem(
                                                text = { Text(statusOption) },
                                                onClick = {
                                                    selectedStatus = statusOption
                                                    statusExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showStatusDialog = false
                                    viewModel.updateItemStatus(itemCode, selectedStatus) { success ->
                                        scope.launch {
                                            if (success) {
                                                snackbarHostState.showSnackbar("Status updated to '$selectedStatus'")
                                            } else {
                                                snackbarHostState.showSnackbar("Failed to update status")
                                            }
                                        }
                                    }
                                }
                            ) {
                                Text("Update")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showStatusDialog = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }

                // ---- Log Transaction / Exception Dialog ----
                if (showTransactionDialog) {
                    var selectedType by remember { mutableStateOf(TransactionType.OUT) }
                    var typeExpanded by remember { mutableStateOf(false) }
                    var quantity by remember { mutableStateOf(1) }
                    var selectedStatus by remember { mutableStateOf(currentItem.status.ifEmpty { "Active" }) }
                    var statusExpanded by remember { mutableStateOf(false) }

                    AlertDialog(
                        onDismissRequest = { showTransactionDialog = false },
                        title = { Text("Log Transaction / Exception", fontWeight = FontWeight.Bold) },
                        text = {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Text(
                                    text = "Record an inventory movement or exception with full audit tracking.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                // Transaction Type Dropdown
                                ExposedDropdownMenuBox(
                                    expanded = typeExpanded,
                                    onExpandedChange = { typeExpanded = !typeExpanded }
                                ) {
                                    OutlinedTextField(
                                        value = selectedType.name,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("Transaction Type") },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                                        modifier = Modifier.menuAnchor().fillMaxWidth()
                                    )
                                    ExposedDropdownMenu(
                                        expanded = typeExpanded,
                                        onDismissRequest = { typeExpanded = false }
                                    ) {
                                        InventoryConstants.OFFICIAL_TRANSACTION_TYPES.forEach { t ->
                                            DropdownMenuItem(
                                                text = { Text(t.name) },
                                                onClick = {
                                                    selectedType = t
                                                    typeExpanded = false
                                                    // Auto-suggest status when appropriate
                                                    when (t) {
                                                        TransactionType.LOST -> selectedStatus = "Lost"
                                                        TransactionType.DAMAGE -> selectedStatus = "Under Maintenance"
                                                        TransactionType.RETURN -> selectedStatus = "Active"
                                                        else -> {}
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }

                                // Quantity Stepper
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Quantity", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = { if (quantity > 0) quantity-- }) {
                                            Icon(Icons.Default.Remove, contentDescription = "Decrease")
                                        }
                                        Text(
                                            text = "$quantity",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 12.dp)
                                        )
                                        IconButton(onClick = { quantity++ }) {
                                            Icon(Icons.Default.Add, contentDescription = "Increase")
                                        }
                                    }
                                }

                                // Resulting Status Dropdown
                                ExposedDropdownMenuBox(
                                    expanded = statusExpanded,
                                    onExpandedChange = { statusExpanded = !statusExpanded }
                                ) {
                                    OutlinedTextField(
                                        value = selectedStatus,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("Resulting Status") },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = statusExpanded) },
                                        modifier = Modifier.menuAnchor().fillMaxWidth()
                                    )
                                    ExposedDropdownMenu(
                                        expanded = statusExpanded,
                                        onDismissRequest = { statusExpanded = false }
                                    ) {
                                        InventoryConstants.OFFICIAL_STATUSES.forEach { s ->
                                            DropdownMenuItem(
                                                text = { Text(s) },
                                                onClick = {
                                                    selectedStatus = s
                                                    statusExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showTransactionDialog = false
                                    viewModel.manualEntry(itemCode, quantity, selectedType, selectedStatus) { result ->
                                        scope.launch {
                                            when (result.status) {
                                                EntryResult.SUCCESS -> {
                                                    snackbarHostState.showSnackbar("Transaction logged successfully")
                                                    viewModel.loadItemDetail(itemCode)
                                                }
                                                EntryResult.WRONG_STORE -> {
                                                    snackbarHostState.showSnackbar("Wrong store. Item belongs to: ${result.correctStore}")
                                                }
                                                EntryResult.INSUFFICIENT_STOCK -> {
                                                    snackbarHostState.showSnackbar("Insufficient stock for this operation")
                                                }
                                                EntryResult.ITEM_NOT_FOUND -> {
                                                    snackbarHostState.showSnackbar("Item not found in catalog")
                                                }
                                            }
                                        }
                                    }
                                }
                            ) {
                                Text("Log Record")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showTransactionDialog = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }
            }
        }
    }
}

fun getTransactionTypeColor(type: TransactionType): Color {
    return when (type) {
        TransactionType.IN, TransactionType.CHECK_IN -> Color(0xFF10B981) // Green
        TransactionType.OUT, TransactionType.CHECK_OUT -> Color(0xFFEF4444) // Red
        TransactionType.RETURN -> Color(0xFF059669) // Emerald
        TransactionType.TRANSFER -> Color(0xFF8B5CF6) // Purple
        TransactionType.LOST -> Color(0xFFB91C1C) // Dark Red
        TransactionType.DAMAGE -> Color(0xFFEA580C) // Orange
        TransactionType.ADJUSTMENT -> Color(0xFF2563EB) // Blue
    }
}

fun getTransactionTypeLabel(type: TransactionType): String {
    return when (type) {
        TransactionType.CHECK_IN -> "IN"
        TransactionType.CHECK_OUT -> "OUT"
        else -> type.name
    }
}

fun getQuantityDisplay(type: TransactionType, quantity: Int): String {
    return when (type) {
        TransactionType.IN, TransactionType.CHECK_IN, TransactionType.RETURN -> "+$quantity"
        TransactionType.OUT, TransactionType.CHECK_OUT, TransactionType.TRANSFER, TransactionType.LOST, TransactionType.DAMAGE -> "-$quantity"
        TransactionType.ADJUSTMENT -> if (quantity == 0) "0" else if (quantity > 0) "+$quantity" else "$quantity"
    }
}

@Composable
fun DetailRow(
    icon: ImageVector,
    label: String,
    value: String,
    iconColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = iconColor.copy(alpha = 0.12f),
            modifier = Modifier.size(36.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
