package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.viewmodel.CopilotViewModel
import com.example.viewmodel.InventoryViewModel
import com.example.ui.screens.*

@Composable
fun AppNavigation(
    viewModel: InventoryViewModel,
    copilotViewModel: CopilotViewModel
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val showBottomBar = currentRoute?.contains("Dashboard") == true ||
           currentRoute?.contains("History") == true ||
           currentRoute?.contains("Search") == true ||
           currentRoute?.contains("Profile") == true

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                CustomBottomNavBar(navController, currentRoute)
            }
        },
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            NavHost(
                navController = navController,
                startDestination = Screen.Login,
                modifier = Modifier.fillMaxSize()
            ) {
                composable<Screen.Login> { LoginScreen(viewModel, navController) }
                composable<Screen.Dashboard> { DashboardScreen(viewModel, copilotViewModel, navController) }
                composable<Screen.StoreSelection> { StoreSelectionScreen(viewModel, navController) }
                composable<Screen.CameraScan> { CameraScanScreen(viewModel, navController) }
                composable<Screen.ManualEntry> { ManualEntryScreen(viewModel, navController) }
                composable<Screen.History> { HistoryScreen(viewModel, navController) }
                composable<Screen.Profile> { ProfileScreen(viewModel, navController) }
                composable<Screen.Search> { SearchScreen(viewModel, navController) }
                composable<Screen.IssueTracker> { IssueTrackerScreen(viewModel, navController) }
                composable<Screen.ItemDetail> { backStackEntry ->
                    val detail = backStackEntry.toRoute<Screen.ItemDetail>()
                    ItemDetailScreen(
                        itemCode = detail.itemCode,
                        viewModel = viewModel,
                        navController = navController
                    )
                }
            }
        }
    }
}

@Composable
fun CustomBottomNavBar(navController: NavController, currentRoute: String?) {
    BottomAppBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        contentPadding = PaddingValues(0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            NavBarItem(
                icon = Icons.Default.Home,
                label = "Home",
                selected = currentRoute?.contains("Dashboard") == true,
                onClick = { navigateTo(navController, Screen.Dashboard) }
            )
            NavBarItem(
                icon = Icons.Default.Inventory,
                label = "Inventory",
                selected = currentRoute?.contains("Search") == true,
                onClick = { navigateTo(navController, Screen.Search) }
            )
            
            Surface(
                onClick = { navController.navigate(Screen.CameraScan) },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp),
                shadowElevation = 2.dp
            ) {
                Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Icon(
                        Icons.Default.QrCodeScanner, 
                        contentDescription = "Scan", 
                        tint = Color.White, 
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            
            NavBarItem(
                icon = Icons.Default.History,
                label = "History",
                selected = currentRoute?.contains("History") == true,
                onClick = { navigateTo(navController, Screen.History) }
            )
            NavBarItem(
                icon = Icons.Default.Settings,
                label = "Setting",
                selected = currentRoute?.contains("Profile") == true,
                onClick = { navigateTo(navController, Screen.Profile) }
            )
        }
    }
}

@Composable
fun RowScope.NavBarItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    IconButton(onClick = onClick, modifier = Modifier.weight(1f)) {
        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, tint = color)
            Text(label, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

private fun navigateTo(navController: NavController, screen: Screen) {
    navController.navigate(screen) {
        popUpTo(Screen.Dashboard) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
