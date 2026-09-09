package com.example.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.data.InventoryRepository
import com.example.network.NetworkMonitor

class InventoryViewModelFactory(
    private val repository: InventoryRepository,
    private val networkMonitor: NetworkMonitor,
    private val context: Context? = null
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(InventoryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return InventoryViewModel(repository, networkMonitor, context?.applicationContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
