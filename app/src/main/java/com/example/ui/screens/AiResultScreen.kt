package com.example.ui.screens

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.data.TransactionType
import com.example.viewmodel.AiDetectionItem
import com.example.viewmodel.InventoryViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiResultScreen(viewModel: InventoryViewModel, navController: NavController) {
    // Completely pause background camera scanner while viewing/confirming AI scan
    DisposableEffect(Unit) {
        viewModel.pauseScanner()
        onDispose {
            viewModel.resumeScanner()
        }
    }

    val initialItems = viewModel.aiScanResult.collectAsState().value ?: emptyList()
    var editableItems by remember { mutableStateOf(initialItems.map { it.copy() }) }
    var transactionType by remember { mutableStateOf(TransactionType.CHECK_IN) }

    val checkedOutMap by viewModel.checkedOutQuantities.collectAsState()
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    // Mathematical validation:
    // (Damaged + Missing + Disposed) == (checkedOutQuantity - selectedQuantity)
    val partialReturnValidation = remember(editableItems, checkedOutMap, transactionType) {
        if (transactionType != TransactionType.CHECK_IN) {
            Triple(true, 0, 0)
        } else {
            var allValid = true
            var totalUnreturned = 0
            var totalAssigned = 0
            for (item in editableItems) {
                val co = checkedOutMap[item.itemName]
                    ?: checkedOutMap[item.itemName.trim()]
                    ?: checkedOutMap[item.itemName.lowercase().trim()]
                    ?: item.activeCheckedOutQuantity
                if (co > 0 && item.quantity < co) {
                    val unreturned = co - item.quantity
                    val assigned = item.damagedQuantity + item.missingQuantity + item.disposedQuantity
                    totalUnreturned += unreturned
                    totalAssigned += assigned
                    if (assigned != unreturned) {
                        allValid = false
                    }
                }
            }
            Triple(allValid, totalUnreturned, totalAssigned)
        }
    }
    val isMathValid = partialReturnValidation.first
    val totalUnreturnedItems = partialReturnValidation.second
    val totalAssignedItems = partialReturnValidation.third
    val remainingToAssign = totalUnreturnedItems - totalAssignedItems
    val hasPartialReturns = (totalUnreturnedItems > 0)

    // Synchronize initialItems when scan completes
    LaunchedEffect(initialItems) {
        if (initialItems.isNotEmpty() && editableItems.isEmpty()) {
            editableItems = initialItems.map { it.copy() }
        }
        viewModel.loadCheckedOutQuantities(initialItems.map { it.itemName })
    }

    // Query active checked-out quantities for all current items
    LaunchedEffect(transactionType, editableItems.map { it.itemName }) {
        if (editableItems.isNotEmpty()) {
            viewModel.loadCheckedOutQuantities(editableItems.map { it.itemName })
        }
    }

    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Inventory Notice", fontWeight = FontWeight.Bold) },
            text = { Text(errorMessage ?: "") },
            confirmButton = {
                TextButton(onClick = { errorMessage = null }) {
                    Text("OK")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Confirm AI Scan") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.clearAiScan(); navController.popBackStack() }) {
                        Icon(Icons.Default.Close, contentDescription = "Discard")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    // Dynamic Helper Text for Partial Return Guidance
                    if (transactionType == TransactionType.CHECK_IN && hasPartialReturns) {
                        val helperMsg = when {
                            remainingToAssign > 0 -> "⚠️ Please assign statuses to the remaining $remainingToAssign item(s)"
                            remainingToAssign < 0 -> "⚠️ Assigned exceptions exceed unreturned by ${-remainingToAssign} item(s)"
                            else -> "✓ All unreturned items accounted for"
                        }
                        val helperColor = when {
                            remainingToAssign == 0 -> Color(0xFF10B981)
                            remainingToAssign > 0 -> Color(0xFFF59E0B)
                            else -> MaterialTheme.colorScheme.error
                        }
                        Text(
                            text = helperMsg,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = helperColor,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SegmentedButton(
                            selected = transactionType,
                            onSelected = { newType ->
                                transactionType = newType
                                if (newType == TransactionType.CHECK_IN) {
                                    viewModel.loadCheckedOutQuantities(editableItems.map { it.itemName })
                                }
                            }
                        )

                        Button(
                            onClick = {
                                val itemsToSave = editableItems.map { itm ->
                                    val co = checkedOutMap[itm.itemName]
                                        ?: checkedOutMap[itm.itemName.trim()]
                                        ?: checkedOutMap[itm.itemName.lowercase().trim()]
                                        ?: itm.activeCheckedOutQuantity
                                    itm.copy(activeCheckedOutQuantity = co)
                                }
                                viewModel.confirmAiScan(itemsToSave, transactionType, context = context) { result ->
                                    if (result.status == com.example.data.EntryResult.SUCCESS) {
                                        navController.popBackStack()
                                    } else {
                                        errorMessage = result.message.ifEmpty {
                                            "Cannot update inventory for this item. Wrong store. Please go to the correct store: ${result.correctStore}"
                                        }
                                    }
                                }
                            },
                            enabled = editableItems.isNotEmpty() && (transactionType != TransactionType.CHECK_IN || isMathValid)
                        ) {
                            Text("Confirm & Save")
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (editableItems.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("No objects detected!", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "The AI couldn't find any inventory items in this photo. If you are using the web preview emulator, the camera only shows a simulated virtual room.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            } else {
                item {
                    Text(
                        if (transactionType == TransactionType.CHECK_OUT)
                            "Review items to check out, set expected return dates, and attach optional reasons."
                        else
                            "Review items to check in. Partial returns are automatically detected against your active checkouts.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            itemsIndexed(
                items = editableItems,
                key = { index, item -> "${item.itemName}_${item.itemCode}_$index" }
            ) { index, item ->
                val checkedOutQty = checkedOutMap[item.itemName]
                    ?: checkedOutMap[item.itemName.trim()]
                    ?: checkedOutMap[item.itemName.lowercase().trim()]
                    ?: item.activeCheckedOutQuantity

                EditableItemCard(
                    item = item,
                    transactionType = transactionType,
                    checkedOutQuantity = checkedOutQty,
                    onQuantityChange = { newQty ->
                        editableItems = editableItems.mapIndexed { i, currentItem ->
                            if (i == index) currentItem.copy(quantity = newQty) else currentItem
                        }
                    },
                    onNameChange = { newName ->
                        editableItems = editableItems.mapIndexed { i, currentItem ->
                            if (i == index) currentItem.copy(itemName = newName) else currentItem
                        }
                        viewModel.loadCheckedOutQuantity(newName)
                    },
                    onReasonChange = { newReason ->
                        editableItems = editableItems.mapIndexed { i, currentItem ->
                            if (i == index) currentItem.copy(reason = newReason) else currentItem
                        }
                    },
                    onExpectedDateChange = { newDate ->
                        editableItems = editableItems.mapIndexed { i, currentItem ->
                            if (i == index) {
                                currentItem.copy(
                                    expectedReturnDate = newDate,
                                    itemName = currentItem.itemName,
                                    itemCode = currentItem.itemCode,
                                    confidence = currentItem.confidence
                                )
                            } else {
                                currentItem
                            }
                        }
                        viewModel.updateExpectedReturnDate(index, newDate)
                    },
                    onDamagedChange = { newDamaged ->
                        editableItems = editableItems.mapIndexed { i, currentItem ->
                            if (i == index) currentItem.copy(damagedQuantity = newDamaged) else currentItem
                        }
                    },
                    onMissingChange = { newMissing ->
                        editableItems = editableItems.mapIndexed { i, currentItem ->
                            if (i == index) currentItem.copy(missingQuantity = newMissing) else currentItem
                        }
                    },
                    onDisposedChange = { newDisposed ->
                        editableItems = editableItems.mapIndexed { i, currentItem ->
                            if (i == index) currentItem.copy(disposedQuantity = newDisposed) else currentItem
                        }
                    },
                    onDelete = {
                        val newList = editableItems.toMutableList()
                        if (index in newList.indices) {
                            newList.removeAt(index)
                            editableItems = newList
                        }
                    }
                )
            }
            item {
                OutlinedButton(
                    onClick = {
                        val newItem = AiDetectionItem("New Item", 1, "", 0f)
                        val newList = editableItems + newItem
                        editableItems = newList
                        viewModel.loadCheckedOutQuantity("New Item")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Item")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add Item Manually")
                }
            }
        }
    }
}

@Composable
fun SegmentedButton(selected: TransactionType, onSelected: (TransactionType) -> Unit) {
    Row(
        modifier = Modifier
            .width(140.dp)
            .height(40.dp)
    ) {
        Surface(
            modifier = Modifier.weight(1f),
            shape = MaterialTheme.shapes.small,
            color = if (selected == TransactionType.CHECK_IN) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            onClick = { onSelected(TransactionType.CHECK_IN) }
        ) {
            Box(contentAlignment = Alignment.Center) { Text("IN") }
        }
        Surface(
            modifier = Modifier.weight(1f),
            shape = MaterialTheme.shapes.small,
            color = if (selected == TransactionType.CHECK_OUT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surface,
            onClick = { onSelected(TransactionType.CHECK_OUT) }
        ) {
            Box(contentAlignment = Alignment.Center) { Text("OUT") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditableItemCard(
    item: AiDetectionItem,
    transactionType: TransactionType,
    checkedOutQuantity: Int,
    onQuantityChange: (Int) -> Unit,
    onNameChange: (String) -> Unit,
    onReasonChange: (String?) -> Unit,
    onExpectedDateChange: (Long?) -> Unit,
    onDamagedChange: (Int) -> Unit,
    onMissingChange: (Int) -> Unit,
    onDisposedChange: (Int) -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var isReasonExpanded by remember { mutableStateOf(!item.reason.isNullOrBlank()) }

    // rememberSaveable ensures itemName and itemCode survive screen recompositions (such as DatePicker dialog closing)
    var savedItemName by rememberSaveable(key = "itemName_${item.itemName}_$checkedOutQuantity") {
        mutableStateOf(item.itemName)
    }
    var savedItemCode by rememberSaveable(key = "itemCode_${item.itemName}_$checkedOutQuantity") {
        mutableStateOf(item.itemCode)
    }

    // Keep state updated if item externally changed
    LaunchedEffect(item.itemName) {
        if (savedItemName != item.itemName && item.itemName.isNotBlank()) {
            savedItemName = item.itemName
        }
    }
    LaunchedEffect(item.itemCode) {
        if (savedItemCode != item.itemCode) {
            savedItemCode = item.itemCode
        }
    }

    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }

    // DatePicker setup for OUT mode - dynamically created on show to prevent stale callbacks and preserve state
    val onExpectedDateChangeState = rememberUpdatedState(onExpectedDateChange)
    val currentItemState = rememberUpdatedState(item)

    val showDatePicker = {
        val calendar = Calendar.getInstance()
        val existingDate = currentItemState.value.expectedReturnDate
        if (existingDate != null && existingDate > 0L) {
            calendar.timeInMillis = existingDate
        } else {
            calendar.timeInMillis = System.currentTimeMillis()
        }
        val dialog = DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val picked = Calendar.getInstance().apply {
                    set(year, month, dayOfMonth, 23, 59, 59)
                }
                onExpectedDateChangeState.value(picked.timeInMillis)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
        dialog.datePicker.minDate = System.currentTimeMillis()
        dialog.show()
    }


    // Strict UI Condition: isCheckInMode == true AND selectedQuantity < checkedOutQuantity
    val isCheckInMode = (transactionType == TransactionType.CHECK_IN)
    val selectedQuantity = item.quantity
    val showUnreturnedSection = isCheckInMode && (selectedQuantity < checkedOutQuantity)
    val unreturnedQty = (checkedOutQuantity - selectedQuantity).coerceAtLeast(0)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Top Row: Item Name + Delete Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = savedItemName,
                    onValueChange = { newName ->
                        savedItemName = newName
                        onNameChange(newName)
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Item Name") }
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }

            if (savedItemCode.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = "Item Code: $savedItemCode",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            // Checked-out status badge in CHECK_IN mode
            if (isCheckInMode && checkedOutQuantity > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "You actively have $checkedOutQuantity checked out",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Quantity & Confidence Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Confidence: ${item.confidence.toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.confidence > 80) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (item.quantity > 0) onQuantityChange(item.quantity - 1) }) {
                        Icon(Icons.Default.Remove, contentDescription = "Decrease")
                    }
                    Text(
                        "${item.quantity}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    IconButton(onClick = { onQuantityChange(item.quantity + 1) }) {
                        Icon(Icons.Default.Add, contentDescription = "Increase")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Expandable Per-Item Reason
            if (!isReasonExpanded) {
                TextButton(
                    onClick = { isReasonExpanded = true },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Add Reason",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Add Reason (Optional)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else {
                OutlinedTextField(
                    value = item.reason ?: "",
                    onValueChange = onReasonChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    label = { Text("Reason (Optional)") },
                    placeholder = { Text("e.g., Replacement, project X, returned from lab...") },
                    singleLine = false,
                    maxLines = 2,
                    trailingIcon = {
                        IconButton(onClick = {
                            onReasonChange(null)
                            isReasonExpanded = false
                        }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close reason",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                )
            }

            // =================================================================
            // CHECK-OUT (OUT) MODE: Expected Return Date Button & DatePicker
            // =================================================================
            if (transactionType == TransactionType.CHECK_OUT) {
                Spacer(modifier = Modifier.height(8.dp))
                if (item.expectedReturnDate == null || item.expectedReturnDate == 0L) {
                    OutlinedButton(
                        onClick = showDatePicker,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.DateRange,
                            contentDescription = "Select Expected Return Date",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Select Expected Return Date")
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Event,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        "Expected Return Date",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        dateFormat.format(Date(item.expectedReturnDate!!)),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            Row {
                                IconButton(onClick = showDatePicker, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.EditCalendar, contentDescription = "Change date", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                }
                                IconButton(onClick = { onExpectedDateChange(null) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear date", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }

            // =================================================================
            // CHECK-IN (IN) MODE: Partial Return UI & Exception Steppers
            // Strict Condition: isCheckInMode == true AND selectedQuantity < checkedOutQuantity
            // =================================================================
            if (showUnreturnedSection) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(8.dp))

                val totalAssigned = item.damagedQuantity + item.missingQuantity + item.disposedQuantity
                val remainingUnassigned = unreturnedQty - totalAssigned

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Unreturned Items Status",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "Returning $selectedQuantity of $checkedOutQuantity checked out ($unreturnedQty unreturned)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (remainingUnassigned == 0) Color(0xFFD1FAE5) else Color(0xFFFEF3C7)
                    ) {
                        Text(
                            text = if (remainingUnassigned == 0) "Complete" else "$remainingUnassigned unassigned",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (remainingUnassigned == 0) Color(0xFF065F46) else Color(0xFF92400E),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 1. Damaged & Under Maintenance
                ExceptionStepperRow(
                    title = "Damaged & Under Maintenance",
                    subtitle = "Flagged for repairs & tracking",
                    badgeColor = Color(0xFFF59E0B),
                    icon = Icons.Default.Build,
                    quantity = item.damagedQuantity,
                    onQuantityChange = onDamagedChange
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 2. Missing & Lost
                ExceptionStepperRow(
                    title = "Missing & Lost",
                    subtitle = "Unaccounted during custody",
                    badgeColor = Color(0xFFEF4444),
                    icon = Icons.Default.SearchOff,
                    quantity = item.missingQuantity,
                    onQuantityChange = onMissingChange
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 3. Disposed
                ExceptionStepperRow(
                    title = "Disposed",
                    subtitle = "Permanently written-off",
                    badgeColor = Color(0xFF6B7280),
                    icon = Icons.Default.Delete,
                    quantity = item.disposedQuantity,
                    onQuantityChange = onDisposedChange
                )
            }
        }
    }
}

@Composable
fun ExceptionStepperRow(
    title: String,
    subtitle: String,
    badgeColor: Color,
    icon: ImageVector,
    quantity: Int,
    onQuantityChange: (Int) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    shape = CircleShape,
                    color = badgeColor.copy(alpha = 0.15f),
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = badgeColor,
                        modifier = Modifier.padding(8.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Stepper: [-] [count] [+]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = { if (quantity > 0) onQuantityChange(quantity - 1) },
                    enabled = quantity > 0,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Remove,
                        contentDescription = "Decrease $title",
                        tint = if (quantity > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                        modifier = Modifier.size(16.dp)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (quantity > 0) badgeColor.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.widthIn(min = 36.dp).height(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 6.dp)) {
                        Text(
                            text = quantity.toString(),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (quantity > 0) badgeColor else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                IconButton(
                    onClick = { onQuantityChange(quantity + 1) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Increase $title",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
