package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.AppDatabase
import com.example.data.AssetDbHelper
import com.example.data.InventoryRepository
import com.example.data.SessionManager
import com.example.network.NetworkMonitor
import com.example.ui.AppNavigation
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.CopilotViewModel
import com.example.viewmodel.CopilotViewModelFactory
import com.example.viewmodel.InventoryViewModel
import com.example.viewmodel.InventoryViewModelFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val database    = AppDatabase.getDatabase(this)
        val sessionManager = SessionManager(this)
        val assetDb     = AssetDbHelper(this)          // Opens inventory.db directly
        val syncManager = com.example.sync.SyncManager(this, assetDb, database.inventoryDao())
        val repository  = InventoryRepository(database.inventoryDao(), sessionManager, assetDb, syncManager)
        val networkMonitor = NetworkMonitor(this)

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val viewModel: InventoryViewModel = viewModel(
                        factory = InventoryViewModelFactory(repository, networkMonitor, this)
                    )
                    val copilotViewModel: CopilotViewModel = viewModel(
                        factory = CopilotViewModelFactory(repository, sessionManager, networkMonitor)
                    )
                    AppNavigation(viewModel, copilotViewModel)
                }
            }
        }
    }
}
