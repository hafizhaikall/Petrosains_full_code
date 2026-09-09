package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "user_prefs")

class SessionManager(private val context: Context) {
    private val USER_ID_KEY = stringPreferencesKey("user_id")
    private val USERNAME_KEY = stringPreferencesKey("username")
    private val STORE_ID_KEY = stringPreferencesKey("store_id")
    private val LAST_SYNC_KEY = longPreferencesKey("last_sync_timestamp")
    private val IS_FIRST_LAUNCH_KEY = booleanPreferencesKey("is_first_launch")
    private val GEMINI_API_KEY = stringPreferencesKey("user_gemini_api_key")

    val userIdFlow: Flow<String?> = context.dataStore.data.map { prefs -> prefs[USER_ID_KEY] }
    val usernameFlow: Flow<String?> = context.dataStore.data.map { prefs -> prefs[USERNAME_KEY] }
    val storeIdFlow: Flow<String?> = context.dataStore.data.map { prefs -> prefs[STORE_ID_KEY] }
    val lastSyncTimeFlow: Flow<Long> = context.dataStore.data.map { prefs -> prefs[LAST_SYNC_KEY] ?: 0L }
    val isFirstLaunchFlow: Flow<Boolean> = context.dataStore.data.map { prefs -> prefs[IS_FIRST_LAUNCH_KEY] ?: true }
    val geminiApiKeyFlow: Flow<String?> = context.dataStore.data.map { prefs -> prefs[GEMINI_API_KEY] }

    suspend fun setFirstLaunchCompleted() {
        context.dataStore.edit { prefs -> prefs[IS_FIRST_LAUNCH_KEY] = false }
    }

    suspend fun saveLastSyncTime(timestamp: Long) {
        context.dataStore.edit { prefs -> prefs[LAST_SYNC_KEY] = timestamp }
    }

    suspend fun saveSession(userId: String, username: String = "") {
        context.dataStore.edit { prefs -> 
            prefs[USER_ID_KEY] = userId
            if (username.isNotBlank()) {
                prefs[USERNAME_KEY] = username
            }
        }
    }

    suspend fun saveCurrentStore(storeId: String) {
        context.dataStore.edit { prefs -> prefs[STORE_ID_KEY] = storeId }
    }

    suspend fun clearSession() {
        context.dataStore.edit { prefs -> 
            prefs.remove(USER_ID_KEY) 
            prefs.remove(USERNAME_KEY)
            prefs.remove(STORE_ID_KEY)
        }
    }

    suspend fun saveGeminiApiKey(key: String) {
        context.dataStore.edit { prefs -> prefs[GEMINI_API_KEY] = key }
    }
}
