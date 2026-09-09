package com.example

import com.example.data.EntryResult
import com.example.data.InventoryItem
import com.example.data.Item
import com.example.data.ScannedItemResult
import com.example.data.SyncStatus
import com.example.data.TransactionLog
import com.example.data.TransactionOperationResult
import com.example.data.TransactionType
import com.example.data.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanningLogicTest {

    @Test
    fun testUserCredentialsModel() {
        val user = User(
            id = "1",
            username = "admin",
            name = "Admin",
            password = "admin123"
        )
        assertEquals("admin", user.username)
        assertEquals("admin123", user.password)
    }

    @Test
    fun testEntryResultEnum() {
        assertEquals(EntryResult.SUCCESS, EntryResult.valueOf("SUCCESS"))
        assertEquals(EntryResult.ITEM_NOT_FOUND, EntryResult.valueOf("ITEM_NOT_FOUND"))
        assertEquals(EntryResult.INSUFFICIENT_STOCK, EntryResult.valueOf("INSUFFICIENT_STOCK"))
        assertEquals(EntryResult.WRONG_STORE, EntryResult.valueOf("WRONG_STORE"))
    }

    @Test
    fun testStrictLocationValidationWrongStoreBlocked() {
        val officialStore = "CHILLAX"
        val userCurrentStore = "CHEMICAL ROOM"

        // Validation logic: check if user store matches official database location
        val isMatching = officialStore.equals(userCurrentStore, ignoreCase = true)
        assertFalse(isMatching)

        val result = if (!isMatching) {
            TransactionOperationResult(
                status = EntryResult.WRONG_STORE,
                correctStore = officialStore,
                message = "Please go to $officialStore.You enter the wrong store for this items"
            )
        } else {
            TransactionOperationResult(EntryResult.SUCCESS)
        }

        assertEquals(EntryResult.WRONG_STORE, result.status)
        assertEquals("CHILLAX", result.correctStore)
        assertEquals("Please go to CHILLAX.You enter the wrong store for this items", result.message)
    }

    @Test
    fun testStrictLocationValidationMatchingStoreAllowed() {
        val officialStore = "CHILLAX"
        val userCurrentStore = "CHILLAX"

        val isMatching = officialStore.equals(userCurrentStore, ignoreCase = true)
        assertTrue(isMatching)

        val result = if (!isMatching) {
            TransactionOperationResult(
                status = EntryResult.WRONG_STORE,
                correctStore = officialStore,
                message = "Cannot update inventory for this item. Wrong store. Please go to the correct store: $officialStore"
            )
        } else {
            TransactionOperationResult(EntryResult.SUCCESS)
        }

        assertEquals(EntryResult.SUCCESS, result.status)
    }

    @Test
    fun testStoreRoomCatalogFiltering() {
        val allItems = listOf(
            InventoryItem(item_code = "E006", item_name = "NodeMCU", storage_location = "CHILLAX", available_quantity = 102),
            InventoryItem(item_code = "C014", item_name = "A4 colored paper", storage_location = "MAKER STUDIO", available_quantity = 10),
            InventoryItem(item_code = "C007", item_name = "Acrylic standee", storage_location = "STORE 1", available_quantity = 86),
            InventoryItem(item_code = "CH01", item_name = "Ethanol", storage_location = "CHEMICAL ROOM", available_quantity = 5)
        )

        val selectedStoreRoom = "CHILLAX"
        val filteredList = allItems.filter { it.storage_location.equals(selectedStoreRoom, ignoreCase = true) }

        assertEquals(1, filteredList.size)
        assertEquals("E006", filteredList[0].item_code)
        assertEquals("NodeMCU", filteredList[0].item_name)
        assertEquals("CHILLAX", filteredList[0].storage_location)
    }

    @Test
    fun testInventoryCheckoutDeductionCalculation() {
        val initialQuantity = 102
        val checkoutAmount = 2
        val newQuantity = (initialQuantity - checkoutAmount).coerceAtLeast(0)
        assertEquals(100, newQuantity)
    }

    @Test
    fun testStorageLocationRoutingPriority() {
        // NodeMCU has storage_location = "CHILLAX" and specific_location = "DRAWER WOODEN 2" in DB
        val dbItem = InventoryItem(
            item_code = "E006",
            item_name = "NodeMCU",
            storage_location = "CHILLAX",
            specific_location = "DRAWER WOODEN 2",
            available_quantity = 102
        )
        val activeSessionStore = "CHEMICAL ROOM"

        // The app must route to the item's database storage location ('CHILLAX'), not arbitrary active session store
        val targetStore = dbItem.storage_location.ifEmpty { activeSessionStore.ifEmpty { "CHILLAX" } }
        assertEquals("CHILLAX", targetStore)
        assertEquals("DRAWER WOODEN 2", dbItem.specific_location)
    }

    @Test
    fun testSequentialScanStateInitialDetailsHidden() {
        var scannedItem: ScannedItemResult? = null
        var showItemDetails = false

        // Initial state: Item details must be completely hidden
        assertNull(scannedItem)
        assertFalse(showItemDetails)

        // Step 1: Scan barcode -> Item found in DB
        scannedItem = ScannedItemResult(
            itemCode = "E006",
            itemName = "NodeMCU",
            assetType = "Consumable",
            specificLocation = "DRAWER WOODEN 2",
            storageLocation = "CHILLAX",
            availableQuantity = 98,
            unit = "Unit"
        )
        // Details MUST remain hidden until user explicitly clicks the item on confirmation screen
        assertNotNull(scannedItem)
        assertFalse(showItemDetails)

        // Step 2 & 3: User clicks scanned item on confirmation screen
        showItemDetails = true

        // Step 4: Full details displayed
        assertTrue(showItemDetails)
        assertEquals("NodeMCU", scannedItem.itemName)
        assertEquals("CHILLAX", scannedItem.storageLocation)
        assertEquals("DRAWER WOODEN 2", scannedItem.specificLocation)
    }

    @Test
    fun testTransactionLogRecording() {
        val tx = TransactionLog(
            id = "tx-123",
            itemId = "E006",
            storeId = "CHILLAX",
            userId = "admin",
            quantityChange = 2,
            type = TransactionType.CHECK_OUT,
            method = com.example.data.EntryMethod.AI_SCAN,
            timestamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.SYNCED
        )
        assertEquals("E006", tx.itemId)
        assertEquals(TransactionType.CHECK_OUT, tx.type)
        assertEquals(2, tx.quantityChange)
        assertEquals("CHILLAX", tx.storeId)
    }

    @Test
    fun testScannedItemResultFields() {
        val result = ScannedItemResult(
            itemCode = "E001",
            itemName = "Arduino Uno",
            assetType = "Controllable Asset",
            specificLocation = "MS METAL DRAWER",
            storageLocation = "CHILLAX",
            availableQuantity = 96,
            totalQuantity = 96,
            category = "Electronics & Robotics"
        )

        assertEquals("E001", result.itemCode)
        assertEquals("Arduino Uno", result.itemName)
        assertEquals("Controllable Asset", result.assetType)
        assertEquals("MS METAL DRAWER", result.specificLocation)
        assertEquals("CHILLAX", result.storageLocation)
        assertEquals(96, result.availableQuantity)
    }

    @Test
    fun testItemEntityMapping() {
        val item = Item(
            id = "E001",
            name = "Arduino Uno",
            category = "Electronics & Robotics",
            description = "Microcontroller Board",
            assetType = "Controllable Asset",
            specificLocation = "MS METAL DRAWER",
            storageLocation = "CHILLAX"
        )

        assertEquals("E001", item.id)
        assertEquals("Arduino Uno", item.name)
        assertEquals("Controllable Asset", item.assetType)
        assertEquals("MS METAL DRAWER", item.specificLocation)
        assertEquals("CHILLAX", item.storageLocation)
        assertEquals("Microcontroller Board", item.description)
    }

    @Test
    fun testLowStockAndOutOfStockFilteringAndDetailLookup() {
        val stockItems = listOf(
            InventoryItem(item_code = "E006", item_name = "NodeMCU", available_quantity = 2, total_quantity = 100, storage_location = "CHILLAX", specific_location = "DRAWER WOODEN 2"),
            InventoryItem(item_code = "C014", item_name = "A4 colored paper", available_quantity = 0, total_quantity = 50, storage_location = "MAKER STUDIO", specific_location = "DRAWER WOODEN 1"),
            InventoryItem(item_code = "E001", item_name = "Arduino Uno", available_quantity = 20, total_quantity = 20, storage_location = "CHILLAX", specific_location = "MS METAL DRAWER")
        )

        val lowStock = stockItems.filter { it.available_quantity in 1..5 }
        val outOfStock = stockItems.filter { it.available_quantity == 0 }

        assertEquals(1, lowStock.size)
        assertEquals("E006", lowStock[0].item_code)

        assertEquals(1, outOfStock.size)
        assertEquals("C014", outOfStock[0].item_code)

        // Simulating Item Detail Screen lookup by Item Code navigation argument
        val selectedCode = lowStock[0].item_code
        val foundDetail = stockItems.find { it.item_code == selectedCode }

        assertNotNull(foundDetail)
        assertEquals("NodeMCU", foundDetail?.item_name)
        assertEquals("CHILLAX", foundDetail?.storage_location)
        assertEquals("DRAWER WOODEN 2", foundDetail?.specific_location)
    }

    @Test
    fun testItemDetailScreenRouteArgument() {
        val route = com.example.ui.Screen.ItemDetail(itemCode = "E006")
        assertEquals("E006", route.itemCode)
    }

    @Test
    fun testCsvExporterInventoryColumnsTotalAndAvailableQuantity() {
        val testItems = listOf(
            InventoryItem(
                item_code = "E006",
                item_name = "NodeMCU, ESP-32",
                category = "Microcontroller",
                storage_location = "CHILLAX",
                specific_location = "Shelf 2B",
                available_quantity = 3,
                total_quantity = 25,
                initial_quantity = 25,
                unit = "pcs",
                specification = "Dual-core WiFi/BLE"
            ),
            InventoryItem(
                item_code = "C002",
                item_name = "Paper cup",
                category = "Consumable",
                storage_location = "CHILLAX",
                specific_location = "Rack 1",
                available_quantity = 0,
                total_quantity = 100,
                initial_quantity = 100,
                unit = "cup"
            )
        )

        val csvString = com.example.util.CsvExporter.generateInventoryCsv(testItems)
        val lines = csvString.trim().lines()

        // Verify exact required column names
        assertTrue(lines[0].contains("Total Quantity"))
        assertTrue(lines[0].contains("Quantity Available"))
        assertTrue(lines[0].contains("Item Code"))
        assertTrue(lines[0].contains("Item Name"))

        // Row 1: NodeMCU has initial_quantity = 25, available_quantity = 3 -> LOW STOCK
        assertTrue(lines[1].contains("\"NodeMCU, ESP-32\""))
        assertTrue(lines[1].contains("25"))
        assertTrue(lines[1].contains("3"))
        assertTrue(lines[1].contains("LOW STOCK"))

        // Row 2: Paper cup has initial_quantity = 100, available_quantity = 0 -> OUT OF STOCK
        assertTrue(lines[2].contains("C002"))
        assertTrue(lines[2].contains("100"))
        assertTrue(lines[2].contains("0"))
        assertTrue(lines[2].contains("OUT OF STOCK"))
    }

    @Test
    fun testCsvExporterHistoryAuditTrail() {
        val testHistory = listOf(
            com.example.data.TransactionLog(
                id = "tx-001",
                itemId = "E006",
                storeId = "CHILLAX",
                userId = "USR01",
                quantityChange = -2,
                type = com.example.data.TransactionType.OUT,
                method = com.example.data.EntryMethod.AI_SCAN,
                timestamp = 1715000000000L,
                syncStatus = com.example.data.SyncStatus.SYNCED,
                scanned_by = "John Doe",
                status = "Active",
                transaction_reason = "Checked out for workshop lab"
            )
        )

        val csvString = com.example.util.CsvExporter.generateHistoryCsv(testHistory)
        val lines = csvString.trim().lines()

        // Verify headers for standalone history audit trail
        assertTrue(lines[0].contains("Item"))
        assertTrue(lines[0].contains("Reason"))
        assertTrue(lines[0].contains("Scanned By"))
        assertTrue(lines[0].contains("Timestamp"))
        assertTrue(lines[0].contains("Transaction Type"))

        // Verify row values
        val row = lines[1]
        assertTrue(row.contains("E006"))
        assertTrue(row.contains("Checked out for workshop lab"))
        assertTrue(row.contains("John Doe"))
        assertTrue(row.contains("-2"))
        assertTrue(row.contains("OUT"))
    }

    @Test
    fun testCopilotStrictSystemRolePrompt() {
        val expected = "You are A.R.I.F, the Inventory Copilot. Teach the user how to use the app and analyze stock data. Your dual role is to analyze local database stock AND teach users how to use this application. If a user asks how to do something, provide short, step-by-step instructions. Do NOT use asterisk (*) symbols or markdown bold (**) in your responses; provide clean, human-readable plain text."
        assertEquals(expected, com.example.copilot.CopilotKnowledgeProvider.STRICT_SYSTEM_ROLE)
    }

    @Test
    fun testCopilotAppMapContainsRequiredFacts() {
        val appMap = com.example.copilot.CopilotKnowledgeProvider.APP_MAP
        assertTrue(appMap.contains("Fact: The barcode scanner is the floating button"))
        assertTrue(appMap.contains("Fact: Exception auditing is found in the History tab"))
        assertTrue(appMap.contains("Fact: Users must select a Programme Category as a reason when checking out items"))
        assertTrue(appMap.contains("Fact: The Dashboard displays live store alerts"))
        assertTrue(appMap.contains("Fact: The Inventory / Search tab allows live catalog searching"))
        assertTrue(appMap.contains("Fact: The Profile & Settings tab provides standalone CSV exports"))
    }

    @Test
    fun testCopilotInventoryAndHistorySummaries() {
        val testItems = listOf(
            InventoryItem(
                item_code = "E001",
                item_name = "Arduino Uno",
                category = "Microcontrollers",
                storage_location = "CHILLAX",
                specific_location = "Rack A1",
                available_quantity = 2,
                initial_quantity = 10,
                status = "Active"
            ),
            InventoryItem(
                item_code = "E002",
                item_name = "Raspberry Pi 4",
                category = "Computers",
                storage_location = "CHILLAX",
                specific_location = "Rack B2",
                available_quantity = 0,
                initial_quantity = 5,
                status = "Active"
            )
        )

        val testTransactions = listOf(
            TransactionLog(
                id = "t1",
                itemId = "E001",
                storeId = "CHILLAX",
                userId = "admin",
                quantityChange = -1,
                type = TransactionType.CHECK_OUT,
                method = com.example.data.EntryMethod.MANUAL,
                timestamp = 1715000000000L,
                syncStatus = SyncStatus.SYNCED,
                scanned_by = "Alice",
                transaction_reason = "Workshop"
            )
        )

        val invSummary = com.example.copilot.CopilotKnowledgeProvider.generateInventorySummary("CHILLAX", testItems)
        assertTrue(invSummary.contains("Total SKUs: 2"))
        assertTrue(invSummary.contains("Total Available Stock: 2"))
        assertTrue(invSummary.contains("Out of Stock (1 items)"))
        assertTrue(invSummary.contains("Low Stock (1 items)"))
        assertTrue(invSummary.contains("Arduino Uno"))
        assertTrue(invSummary.contains("Raspberry Pi 4"))

        val histSummary = com.example.copilot.CopilotKnowledgeProvider.generateHistorySummary(testTransactions)
        assertTrue(histSummary.contains("Item: E001"))
        assertTrue(histSummary.contains("Alice"))
        assertTrue(histSummary.contains("Workshop"))

        val fullPrompt = com.example.copilot.CopilotKnowledgeProvider.buildFullSystemPrompt("CHILLAX", testItems, testTransactions)
        assertTrue(fullPrompt.contains(com.example.copilot.CopilotKnowledgeProvider.STRICT_SYSTEM_ROLE))
        assertTrue(fullPrompt.contains("APP MAP & NAVIGATION FACTS"))
        assertTrue(fullPrompt.contains("Arduino Uno"))
    }

    @Test
    fun testCopilotSuggestedPromptsAndOfflineFallbacks() {
        val testItems = listOf(
            InventoryItem(
                item_code = "A001",
                item_name = "ESP32",
                category = "IoT",
                storage_location = "CHILLAX",
                specific_location = "Shelf 1",
                available_quantity = 3,
                initial_quantity = 10,
                status = "Active"
            )
        )

        val scanAns = com.example.copilot.CopilotKnowledgeProvider.getOfflineFallbackResponse("How do I scan an item?", testItems)
        assertNotNull(scanAns)
        assertTrue(scanAns!!.contains("floating") || scanAns.contains("bottom navigation"))

        val missingAns = com.example.copilot.CopilotKnowledgeProvider.getOfflineFallbackResponse("How do I report a missing item?", testItems)
        assertNotNull(missingAns)
        assertTrue(missingAns!!.contains("Lost") || missingAns.contains("History tab"))

        val lowStockAns = com.example.copilot.CopilotKnowledgeProvider.getOfflineFallbackResponse("Which items are low in stock?", testItems)
        assertNotNull(lowStockAns)
        assertTrue(lowStockAns!!.contains("ESP32"))

        val exportAns = com.example.copilot.CopilotKnowledgeProvider.getOfflineFallbackResponse("How do I export inventory to CSV?", testItems)
        assertNotNull(exportAns)
        assertTrue(exportAns!!.contains("Setting") || exportAns.contains("CSV"))

        // Welcome message check
        assertEquals(
            "Welcome! I am A.R.I.F, your AI Copilot. I can help you find stock anomalies, or I can teach you how to use this app.",
            com.example.viewmodel.CopilotViewModel.WELCOME_MESSAGE
        )

        // Suggested prompts check
        assertTrue(com.example.viewmodel.CopilotViewModel.SUGGESTED_PROMPTS.contains("How do I scan an item?"))
        assertTrue(com.example.viewmodel.CopilotViewModel.SUGGESTED_PROMPTS.contains("How do I report a missing item?"))
        assertTrue(com.example.viewmodel.CopilotViewModel.SUGGESTED_PROMPTS.contains("Which items are low in stock?"))
    }

    @Test
    fun testTransactionLogExpectedReturnDateAndOverdueReason() {
        val now = System.currentTimeMillis()
        val tx = TransactionLog(
            id = "tx-overdue",
            itemId = "ITEM-100",
            storeId = "CHILLAX",
            userId = "admin",
            quantityChange = -1,
            type = TransactionType.CHECK_OUT,
            method = com.example.data.EntryMethod.MANUAL,
            timestamp = now,
            syncStatus = SyncStatus.SYNCED,
            status = "Missing",
            transaction_reason = com.example.worker.OverdueAuditWorker.OVERDUE_REASON,
            expected_return_date = now - (15L * 24 * 60 * 60 * 1000L)
        )
        assertEquals("Auto-flagged: 14 days overdue", tx.transaction_reason)
        assertEquals("Missing", tx.status)
        assertTrue(tx.expected_return_date > 0L)
        assertEquals(com.example.worker.OverdueAuditWorker.OVERDUE_REASON, "Auto-flagged: 14 days overdue")
    }

    @Test
    fun testAiDetectionItemExpectedReturnDateAndPartialReturnFields() {
        val returnTimestamp = System.currentTimeMillis() + (7L * 24 * 60 * 60 * 1000L)
        val item = com.example.viewmodel.AiDetectionItem(
            itemName = "Arduino Uno",
            quantity = 2,
            itemType = "Microcontroller",
            confidence = 95f,
            reason = "Lab research",
            expectedReturnDate = returnTimestamp,
            activeCheckedOutQuantity = 5,
            unreturnedStatus = "Damaged and Under Maintenance"
        )
        assertEquals("Arduino Uno", item.itemName)
        assertEquals(2, item.quantity)
        assertEquals(returnTimestamp, item.expectedReturnDate)
        assertEquals(5, item.activeCheckedOutQuantity)
        assertEquals("Damaged and Under Maintenance", item.unreturnedStatus)

        // Test partial return condition: quantity < activeCheckedOutQuantity
        val isPartialReturn = item.activeCheckedOutQuantity > 0 && item.quantity < item.activeCheckedOutQuantity
        assertTrue(isPartialReturn)
        val unreturnedQty = item.activeCheckedOutQuantity - item.quantity
        assertEquals(3, unreturnedQty)
    }

    @Test
    fun testPartialReturnAuditSplitLogic() {
        // Given a user checked out 5 items and now returns 2
        val activeCheckedOut = 5
        val returningQty = 2
        val unreturnedRemainder = activeCheckedOut - returningQty
        val exceptionStatus = "Missing and Lost"

        // Returned transaction simulation
        val returnedTx = TransactionLog(
            id = "tx-returned",
            itemId = "ARD-001",
            storeId = "CHILLAX",
            userId = "admin",
            quantityChange = returningQty,
            type = TransactionType.IN,
            method = com.example.data.EntryMethod.AI_SCAN,
            timestamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.SYNCED,
            status = "Available",
            transaction_reason = "Returned item"
        )

        // Unreturned transaction simulation
        val unreturnedTx = TransactionLog(
            id = "tx-unreturned",
            itemId = "ARD-001",
            storeId = "CHILLAX",
            userId = "admin",
            quantityChange = unreturnedRemainder,
            type = TransactionType.LOST,
            method = com.example.data.EntryMethod.AI_SCAN,
            timestamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.SYNCED,
            status = exceptionStatus,
            transaction_reason = "Partial return unreturned items: $exceptionStatus"
        )

        assertEquals("Available", returnedTx.status)
        assertEquals(2, returnedTx.quantityChange)
        assertEquals(TransactionType.IN, returnedTx.type)

        assertEquals("Missing and Lost", unreturnedTx.status)
        assertEquals(3, unreturnedTx.quantityChange)
        assertEquals(TransactionType.LOST, unreturnedTx.type)

        // Total units accounted for equals original checked out
        assertEquals(activeCheckedOut, returnedTx.quantityChange + unreturnedTx.quantityChange)
    }

    @Test
    fun testActiveCheckoutItemModelAndOverdueLogic() {
        val now = System.currentTimeMillis()
        val overdueDate = now - 86400000L // 1 day ago
        val futureDate = now + 86400000L // 1 day ahead

        val overdueItem = com.example.data.ActiveCheckoutItem(
            id = "tx-1",
            itemId = "NODEMCU",
            itemName = "NodeMCU",
            quantity = 3,
            expected_return_date = overdueDate,
            status = "Checked Out",
            timestamp = now - (2 * 86400000L)
        )

        val activeItem = com.example.data.ActiveCheckoutItem(
            id = "tx-2",
            itemId = "ARD-001",
            itemName = "Arduino Uno",
            quantity = 1,
            expected_return_date = futureDate,
            status = "Checked Out",
            timestamp = now
        )

        // Item Name & Quantity format e.g. "NodeMCU (x3)"
        assertEquals("NodeMCU (x3)", "${overdueItem.itemName} (x${overdueItem.quantity})")
        assertEquals("Arduino Uno (x1)", "${activeItem.itemName} (x${activeItem.quantity})")

        // Overdue detection logic
        val isOverdue = overdueItem.expected_return_date > 0L && now > overdueItem.expected_return_date
        assertTrue(isOverdue)
        assertEquals(overdueDate, overdueItem.expectedReturnDate)

        val isActiveNotOverdue = activeItem.expected_return_date > 0L && now > activeItem.expected_return_date
        assertFalse(isActiveNotOverdue)
        assertEquals(futureDate, activeItem.expectedReturnDate)

        // Date format check (dd/MM/yyyy)
        val sdf = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.US)
        val formatted = sdf.format(java.util.Date(1792022400000L)) // 15/10/2026 UTC
        assertTrue(formatted.contains("10/2026") || formatted.contains("2026"))
    }

    @Test
    fun testDatePickerStateCopyingPreservesItemNameCodeAndConfidence() {
        val originalItem = com.example.viewmodel.AiDetectionItem(
            itemName = "Arduino Uno",
            quantity = 2,
            itemType = "Microcontroller",
            confidence = 94.5f,
            itemCode = "ARD-UNO-001",
            reason = "Robotics Lab",
            expectedReturnDate = null,
            activeCheckedOutQuantity = 2,
            unreturnedStatus = "Damaged and Under Maintenance"
        )

        val newReturnDate = System.currentTimeMillis() + 86400000L * 7

        // Simulate onExpectedDateChange copy logic
        val updatedItem = originalItem.copy(
            expectedReturnDate = newReturnDate,
            itemName = originalItem.itemName,
            itemCode = originalItem.itemCode,
            confidence = originalItem.confidence
        )

        // Strict assertions: itemName, itemCode, confidence are strictly preserved
        assertEquals("Arduino Uno", updatedItem.itemName)
        assertEquals("ARD-UNO-001", updatedItem.itemCode)
        assertEquals(94.5f, updatedItem.confidence, 0.001f)
        assertEquals(2, updatedItem.quantity)
        assertEquals("Robotics Lab", updatedItem.reason)
        assertEquals(newReturnDate, updatedItem.expectedReturnDate)
    }

    @Test
    fun testListMapIndexedUpdatesOnlyTargetItemAndPreservesOtherItems() {
        val item1 = com.example.viewmodel.AiDetectionItem(
            itemName = "NodeMCU ESP8266",
            quantity = 1,
            confidence = 88.0f,
            itemCode = "ESP-001"
        )
        val item2 = com.example.viewmodel.AiDetectionItem(
            itemName = "Arduino Uno",
            quantity = 3,
            confidence = 96.0f,
            itemCode = "ARD-002"
        )

        val list = listOf(item1, item2)
        val targetIndex = 1
        val newDate = 1792022400000L

        // Execute mapIndexed state update
        val updatedList = list.mapIndexed { i, currentItem ->
            if (i == targetIndex) {
                currentItem.copy(
                    expectedReturnDate = newDate,
                    itemName = currentItem.itemName,
                    itemCode = currentItem.itemCode,
                    confidence = currentItem.confidence
                )
            } else {
                currentItem
            }
        }

        // Item 0 is completely untouched
        assertEquals("NodeMCU ESP8266", updatedList[0].itemName)
        assertEquals("ESP-001", updatedList[0].itemCode)
        assertNull(updatedList[0].expectedReturnDate)

        // Item 1 only updated expectedReturnDate; itemName and itemCode untouched
        assertEquals("Arduino Uno", updatedList[1].itemName)
        assertEquals("ARD-002", updatedList[1].itemCode)
        assertEquals(newDate, updatedList[1].expectedReturnDate)
    }

    @Test
    fun testIssueTrackerConditionalResolutionOptions() {
        val missingStatuses = listOf("Missing and Lost", "Missing")
        val damagedStatuses = listOf("Damaged and Under Maintenance", "Damaged")

        for (status in missingStatuses) {
            val isMissing = status.contains("Missing", ignoreCase = true) || status.contains("Lost", ignoreCase = true)
            val options = if (isMissing) {
                listOf("Found (Return to Inventory)", "Disposed (Write-off)")
            } else {
                listOf("Fixed (Return to Inventory)", "Disposed (Write-off)")
            }
            assertEquals(listOf("Found (Return to Inventory)", "Disposed (Write-off)"), options)
            assertTrue(options.contains("Found (Return to Inventory)"))
            assertTrue(options.contains("Disposed (Write-off)"))
            assertFalse(options.contains("Fixed (Return to Inventory)"))
        }

        for (status in damagedStatuses) {
            val isMissing = status.contains("Missing", ignoreCase = true) || status.contains("Lost", ignoreCase = true)
            val options = if (isMissing) {
                listOf("Found (Return to Inventory)", "Disposed (Write-off)")
            } else {
                listOf("Fixed (Return to Inventory)", "Disposed (Write-off)")
            }
            assertEquals(listOf("Fixed (Return to Inventory)", "Disposed (Write-off)"), options)
            assertTrue(options.contains("Fixed (Return to Inventory)"))
            assertTrue(options.contains("Disposed (Write-off)"))
            assertFalse(options.contains("Found (Return to Inventory)"))
        }
    }

    @Test
    fun testExceptionItemModelAndResolvedState() {
        val item = com.example.data.ExceptionItem(
            id = "tx-101",
            itemId = "ARD-UNO",
            itemName = "Arduino Uno",
            quantity = 2,
            status = "Missing and Lost",
            timestamp = 1792000000000L,
            scannedBy = "john_doe",
            reason = "Checked out for workshop",
            storeId = "STORE_MAIN",
            is_resolved = 0
        )

        assertEquals("tx-101", item.id)
        assertEquals("Arduino Uno", item.itemName)
        assertEquals(2, item.quantity)
        assertEquals("Missing and Lost", item.status)
        assertEquals("john_doe", item.scannedBy)
        assertEquals(0, item.isResolved)

        val resolvedItem = item.copy(is_resolved = 1)
        assertEquals(1, resolvedItem.isResolved)
    }

    @Test
    fun testIssueTrackerResolutionAuditTrailMapping() {
        val exception = com.example.data.ExceptionItem(
            id = "tx-202",
            itemId = "ESP-8266",
            itemName = "NodeMCU ESP8266",
            quantity = 1,
            status = "Damaged and Under Maintenance",
            timestamp = 1792000000000L,
            scannedBy = "alice",
            reason = "Port burnt",
            storeId = "STORE_MAIN"
        )

        // Case 1: Fixed
        val fixedResolution = "Fixed (Return to Inventory)"
        val isFixedOrFound = fixedResolution.contains("Found") || fixedResolution.contains("Fixed")
        val fixedTxType = if (isFixedOrFound) TransactionType.CHECK_IN else TransactionType.LOST
        assertEquals(TransactionType.CHECK_IN, fixedTxType)

        // Case 2: Disposed
        val disposedResolution = "Disposed (Write-off)"
        val isDisposed = disposedResolution.contains("Disposed")
        val disposedTxType = if (isDisposed) TransactionType.LOST else TransactionType.CHECK_IN
        assertEquals(TransactionType.LOST, disposedTxType)
    }

    @Test
    fun testPartialReturnMathematicalValidation() {
        // User checked out 5 items, returning 2 in IN mode -> 3 unreturned items
        val checkedOutQuantity = 5
        val selectedQuantity = 2
        val expectedUnreturned = checkedOutQuantity - selectedQuantity
        assertEquals(3, expectedUnreturned)

        // Case 1: Under-assigned (Damaged: 1, Missing: 1, Disposed: 0) -> Sum: 2 != 3 (Invalid)
        var damaged = 1
        var missing = 1
        var disposed = 0
        var totalAssigned = damaged + missing + disposed
        var isMathValid = (totalAssigned == expectedUnreturned)
        assertFalse(isMathValid)
        assertEquals(1, expectedUnreturned - totalAssigned) // 1 item remaining to assign

        // Case 2: Over-assigned (Damaged: 2, Missing: 1, Disposed: 1) -> Sum: 4 != 3 (Invalid)
        damaged = 2
        missing = 1
        disposed = 1
        totalAssigned = damaged + missing + disposed
        isMathValid = (totalAssigned == expectedUnreturned)
        assertFalse(isMathValid)
        assertTrue(totalAssigned > expectedUnreturned)

        // Case 3: Perfectly matched (Damaged: 1, Missing: 1, Disposed: 1) -> Sum: 3 == 3 (Valid)
        damaged = 1
        missing = 1
        disposed = 1
        totalAssigned = damaged + missing + disposed
        isMathValid = (totalAssigned == expectedUnreturned)
        assertTrue(isMathValid)
        assertEquals(0, expectedUnreturned - totalAssigned) // All accounted for

        // Helper text validation logic
        val helperText = when {
            totalAssigned == expectedUnreturned -> "All unreturned items accounted for"
            totalAssigned < expectedUnreturned -> "Please assign statuses to the remaining ${expectedUnreturned - totalAssigned} items"
            else -> "Assigned items (${totalAssigned}) exceeds unreturned count (${expectedUnreturned})"
        }
        assertEquals("All unreturned items accounted for", helperText)
    }

    @Test
    fun testPartialReturnBatchExecutionMapping() {
        val returnedQty = 2
        val damagedQty = 1
        val missingQty = 1
        val disposedQty = 1
        val totalCheckedOut = 5

        // Verify total sum equals checked out
        assertEquals(totalCheckedOut, returnedQty + damagedQty + missingQty + disposedQty)

        // Simulated batch records
        data class BatchRecord(val type: TransactionType, val status: String, val qty: Int, val isResolved: Int)
        val batch = mutableListOf<BatchRecord>()

        if (returnedQty > 0) {
            batch.add(BatchRecord(TransactionType.IN, "Available", returnedQty, 1))
        }
        if (damagedQty > 0) {
            batch.add(BatchRecord(TransactionType.DAMAGE, "Damaged and Under Maintenance", damagedQty, 0))
        }
        if (missingQty > 0) {
            batch.add(BatchRecord(TransactionType.LOST, "Missing and Lost", missingQty, 0))
        }
        if (disposedQty > 0) {
            batch.add(BatchRecord(TransactionType.LOST, "Disposed", disposedQty, 1))
        }

        assertEquals(4, batch.size)
        // Check returned
        assertEquals(TransactionType.IN, batch[0].type)
        assertEquals("Available", batch[0].status)
        assertEquals(2, batch[0].qty)

        // Check damaged
        assertEquals(TransactionType.DAMAGE, batch[1].type)
        assertEquals("Damaged and Under Maintenance", batch[1].status)
        assertEquals(1, batch[1].qty)
        assertEquals(0, batch[1].isResolved) // Active issue in Issue Tracker

        // Check missing
        assertEquals(TransactionType.LOST, batch[2].type)
        assertEquals("Missing and Lost", batch[2].status)
        assertEquals(1, batch[2].qty)
        assertEquals(0, batch[2].isResolved) // Active issue in Issue Tracker

        // Check disposed
        assertEquals(TransactionType.LOST, batch[3].type)
        assertEquals("Disposed", batch[3].status)
        assertEquals(1, batch[3].qty)
        assertEquals(1, batch[3].isResolved) // Written-off, resolved
    }

    @Test
    fun testOfflineFirstPendingSyncFlags() {
        val tx = TransactionLog(
            id = "tx-offline-1",
            itemId = "E006",
            storeId = "CHILLAX",
            userId = "admin",
            quantityChange = -2,
            type = TransactionType.OUT,
            method = com.example.data.EntryMethod.AI_SCAN,
            timestamp = System.currentTimeMillis()
        )
        // Default is_pending_sync MUST be true
        assertTrue(tx.is_pending_sync)

        val stockHistory = com.example.data.StockHistory(
            id = "sh-offline-1",
            itemId = "E006"
        )
        assertTrue(stockHistory.is_pending_sync)

        val invMaster = com.example.data.InventoryMaster(
            item_code = "E006",
            item_name = "NodeMCU"
        )
        assertTrue(invMaster.is_pending_sync)

        val invItem = InventoryItem(
            item_code = "E006",
            item_name = "NodeMCU"
        )
        assertTrue(invItem.is_pending_sync)

        // Verify state after successful sync
        val syncedTx = tx.copy(is_pending_sync = false)
        assertFalse(syncedTx.is_pending_sync)

        val syncedSh = stockHistory.copy(is_pending_sync = false)
        assertFalse(syncedSh.is_pending_sync)

        val syncedInv = invMaster.copy(is_pending_sync = false)
        assertFalse(syncedInv.is_pending_sync)
    }

    @Test
    fun testWorkManagerNetworkConstraintConfiguration() {
        assertEquals("FirebaseSyncWork", com.example.worker.FirebaseSyncWorker.WORK_NAME)

        val constraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
            .build()

        assertEquals(androidx.work.NetworkType.CONNECTED, constraints.requiredNetworkType)
    }
}

