package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.copilot.CopilotKnowledgeProvider
import com.example.data.InventoryItem
import com.example.data.InventoryRepository
import com.example.data.SessionManager
import com.example.network.NetworkMonitor
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

import com.google.ai.client.generativeai.Chat

data class CopilotChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isFromUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

class CopilotViewModel(
    private val repository: InventoryRepository,
    private val sessionManager: SessionManager,
    private val networkMonitor: NetworkMonitor? = null
) : ViewModel() {

    private val _chatMessages = MutableStateFlow<List<CopilotChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<CopilotChatMessage>> = _chatMessages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isChatOpen = MutableStateFlow(false)
    val isChatOpen: StateFlow<Boolean> = _isChatOpen.asStateFlow()

    private val _isFirstLaunch = MutableStateFlow(false)
    val isFirstLaunch: StateFlow<Boolean> = _isFirstLaunch.asStateFlow()

    private var chatSession: Chat? = null

    companion object {
        const val WELCOME_MESSAGE =
            "Welcome! I am A.R.I.F, your AI Copilot. I can help you find stock anomalies, or I can teach you how to use this app."

        val SUGGESTED_PROMPTS = listOf(
            "How do I scan an item?",
            "How do I report a missing item?",
            "Which items are low in stock?",
            "Where is exception auditing?",
            "How do I export inventory to CSV?"
        )
    }

    init {
        checkFirstLaunchAndInitialize()
    }

    private fun checkFirstLaunchAndInitialize() {
        viewModelScope.launch {
            val isFirst = sessionManager.isFirstLaunchFlow.first()
            _isFirstLaunch.value = isFirst

            if (isFirst) {
                _isChatOpen.value = true
                if (_chatMessages.value.isEmpty()) {
                    _chatMessages.value = listOf(CopilotChatMessage(text = WELCOME_MESSAGE, isFromUser = false))
                }
            } else if (_chatMessages.value.isEmpty()) {
                _chatMessages.value = listOf(CopilotChatMessage(text = WELCOME_MESSAGE, isFromUser = false))
            }
        }
    }

    fun openChat() {
        _isChatOpen.value = true
        if (_chatMessages.value.isEmpty()) {
            _chatMessages.value = listOf(CopilotChatMessage(text = WELCOME_MESSAGE, isFromUser = false))
        }
    }

    fun closeChat() {
        _isChatOpen.value = false
        if (_isFirstLaunch.value) {
            markFirstLaunchCompleted()
        }
    }

    fun markFirstLaunchCompleted() {
        viewModelScope.launch {
            sessionManager.setFirstLaunchCompleted()
            _isFirstLaunch.value = false
        }
    }

    val apiKeyFlow = sessionManager.geminiApiKeyFlow

    fun saveApiKey(key: String) {
        chatSession = null
        viewModelScope.launch {
            sessionManager.saveGeminiApiKey(key.trim())
        }
    }

    fun clearChat() {
        chatSession = null
        _chatMessages.value = listOf(CopilotChatMessage(text = WELCOME_MESSAGE, isFromUser = false))
    }

    fun sendMessage(userText: String) {
        val trimmed = userText.trim()
        if (trimmed.isEmpty() || _isLoading.value) return

        val userMsg = CopilotChatMessage(text = trimmed, isFromUser = true)
        _chatMessages.value = _chatMessages.value + userMsg
        _isLoading.value = true

        viewModelScope.launch {
            try {
                val currentStore = sessionManager.storeIdFlow.firstOrNull()?.ifBlank { "CHILLAX" } ?: "CHILLAX"
                val storeItems = withContext(Dispatchers.IO) {
                    val byStore = repository.getInventoryItemsByStore(currentStore)
                    if (byStore.isNotEmpty()) byStore else repository.assetDb.getAllItems()
                }
                val recentTransactions = withContext(Dispatchers.IO) {
                    repository.getAllTransactions()
                }

                val isOnline = networkMonitor?.isOnline?.firstOrNull() ?: true
                val storedKey = sessionManager.geminiApiKeyFlow.firstOrNull()?.trim() ?: ""
                val buildConfigKey = try { com.example.smartinventory.BuildConfig.GEMINI_API_KEY.trim() } catch (_: Exception) { "" }
                val apiKey = if (storedKey.isNotBlank()) storedKey else if (buildConfigKey != "YOUR_API_KEY") buildConfigKey else ""

                if (apiKey.isBlank()) {
                    appendAssistantResponse("API Key missing. Please click the gear icon above to paste your Gemini API key.")
                    return@launch
                }

                val offlineAnswer = CopilotKnowledgeProvider.getOfflineFallbackResponse(trimmed, storeItems)
                if (!isOnline) {
                    val responseText = offlineAnswer ?: "Device is offline. Please check your connection."
                    appendAssistantResponse(responseText)
                    return@launch
                }

                val systemPrompt = CopilotKnowledgeProvider.buildFullSystemPrompt(
                    storeId = currentStore,
                    items = storeItems,
                    transactions = recentTransactions
                )

                if (chatSession == null) {
                    val model = GenerativeModel(
                        modelName = "gemini-3.6-flash",
                        apiKey = apiKey,
                        systemInstruction = content { text(systemPrompt) }
                    )
                    chatSession = model.startChat()
                }

                val response = withContext(Dispatchers.IO) {
                    try {
                        chatSession!!.sendMessage(trimmed)
                    } catch (e: Exception) {
                        // If gemini-3.6-flash fails, fallback to gemini-3.8-flash or gemini-3.5-flash-lite
                        if (e.message?.contains("404") == true || e.message?.contains("not found") == true) {
                            val fallbackModel = GenerativeModel(
                                modelName = "gemini-3.8-flash",
                                apiKey = apiKey,
                                systemInstruction = content { text(systemPrompt) }
                            )
                            chatSession = fallbackModel.startChat()
                            fallbackModel.startChat().sendMessage(trimmed)
                        } else {
                            throw e
                        }
                    }
                }

                if (!response.text.isNullOrBlank()) {
                    appendAssistantResponse(response.text!!.trim())
                } else {
                    appendAssistantResponse("I received an empty response from Gemini. Please try again.")
                }
            } catch (e: Exception) {
                chatSession = null
                val errorMsg = e.localizedMessage ?: e.message ?: "Unknown error"
                
                val currentStore = sessionManager.storeIdFlow.firstOrNull()?.ifBlank { "CHILLAX" } ?: "CHILLAX"
                val storeItems = withContext(Dispatchers.IO) {
                    val byStore = repository.getInventoryItemsByStore(currentStore)
                    if (byStore.isNotEmpty()) byStore else repository.assetDb.getAllItems()
                }
                val offlineAnswer = CopilotKnowledgeProvider.getOfflineFallbackResponse(trimmed, storeItems)
                
                if (offlineAnswer != null) {
                    appendAssistantResponse("$offlineAnswer\n\n(Note: Live A.R.I.F call failed: $errorMsg)")
                } else {
                    appendAssistantResponse("Error calling A.R.I.F ($errorMsg). Please check your internet connection or verify your API key in Settings.")
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun sanitizeText(text: String): String {
        return text
            .replace(Regex("""(?m)^\s*\*\s+"""), "• ")
            .replace("**", "")
            .replace("*", "")
            .trim()
    }

    private fun appendAssistantResponse(text: String) {
        val assistantMsg = CopilotChatMessage(text = sanitizeText(text), isFromUser = false)
        _chatMessages.value = _chatMessages.value + assistantMsg
        _isLoading.value = false
    }
}
