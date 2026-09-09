package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.data.InventoryItem
import com.example.ui.Screen
import com.example.ui.components.CopilotChatSheet
import com.example.ui.formatLastSyncTime
import com.example.viewmodel.CopilotViewModel
import com.example.viewmodel.InventoryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: InventoryViewModel,
    copilotViewModel: CopilotViewModel,
    navController: NavController
) {
    val storeId by viewModel.storeId.collectAsState()
    val username by viewModel.username.collectAsState()
    val stores by viewModel.stores.collectAsState()
    val storeItems by viewModel.storeInventoryItems.collectAsState()  // Real inventory_master data
    val isSyncing by viewModel.isSyncing.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()
    val pendingSyncCount by viewModel.pendingSyncCount.collectAsState()
    val lastSyncTime by viewModel.lastSyncTime.collectAsState()
    val isChatOpen by copilotViewModel.isChatOpen.collectAsState()
    val activeExceptions by viewModel.activeExceptions.collectAsState()

    val currentStore = stores.find { it.id == storeId }

    val totalItems = storeItems.sumOf { it.available_quantity }
    val lowStockItems = remember(storeItems) { storeItems.filter { it.available_quantity in 1..5 } }
    val outOfStockItems = remember(storeItems) { storeItems.filter { it.available_quantity == 0 } }
    val lowStockCount = lowStockItems.size
    val outOfStockCount = outOfStockItems.size

    // Active stock alert filter: "ALL", "LOW_STOCK", "OUT_OF_STOCK"
    var activeStockFilter by remember { mutableStateOf("ALL") }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { copilotViewModel.openChat() },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
                icon = {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "Ask A.R.I.F AI Copilot",
                        modifier = Modifier.size(20.dp)
                    )
                },
                text = {
                    Text(
                        text = "Ask A.R.I.F",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            
            // Custom Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.background,
                        border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Store, 
                            contentDescription = "Store", 
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.clickable { navController.navigate(Screen.StoreSelection) }) {
                        Text(
                            currentStore?.name ?: "No Store Selected", 
                            fontWeight = FontWeight.Bold, 
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            username?.ifBlank { "ADMIN" } ?: "ADMIN", 
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.size(56.dp),
                    shadowElevation = 2.dp
                ) {
                    IconButton(onClick = { viewModel.syncNow() }) {
                        Icon(Icons.Default.Notifications, contentDescription = "Alerts", tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Search Bar
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().height(56.dp).clickable { navController.navigate(Screen.Search) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Search...", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            SyncStatusBanner(isOnline = isOnline, isSyncing = isSyncing, pendingCount = pendingSyncCount, lastSyncTime = lastSyncTime)
            Spacer(modifier = Modifier.height(16.dp))

            // Stats Row (3 items with clickable stock filters)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    title = "Out of stock",
                    value = outOfStockCount.toString(),
                    icon = Icons.Default.ShoppingBasket,
                    isSelected = activeStockFilter == "OUT_OF_STOCK",
                    onClick = { activeStockFilter = if (activeStockFilter == "OUT_OF_STOCK") "ALL" else "OUT_OF_STOCK" },
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "Low stock",
                    value = lowStockCount.toString(),
                    icon = Icons.AutoMirrored.Filled.TrendingDown,
                    isSelected = activeStockFilter == "LOW_STOCK",
                    onClick = { activeStockFilter = if (activeStockFilter == "LOW_STOCK") "ALL" else "LOW_STOCK" },
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "Total items",
                    value = totalItems.toString(),
                    icon = Icons.Default.Inventory,
                    isSelected = false,
                    onClick = { navController.navigate(Screen.Search) },
                    modifier = Modifier.weight(1f)
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))

            // --- Stock Alerts Lists (Low Stock & Out of Stock) ---
            val alertDisplayList = remember(activeStockFilter, lowStockItems, outOfStockItems) {
                when (activeStockFilter) {
                    "LOW_STOCK" -> lowStockItems
                    "OUT_OF_STOCK" -> outOfStockItems
                    else -> (outOfStockItems + lowStockItems).distinctBy { it.item_code }
                }
            }

            if (alertDisplayList.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = when (activeStockFilter) {
                            "LOW_STOCK" -> "Low Stock Items (${alertDisplayList.size})"
                            "OUT_OF_STOCK" -> "Out of Stock Items (${alertDisplayList.size})"
                            else -> "Stock Alerts (${alertDisplayList.size})"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = activeStockFilter == "ALL",
                            onClick = { activeStockFilter = "ALL" },
                            label = { Text("All (${outOfStockCount + lowStockCount})", fontSize = 11.sp) },
                            shape = RoundedCornerShape(8.dp)
                        )
                        FilterChip(
                            selected = activeStockFilter == "LOW_STOCK",
                            onClick = { activeStockFilter = "LOW_STOCK" },
                            label = { Text("Low ($lowStockCount)", fontSize = 11.sp) },
                            shape = RoundedCornerShape(8.dp)
                        )
                        FilterChip(
                            selected = activeStockFilter == "OUT_OF_STOCK",
                            onClick = { activeStockFilter = "OUT_OF_STOCK" },
                            label = { Text("Out ($outOfStockCount)", fontSize = 11.sp) },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // List of Low Stock and Out of Stock cards with click listener
                alertDisplayList.take(6).forEach { item ->
                    StockAlertItemCard(
                        item = item,
                        onClick = {
                            navController.navigate(Screen.ItemDetail(item.item_code))
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Spacer(modifier = Modifier.height(20.dp))
            }
            
            // Quick Actions: AI Scan & Manual (Single 2-column balanced row)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ActionCard(
                    action = ActionItem("AI Scan", Icons.Default.CameraAlt, Screen.CameraScan, MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f)
                ) {
                    navController.navigate(Screen.CameraScan)
                }
                ActionCard(
                    action = ActionItem("Manual", Icons.Default.Edit, Screen.ManualEntry, MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f)
                ) {
                    navController.navigate(Screen.ManualEntry)
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))

            // Issue Tracker / Exceptions Banner
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (activeExceptions.isNotEmpty()) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surface,
                border = if (activeExceptions.isNotEmpty()) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f)) else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { navController.navigate(Screen.IssueTracker) },
                shadowElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (activeExceptions.isNotEmpty()) Color(0xFFEF4444) else MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = if (activeExceptions.isNotEmpty()) Icons.Default.Warning else Icons.Default.CheckCircle,
                            contentDescription = "Exceptions",
                            tint = if (activeExceptions.isNotEmpty()) Color.White else MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Issue Tracker",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (activeExceptions.isNotEmpty()) {
                                "${activeExceptions.size} unresolved item${if (activeExceptions.size > 1) "s" else ""} (Missing / Damaged)"
                            } else {
                                "No unresolved issues. All items accounted for."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (activeExceptions.isNotEmpty()) Color(0xFFF87171) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (activeExceptions.isNotEmpty()) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFFEF4444)
                        ) {
                            Text(
                                text = "${activeExceptions.size}",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Navigate to Issue Tracker",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Recent Transactions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                Text("View All", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { navController.navigate(Screen.History) })
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            val recentTransactions = viewModel.transactions.collectAsState().value.take(3)
            if (recentTransactions.isEmpty()) {
                Text("No recent transactions", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                recentTransactions.forEach { tx ->
                    val isCheckIn = tx.type == com.example.data.TransactionType.CHECK_IN
                    val iconColor = if (isCheckIn) Color(0xFF10B981) else Color(0xFFEF4444)
                    val bgColor = if (isCheckIn) Color(0xFFD1FAE5) else Color(0xFFFFE4E6)
                    val icon = if (isCheckIn) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward
                    
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = bgColor,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.padding(12.dp))
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(tx.itemId, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                Text(java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault()).format(java.util.Date(tx.timestamp)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Surface(shape = RoundedCornerShape(4.dp), color = bgColor) {
                                    Text(if (isCheckIn) "IN" else "OUT", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = iconColor)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(if (isCheckIn) "+${tx.quantityChange}" else "-${tx.quantityChange}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = iconColor)
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(96.dp)) // Padding for bottom nav
        }
    }

    if (isChatOpen) {
        CopilotChatSheet(
            viewModel = copilotViewModel,
            onDismiss = { copilotViewModel.closeChat() }
        )
    }
}

@Composable
fun StockAlertItemCard(
    item: InventoryItem,
    onClick: () -> Unit
) {
    val isOutOfStock = item.available_quantity == 0
    val stockColor = if (isOutOfStock) Color(0xFFEF4444) else Color(0xFFF59E0B)
    val stockBg = stockColor.copy(alpha = 0.15f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
            ) {
                Text(
                    text = item.item_code.ifEmpty { "CODE" },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.item_name.ifEmpty { "Unnamed Item" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                if (item.specific_location.isNotBlank() || item.storage_location.isNotBlank()) {
                    Text(
                        text = "${item.storage_location} • ${item.specific_location}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Surface(
                shape = RoundedCornerShape(6.dp),
                color = stockBg
            ) {
                Text(
                    text = if (isOutOfStock) "Out (0)" else "Low (${item.available_quantity})",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = stockColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "View Details",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
fun StatCard(
    title: String,
    value: String,
    icon: ImageVector,
    isSelected: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .aspectRatio(0.85f)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface
        ),
        shape = MaterialTheme.shapes.large,
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column {
                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(4.dp))
                Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun ActionCard(action: ActionItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1.5f)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            val bgColor = if (action.title == "Incoming") Color(0xFFFFE4E6) else if (action.title == "Outgoing") Color(0xFFD1FAE5) else MaterialTheme.colorScheme.primaryContainer
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = bgColor,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(action.icon, contentDescription = action.title, modifier = Modifier.padding(10.dp), tint = action.tintColor)
            }
            Text(action.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

data class ActionItem(val title: String, val icon: ImageVector, val screen: Screen, val tintColor: Color)

@Composable
fun SyncStatusBanner(isOnline: Boolean, isSyncing: Boolean, pendingCount: Int, lastSyncTime: Long = 0L) {
    val bgColor = if (!isOnline) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
    val contentColor = if (!isOnline) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isOnline) Icons.Default.Wifi else Icons.Default.WifiOff,
                    contentDescription = null,
                    tint = contentColor
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = if (isOnline) "Online" else "Offline Mode",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = contentColor
                    )
                    Text(
                        text = if (isSyncing) "Syncing data..." else if (pendingCount > 0) "$pendingCount pending sync" else formatLastSyncTime(lastSyncTime),
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.8f)
                    )
                }
            }
            if (isSyncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = contentColor,
                    strokeWidth = 2.dp
                )
            } else if (isOnline && pendingCount == 0) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Synced",
                    tint = contentColor
                )
            } else if (!isOnline) {
                Icon(
                    imageVector = Icons.Default.CloudOff,
                    contentDescription = "Not Synced",
                    tint = contentColor
                )
            }
        }
    }
}
