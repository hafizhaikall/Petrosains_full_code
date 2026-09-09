package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.example.data.EntryResult
import com.example.data.InventoryConstants
import com.example.data.TransactionType
import com.example.viewmodel.InventoryViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualEntryScreen(viewModel: InventoryViewModel, navController: NavController) {
    var itemCode by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf(1) }
    var transactionType by remember { mutableStateOf(TransactionType.OUT) }
    var typeExpanded by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Active") }
    var statusExpanded by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manual Entry") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = itemCode,
                onValueChange = { itemCode = it; errorMessage = null },
                label = { Text("Item Code") },
                placeholder = { Text("e.g. E001") },
                leadingIcon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                isError = errorMessage != null,
                singleLine = true
            )
            if (errorMessage != null) {
                Text(errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            // Transaction Type Dropdown
            ExposedDropdownMenuBox(
                expanded = typeExpanded,
                onExpandedChange = { typeExpanded = !typeExpanded }
            ) {
                OutlinedTextField(
                    value = transactionType.name,
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
                                transactionType = t
                                typeExpanded = false
                                when (t) {
                                    TransactionType.LOST -> status = "Lost"
                                    TransactionType.DAMAGE -> status = "Under Maintenance"
                                    TransactionType.RETURN -> status = "Active"
                                    else -> {}
                                }
                            }
                        )
                    }
                }
            }

            // Status Dropdown
            ExposedDropdownMenuBox(
                expanded = statusExpanded,
                onExpandedChange = { statusExpanded = !statusExpanded }
            ) {
                OutlinedTextField(
                    value = status,
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
                    InventoryConstants.OFFICIAL_STATUSES.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s) },
                            onClick = {
                                status = s
                                statusExpanded = false
                            }
                        )
                    }
                }
            }
            
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Quantity", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { if (quantity > 1) quantity-- }) {
                            Icon(Icons.Default.Remove, contentDescription = "Decrease")
                        }
                        Text("$quantity", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
                        IconButton(onClick = { quantity++ }) {
                            Icon(Icons.Default.Add, contentDescription = "Increase")
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = {
                    val code = itemCode.trim()
                    if (code.isBlank()) {
                        errorMessage = "Item Code is required"
                        return@Button
                    }
                    viewModel.manualEntry(code, quantity, transactionType, status) { result ->
                        when (result.status) {
                            EntryResult.SUCCESS -> {
                                navController.popBackStack()
                            }
                            EntryResult.WRONG_STORE -> {
                                val msg = "Cannot update inventory for this item. Wrong store. Please go to the correct store: ${result.correctStore}"
                                errorMessage = msg
                                scope.launch {
                                    snackbarHostState.showSnackbar(msg)
                                }
                            }
                            EntryResult.ITEM_NOT_FOUND -> {
                                errorMessage = "Item Not Found"
                                scope.launch {
                                    snackbarHostState.showSnackbar("Item Not Found")
                                }
                            }
                            EntryResult.INSUFFICIENT_STOCK -> {
                                errorMessage = "Insufficient inventory"
                                scope.launch {
                                    snackbarHostState.showSnackbar("Insufficient inventory")
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("Confirm Transaction", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
