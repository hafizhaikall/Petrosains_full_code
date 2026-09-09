package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.data.InventoryRepository
import com.example.data.SessionManager
import com.example.network.NetworkMonitor

class CopilotViewModelFactory(
    private val repository: InventoryRepository,
    private val sessionManager: SessionManager,
    private val networkMonitor: NetworkMonitor? = null
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CopilotViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return CopilotViewModel(repository, sessionManager, networkMonitor) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
