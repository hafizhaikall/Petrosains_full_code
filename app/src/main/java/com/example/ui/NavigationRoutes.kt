package com.example.ui

import kotlinx.serialization.Serializable

sealed class Screen {
    @Serializable data object Login : Screen()
    @Serializable data object Dashboard : Screen()
    @Serializable data object StoreSelection : Screen()
    @Serializable data object CameraScan : Screen()
    @Serializable data object ManualEntry : Screen()
    @Serializable data object History : Screen()
    @Serializable data object Profile : Screen()
    @Serializable data object Search : Screen()
    @Serializable data object IssueTracker : Screen()
    @Serializable data class ItemDetail(val itemCode: String) : Screen()
}
