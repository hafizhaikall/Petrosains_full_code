package com.example.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.smartinventory.BuildConfig
import com.example.data.*
import com.example.ml.ObjectDetector
import com.example.ml.ObjectDetector.DetectionResult
import com.example.network.*
import com.example.worker.FirebaseSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class DetectionSource { TFLITE, GEMINI, NONE }

class InventoryViewModel(
    private val repository: InventoryRepository,
    private val networkMonitor: NetworkMonitor,
    private val appContext: Context? = null
) : ViewModel() {

    fun scheduleFirebaseSync(context: Context? = appContext) {
        val targetContext = context ?: appContext ?: return
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = OneTimeWorkRequestBuilder<FirebaseSyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(targetContext)
                .enqueueUniqueWork(
                    FirebaseSyncWorker.WORK_NAME,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    syncRequest
                )
        } catch (e: Exception) {
            android.util.Log.e("InventoryViewModel", "Failed to schedule FirebaseSyncWorker: ${e.message}")
        }
    }

    val userId = repository.sessionManager.userIdFlow.stateIn(viewModelScope, SharingStarted.Lazily, null)
    val username = repository.sessionManager.usernameFlow.stateIn(viewModelScope, SharingStarted.Lazily, null)
    val storeId = repository.sessionManager.storeIdFlow.stateIn(viewModelScope, SharingStarted.Lazily, null)

    val stores = repository.getStores().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // --- Real inventory from inventory.db (loaded on demand) ---
    private val _allInventoryItems = MutableStateFlow<List<InventoryItem>>(emptyList())
    val allInventoryItems: StateFlow<List<InventoryItem>> = _allInventoryItems.asStateFlow()

    private val _storeInventoryItems = MutableStateFlow<List<InventoryItem>>(emptyList())
    val storeInventoryItems: StateFlow<List<InventoryItem>> = _storeInventoryItems.asStateFlow()

    val items = repository.getItems().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val roomStoreItems: StateFlow<List<Item>> = storeId.flatMapLatest { sId ->
        if (sId != null && sId.isNotBlank()) {
            repository.getItemsByStoreRoom(sId)
        } else {
            flowOf(emptyList())
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val transactions = repository.getTransactions().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val pendingSyncCount = repository.getPendingSyncCount().stateIn(viewModelScope, SharingStarted.Lazily, 0)
    val isOnline = networkMonitor.isOnline.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val lastSyncTime = repository.sessionManager.lastSyncTimeFlow.stateIn(viewModelScope, SharingStarted.Lazily, 0L)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val activeCheckouts: StateFlow<List<com.example.data.ActiveCheckoutItem>> = username.flatMapLatest { uName ->
        val effectiveUser = uName?.ifBlank { null } ?: userId.value ?: "admin"
        repository.getActiveCheckoutsForUser(effectiveUser)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeExceptions: StateFlow<List<com.example.data.ExceptionItem>> = repository.getActiveExceptions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentInventory = MutableStateFlow<List<Inventory>>(emptyList())
    val currentInventory: StateFlow<List<Inventory>> = _currentInventory.asStateFlow()

    private val _aiScanResult = MutableStateFlow<List<AiDetectionItem>?>(null)
    val aiScanResult: StateFlow<List<AiDetectionItem>?> = _aiScanResult.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _detectionSource = MutableStateFlow(DetectionSource.NONE)
    val detectionSource: StateFlow<DetectionSource> = _detectionSource.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _liveDetections = MutableStateFlow<List<DetectionResult>>(emptyList())
    val liveDetections: StateFlow<List<DetectionResult>> = _liveDetections.asStateFlow()

    private val _detectionFrameSize = MutableStateFlow<Pair<Int, Int>?>(null)
    val detectionFrameSize: StateFlow<Pair<Int, Int>?> = _detectionFrameSize.asStateFlow()
    
    fun updateLiveDetections(detections: List<DetectionResult>, frameWidth: Int = 0, frameHeight: Int = 0) {
        _liveDetections.value = detections
        if (frameWidth > 0 && frameHeight > 0) {
            _detectionFrameSize.value = Pair(frameWidth, frameHeight)
        }
    }

    private val _isScannerPaused = MutableStateFlow(false)
    val isScannerPaused: StateFlow<Boolean> = _isScannerPaused.asStateFlow()

    fun pauseScanner() {
        _isScannerPaused.value = true
    }

    fun resumeScanner() {
        _isScannerPaused.value = false
    }

    fun updateExpectedReturnDate(index: Int, newDate: Long?) {
        val currentList = _aiScanResult.value?.toMutableList() ?: return
        if (index in currentList.indices) {
            val currentItem = currentList[index]
            currentList[index] = currentItem.copy(
                expectedReturnDate = newDate,
                itemName = currentItem.itemName,
                itemCode = currentItem.itemCode,
                confidence = currentItem.confidence
            )
            _aiScanResult.value = currentList
        }
    }

    // --- Item Code Scanning States ---
    private val _scannedItem = MutableStateFlow<ScannedItemResult?>(null)
    val scannedItem: StateFlow<ScannedItemResult?> = _scannedItem.asStateFlow()

    private val _showItemDetails = MutableStateFlow(false)
    val showItemDetails: StateFlow<Boolean> = _showItemDetails.asStateFlow()

    private val _scanErrorMessage = MutableStateFlow<String?>(null)
    val scanErrorMessage: StateFlow<String?> = _scanErrorMessage.asStateFlow()

    private val _isItemCodeScanning = MutableStateFlow(false)
    val isItemCodeScanning: StateFlow<Boolean> = _isItemCodeScanning.asStateFlow()

    // --- Item Details Screen State ---
    private val _selectedItemDetail = MutableStateFlow<InventoryItem?>(null)
    val selectedItemDetail: StateFlow<InventoryItem?> = _selectedItemDetail.asStateFlow()

    private val _selectedItemCode = MutableStateFlow<String?>(null)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val selectedItemTransactions: StateFlow<List<TransactionLog>> = _selectedItemCode
        .flatMapLatest { code ->
            if (code.isNullOrBlank()) flowOf(emptyList())
            else repository.getTransactionsByItemCode(code)
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _isLoadingDetail = MutableStateFlow(false)
    val isLoadingDetail: StateFlow<Boolean> = _isLoadingDetail.asStateFlow()

    private var tfliteDetector: ObjectDetector? = null

    fun loadItemDetail(itemCode: String) {
        _selectedItemCode.value = itemCode
        viewModelScope.launch {
            _isLoadingDetail.value = true
            _selectedItemDetail.value = repository.getItemDetails(itemCode)
            _isLoadingDetail.value = false
        }
    }

    init {
        viewModelScope.launch { repository.initializeStores() }

        viewModelScope.launch {
            storeId.collectLatest { sId ->
                val targetRoom = sId ?: "CHILLAX"
                _storeInventoryItems.value = repository.getInventoryItemsByStore(targetRoom)
            }
        }

        viewModelScope.launch {
            _allInventoryItems.value = repository.getAllInventoryItems()
        }

        viewModelScope.launch {
            isOnline.collectLatest { online ->
                if (online && pendingSyncCount.value > 0) syncNow()
            }
        }

        viewModelScope.launch {
            pendingSyncCount.collectLatest { count ->
                if (count > 0 && isOnline.value) syncNow()
            }
        }

        // Start listening to real-time remote cloud updates from Firebase
        repository.syncManager?.startRealtimeSync {
            refreshInventoryData()
        }

        viewModelScope.launch {
            syncNow()
        }
    }

    fun refreshInventoryData() {
        viewModelScope.launch {
            _allInventoryItems.value = repository.getAllInventoryItems()
            val currentRoom = storeId.value ?: "CHILLAX"
            _storeInventoryItems.value = repository.getInventoryItemsByStore(currentRoom)
        }
    }

    fun login(username: String, password: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val user = repository.login(username, password)
            onResult(user != null)
        }
    }

    /**
     * Scanning logic: When an "Item Code" is scanned, queries the database
     * to retrieve the item's name, "Asset Type", "Specific Location / Rack",
     * and "Storage Location", and sets the confirmation state (keeping details hidden initially).
     */
    fun scanItemCode(code: String) {
        if (_isScannerPaused.value) return
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            _isItemCodeScanning.value = true
            _scanErrorMessage.value = null
            _showItemDetails.value = false
            try {
                val result = repository.scanItemByCode(trimmed)
                if (result != null) {
                    _scannedItem.value = result
                    _showItemDetails.value = false // Keep details hidden until user clicks the item
                } else {
                    _scannedItem.value = null
                    _scanErrorMessage.value = "No item found with Item Code '$trimmed'"
                }
            } catch (e: Exception) {
                _scannedItem.value = null
                _scanErrorMessage.value = "Error querying database: ${e.localizedMessage}"
            } finally {
                _isItemCodeScanning.value = false
            }
        }
    }

    fun showScannedItemDetails() {
        _showItemDetails.value = true
    }

    fun dismissScannedItemDetails() {
        _showItemDetails.value = false
    }

    fun clearScannedItem() {
        _scannedItem.value = null
        _showItemDetails.value = false
        _scanErrorMessage.value = null
    }

    fun logout() {
        viewModelScope.launch { repository.sessionManager.clearSession() }
    }

    fun getDetector(context: Context): ObjectDetector {
        return tfliteDetector ?: ObjectDetector(context).also { tfliteDetector = it }
    }

    fun selectStore(storeId: String) {
        viewModelScope.launch { repository.sessionManager.saveCurrentStore(storeId) }
    }

    fun processLiveDetectionCapture(detections: List<DetectionResult>) {
        if (_isScannerPaused.value) return
        _isScanning.value = true
        _detectionSource.value = DetectionSource.TFLITE
        val grouped = aggregateDetections(detections)
        _aiScanResult.value = grouped
        _isScanning.value = false
    }

    /**
     * Smart detection: TFLite first → Gemini fallback if low confidence or no results.
     */
    fun processImageSmart(bitmap: Bitmap, context: Context) {
        if (_isScannerPaused.value) return
        viewModelScope.launch {
            _isScanning.value = true
            _detectionSource.value = DetectionSource.NONE

            try {
                val tfliteResults = withContext(Dispatchers.Default) {
                    val detector = tfliteDetector ?: ObjectDetector(context).also { tfliteDetector = it }
                    detector.detect(bitmap)
                }

                val bestConfidence = tfliteResults.firstOrNull()?.confidence ?: 0f

                if (tfliteResults.isNotEmpty()) {
                    val grouped = aggregateDetections(tfliteResults)
                    _aiScanResult.value = grouped
                    _detectionSource.value = DetectionSource.TFLITE
                } else if (isOnline.value) {
                    processImageWithAi(bitmap)
                    _detectionSource.value = DetectionSource.GEMINI
                } else {
                    _aiScanResult.value = emptyList()
                    _detectionSource.value = DetectionSource.TFLITE
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (isOnline.value) {
                    processImageWithAi(bitmap)
                    _detectionSource.value = DetectionSource.GEMINI
                } else {
                    _aiScanResult.value = emptyList()
                }
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun processImageWithAi(bitmap: Bitmap) {
        if (_isScannerPaused.value) return
        viewModelScope.launch {
            try {
                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

                val catalogSummary = _storeInventoryItems.value.joinToString(", ") { "${it.item_name} (Code: ${it.item_code})" }
                
                val prompt = """
                    Analyze this image and identify inventory items. Count their quantity. 
                    IMPORTANT: You are looking at items in a scientific inventory room. 
                    The allowed items in this room are: [$catalogSummary]. 
                    Do NOT guess generic names. Match the items exactly to the allowed list above if possible.
                    Respond with a JSON array of objects with fields: 'itemName' (string), 'quantity' (integer), 'itemType' (string), and 'confidence' (float 0-100).
                """.trimIndent()

                val request = GenerateContentRequest(
                    contents = listOf(Content(
                        parts = listOf(
                            Part(text = prompt),
                            Part(inlineData = InlineData(mimeType = "image/jpeg", data = base64Image))
                        )
                    )),
                    generationConfig = GenerationConfig(
                        responseFormat = ResponseFormat(
                            text = ResponseFormatText(
                                mimeType = "application/json",
                                schema = buildJsonObject {
                                    put("type", "ARRAY")
                                    putJsonObject("items") {
                                        put("type", "OBJECT")
                                        putJsonObject("properties") {
                                            putJsonObject("itemName") { put("type", "STRING") }
                                            putJsonObject("quantity") { put("type", "INTEGER") }
                                            putJsonObject("itemType") { put("type", "STRING") }
                                            putJsonObject("confidence") { put("type", "NUMBER") }
                                        }
                                    }
                                }
                            )
                        )
                    )
                )

                val apiKey = BuildConfig.GEMINI_API_KEY
                val response = RetrofitClient.service.generateContent(apiKey, request)
                val textResponse = response.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: "[]"
                val json = Json { ignoreUnknownKeys = true }
                _aiScanResult.value = json.decodeFromString<List<AiDetectionItem>>(textResponse)

            } catch (e: Exception) {
                e.printStackTrace()
                _aiScanResult.value = emptyList()
            }
        }
    }

    fun confirmAiScan(
        items: List<AiDetectionItem>,
        type: TransactionType,
        reason: String = "",
        context: Context? = null,
        onResult: (TransactionOperationResult) -> Unit = {}
    ) {
        viewModelScope.launch {
            val uId = username.value?.ifBlank { null } ?: userId.value ?: "admin"
            val sId = storeId.value ?: "CHILLAX"

            // Pre-validate all items: if any item's official Storage Location does not match current store, block all updates
            for (item in items) {
                val dbItem = repository.getInventoryItemByCode(item.itemName)
                    ?: repository.searchInventoryItems(item.itemName).firstOrNull()
                if (dbItem != null && dbItem.storage_location.isNotBlank() && sId.isNotBlank()) {
                    if (!dbItem.storage_location.trim().equals(sId.trim(), ignoreCase = true)) {
                        val errorMsg = "Cannot update inventory for this item. Wrong store. Please go to the correct store: ${dbItem.storage_location.trim()}"
                        val wrongResult = TransactionOperationResult(
                            status = EntryResult.WRONG_STORE,
                            correctStore = dbItem.storage_location.trim(),
                            message = errorMsg
                        )
                        onResult(wrongResult)
                        return@launch
                    }
                }
            }

            var allSuccess = true
            var lastResult = TransactionOperationResult(EntryResult.SUCCESS)

            for (item in items) {
                val itemReason = item.reason?.trim()?.ifEmpty { null } ?: reason.trim()
                val res = if (type == TransactionType.CHECK_IN) {
                    val activeCheckedOut = item.activeCheckedOutQuantity
                    if (activeCheckedOut > 0 && item.quantity < activeCheckedOut) {
                        repository.executePartialReturn(
                            itemNameOrCode = item.itemName,
                            returnedQuantity = item.quantity,
                            damagedQuantity = item.damagedQuantity,
                            missingQuantity = item.missingQuantity,
                            disposedQuantity = item.disposedQuantity,
                            storeId = sId,
                            userId = uId,
                            method = EntryMethod.AI_SCAN,
                            reason = itemReason
                        )
                    } else {
                        repository.checkInItem(item.itemName, item.quantity, sId, uId, EntryMethod.AI_SCAN, reason = itemReason)
                    }
                } else {
                    repository.checkOutItem(
                        itemNameOrCode = item.itemName,
                        quantity = item.quantity,
                        storeId = sId,
                        userId = uId,
                        method = EntryMethod.AI_SCAN,
                        reason = itemReason,
                        expectedReturnDate = item.expectedReturnDate ?: 0L
                    )
                }
                if (res.status != EntryResult.SUCCESS) {
                    allSuccess = false
                    lastResult = res
                    break
                }
            }

            if (allSuccess) {
                _aiScanResult.value = null
                _detectionSource.value = DetectionSource.NONE

                // Immediately refresh inventory so UI updates with new counts
                _allInventoryItems.value = repository.getAllInventoryItems()
                val currentStoreId = storeId.value ?: sId
                _storeInventoryItems.value = repository.getInventoryItemsByStore(currentStoreId)

                // Schedule background synchronization to Firebase with network constraint
                scheduleFirebaseSync(context ?: appContext)

                onResult(TransactionOperationResult(EntryResult.SUCCESS))
            } else {
                onResult(lastResult)
            }
        }
    }

    fun clearAiScan() {
        _aiScanResult.value = null
        _detectionSource.value = DetectionSource.NONE
        _checkedOutQuantities.value = emptyMap()
        _isScannerPaused.value = false
    }

    private val _checkedOutQuantities = MutableStateFlow<Map<String, Int>>(emptyMap())
    val checkedOutQuantities: StateFlow<Map<String, Int>> = _checkedOutQuantities.asStateFlow()

    fun loadCheckedOutQuantity(itemNameOrCode: String) {
        viewModelScope.launch {
            val uId = username.value?.ifBlank { null } ?: userId.value ?: "admin"
            val qty = repository.getActiveCheckedOutQuantity(itemNameOrCode, uId)
            val map = _checkedOutQuantities.value.toMutableMap()
            map[itemNameOrCode] = qty
            map[itemNameOrCode.trim()] = qty
            map[itemNameOrCode.lowercase().trim()] = qty
            _checkedOutQuantities.value = map
        }
    }

    fun loadCheckedOutQuantities(itemNames: List<String>) {
        viewModelScope.launch {
            val uId = username.value?.ifBlank { null } ?: userId.value ?: "admin"
            val map = _checkedOutQuantities.value.toMutableMap()
            for (name in itemNames) {
                val qty = repository.getActiveCheckedOutQuantity(name, uId)
                map[name] = qty
                map[name.trim()] = qty
                map[name.lowercase().trim()] = qty
            }
            _checkedOutQuantities.value = map
        }
    }

    fun refreshActiveCheckedOutQuantities(items: List<AiDetectionItem>, onDone: (List<AiDetectionItem>) -> Unit) {
        viewModelScope.launch {
            val uId = username.value?.ifBlank { null } ?: userId.value ?: "admin"
            val map = _checkedOutQuantities.value.toMutableMap()
            val updated = items.map { item ->
                val checkedOut = repository.getActiveCheckedOutQuantity(item.itemName, uId)
                map[item.itemName] = checkedOut
                map[item.itemName.trim()] = checkedOut
                map[item.itemName.lowercase().trim()] = checkedOut
                item.copy(activeCheckedOutQuantity = checkedOut)
            }
            _checkedOutQuantities.value = map
            onDone(updated)
        }
    }

    fun manualEntry(
        itemCodeOrName: String,
        quantity: Int,
        type: TransactionType,
        status: String = "",
        result: (TransactionOperationResult) -> Unit
    ) {
        viewModelScope.launch {
            val uId = username.value?.ifBlank { null } ?: userId.value ?: "admin"
            val sId = storeId.value ?: "CHILLAX"
            val res = repository.manualEntry(itemCodeOrName, quantity, type, sId, uId, status)
            if (res.status == EntryResult.SUCCESS) {
                _allInventoryItems.value = repository.getAllInventoryItems()
                val currentStoreId = storeId.value ?: sId
                _storeInventoryItems.value = repository.getInventoryItemsByStore(currentStoreId)
                scheduleFirebaseSync(appContext)
            }
            result(res)
        }
    }

    fun updateItemStatus(
        itemCode: String,
        newStatus: String,
        onResult: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch {
            val uId = username.value?.ifBlank { null } ?: userId.value ?: "admin"
            val sId = storeId.value ?: "CHILLAX"
            val success = repository.updateItemStatus(itemCode, newStatus, sId, uId)
            if (success) {
                refreshInventoryData()
                loadItemDetail(itemCode)
            }
            onResult(success)
        }
    }

    fun resolveException(
        exception: com.example.data.ExceptionItem,
        newStatus: String,
        notes: String = "",
        onDone: (TransactionOperationResult) -> Unit = {}
    ) {
        viewModelScope.launch {
            val uId = username.value?.ifBlank { null } ?: userId.value ?: "admin"
            val result = repository.resolveExceptionItem(
                exception = exception,
                resolutionStatus = newStatus,
                resolutionReason = notes,
                resolvedBy = uId
            )
            // Immediately refresh local inventory items and transactions
            _allInventoryItems.value = repository.getAllInventoryItems()
            val currentStoreId = storeId.value ?: "CHILLAX"
            _storeInventoryItems.value = repository.getInventoryItemsByStore(currentStoreId)
            scheduleFirebaseSync(appContext)
            onDone(result)
        }
    }

    fun syncNow() {
        if (_isSyncing.value) return
        viewModelScope.launch {
            _isSyncing.value = true
            repository.syncTransactions()
            refreshInventoryData()
            _isSyncing.value = false
        }
    }

    fun exportHistoryCsv(
        context: Context,
        onResult: (com.example.util.CsvExportResult) -> Unit
    ) {
        viewModelScope.launch {
            val sId = storeId.value ?: "CHILLAX"
            val result = repository.exportTransactionHistoryCsv(context, sId)
            onResult(result)
        }
    }

    fun exportInventoryCsv(
        context: Context,
        onResult: (com.example.util.CsvExportResult) -> Unit
    ) {
        viewModelScope.launch {
            val sId = storeId.value ?: "CHILLAX"
            val storeName = stores.value.find { it.id == sId }?.name ?: sId
            val itemsToExport = if (_storeInventoryItems.value.isNotEmpty()) {
                _storeInventoryItems.value
            } else {
                repository.getInventoryItemsByStore(sId).ifEmpty {
                    repository.getAllInventoryItems()
                }
            }
            val result = com.example.util.CsvExporter.exportInventoryReport(context, itemsToExport, storeName)
            onResult(result)
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.syncManager?.stopRealtimeSync()
        tfliteDetector?.close()
    }

    companion object {
        /**
         * Intelligently aggregates detection results.
         * Handles stacked cup counting:
         * - 'full_cup' identifies the stack bounding box or standalone cup body.
         * - 'cup_rim' identifies each exposed cup rim within the stack.
         * - Calculates total cups = (rims within stack) or 1 for standalone cups.
         * - Normalizes all labels to official inventory item names (e.g. 'Paper cup').
         */
        fun aggregateDetections(detections: List<ObjectDetector.DetectionResult>): List<AiDetectionItem> {
            val result = mutableListOf<AiDetectionItem>()

            // 1. Intelligent Cup Stacking Resolution (full_cup + cup_rim)
            val cupDetections = detections.filter {
                it.label.equals("cup_rim", ignoreCase = true) || it.label.equals("full_cup", ignoreCase = true)
            }

            if (cupDetections.isNotEmpty()) {
                val fullCups = cupDetections.filter { it.label.equals("full_cup", ignoreCase = true) }
                val cupRims = cupDetections.filter { it.label.equals("cup_rim", ignoreCase = true) }

                val totalCups: Int = if (fullCups.isEmpty()) {
                    // Only rims detected (e.g. top-down angle)
                    cupRims.size
                } else if (cupRims.isEmpty()) {
                    // Only full cup bodies detected (e.g. standalone cups without distinct rims)
                    fullCups.size
                } else {
                    // Stacks detected with both body and rims
                    val assignedRims = mutableSetOf<ObjectDetector.DetectionResult>()
                    var cupsInStacks = 0

                    for (fc in fullCups) {
                        val box = fc.boundingBox
                        // 15% margin tolerance around the stack box
                        val marginX = box.width() * 0.15f
                        val marginY = box.height() * 0.15f
                        val left = box.left - marginX
                        val right = box.right + marginX
                        val top = box.top - marginY
                        val bottom = box.bottom + marginY

                        val rimsInThisStack = cupRims.filter { rim ->
                            val cx = rim.boundingBox.centerX()
                            val cy = rim.boundingBox.centerY()
                            cx in left..right && cy in top..bottom
                        }

                        assignedRims.addAll(rimsInThisStack)
                        // If rims were detected in this stack, count is the number of rims.
                        // If no rims were detected inside this full cup (standalone), count as 1 cup.
                        cupsInStacks += if (rimsInThisStack.isNotEmpty()) rimsInThisStack.size else 1
                    }

                    // Any rims detected outside of any full cup bounding box
                    val orphanRims = cupRims.filter { it !in assignedRims }
                    cupsInStacks + orphanRims.size
                }

                if (totalCups > 0) {
                    result.add(
                        AiDetectionItem(
                            itemName = "Paper cup",
                            quantity = totalCups,
                            itemType = "Paper cup",
                            confidence = cupDetections.maxOf { it.confidence } * 100f
                        )
                    )
                }
            }

            // 2. All other items
            val otherDetections = detections.filter {
                !it.label.equals("cup_rim", ignoreCase = true) && !it.label.equals("full_cup", ignoreCase = true)
            }

            val groupedOthers = otherDetections
                .groupBy { normalizeItemLabel(it.label) }
                .map { (normalizedName, items) ->
                    AiDetectionItem(
                        itemName = normalizedName,
                        quantity = items.size,
                        itemType = items.first().label,
                        confidence = items.maxOf { it.confidence } * 100f
                    )
                }
            result.addAll(groupedOthers)

            return result
        }

        fun normalizeItemLabel(label: String): String {
            return when (label.lowercase().trim()) {
                "a4_colored_paper" -> "A4 colored paper"
                "arduino_uno" -> "Arduino Uno"
                "breadboard" -> "Breadboard"
                "goggles" -> "Safety Goggle"
                "nodemcu_esp32" -> "NodeMCU"
                "pen" -> "Pen"
                "scissors" -> "Scissor"
                "box_sticky_note", "sticky_note_paper" -> "Sticky note"
                "tongue_depressor" -> "Tongue depressor"
                "bundle_arduino", "bag_arduino_20", "bag_arduino_30" -> "Arduino Uno"
                else -> label.replace("_", " ")
            }
        }
    }
}

@Serializable
data class AiDetectionItem(
    val itemName: String,
    val quantity: Int,
    val itemType: String = "",
    val confidence: Float = 0f,
    val itemCode: String = "",
    val reason: String? = null,
    val expectedReturnDate: Long? = null,
    val activeCheckedOutQuantity: Int = 0,
    val unreturnedStatus: String = "Damaged and Under Maintenance",
    val damagedQuantity: Int = 0,
    val missingQuantity: Int = 0,
    val disposedQuantity: Int = 0
)

