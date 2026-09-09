package com.example.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.NavController
import com.example.data.EntryResult
import com.example.data.ScannedItemResult
import com.example.data.TransactionType
import com.example.viewmodel.DetectionSource
import com.example.viewmodel.InventoryViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScanScreen(viewModel: InventoryViewModel, navController: NavController) {
    val cameraPermissionState = rememberPermissionState(android.Manifest.permission.CAMERA)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val aiScanResult by viewModel.aiScanResult.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val scannedItem by viewModel.scannedItem.collectAsState()
    val showItemDetails by viewModel.showItemDetails.collectAsState()
    val scanErrorMessage by viewModel.scanErrorMessage.collectAsState()
    val isItemCodeScanning by viewModel.isItemCodeScanning.collectAsState()

    // Full item details dialog: ONLY displayed when user clicks the scanned item on the confirmation screen
    if (showItemDetails && scannedItem != null) {
        ScannedItemDetailDialog(
            item = scannedItem!!,
            viewModel = viewModel,
            onDismiss = { viewModel.dismissScannedItemDetails() },
            onScanAnother = { viewModel.clearScannedItem() }
        )
    }

    // Error message dialog when an item code is not found
    if (scanErrorMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearScannedItem() },
            icon = { Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Item Code Scan", fontWeight = FontWeight.Bold) },
            text = { Text(scanErrorMessage ?: "") },
            confirmButton = {
                TextButton(onClick = { viewModel.clearScannedItem() }) {
                    Text("OK")
                }
            }
        )
    }

    if (cameraPermissionState.status.isGranted) {
        if (scannedItem != null) {
            // Sequential Step 2: Navigate to confirmation screen after barcode scan
            BarcodeScanConfirmationScreen(
                item = scannedItem!!,
                onItemClick = { viewModel.showScannedItemDetails() }, // Sequential Step 3: User clicks item
                onScanAnother = { viewModel.clearScannedItem() },
                onBack = { viewModel.clearScannedItem() }
            )
        } else if (aiScanResult != null && !isScanning) {
            AiResultScreen(viewModel, navController)
        } else {
            // Initial Step: Scanner is active, details completely hidden
            CameraPreviewView(
                context = context,
                lifecycleOwner = lifecycleOwner,
                isScanning = isScanning,
                isItemCodeScanning = isItemCodeScanning,
                viewModel = viewModel,
                onImageCaptured = { bitmap ->
                    viewModel.processImageSmart(bitmap, context)
                },
                onScanCode = { code ->
                    viewModel.scanItemCode(code)
                },
                onClose = { navController.popBackStack() }
            )
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Camera permission is required to scan items.")
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                Text("Request Permission")
            }
        }
    }
}

/**
 * Sequential Step 2: Confirmation screen displayed after barcode scan.
 * Item details are not shown until the user taps the scanned item card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarcodeScanConfirmationScreen(
    item: ScannedItemResult,
    onItemClick: () -> Unit,
    onScanAnother: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan Confirmation", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Success header
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF10B981).copy(alpha = 0.12f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF10B981),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "Barcode Scanned",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF10B981)
                        )
                        Text(
                            text = "Item recognized from database. Click the item below to view full specifications.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Text(
                text = "Scanned Item",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            // Clickable Scanned Item Card (Sequential Step 3)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onItemClick),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "ITEM CODE: ${item.itemCode}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }

                        if (item.storageLocation.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = item.storageLocation,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = item.itemName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Tap here to display full item details",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            OutlinedButton(
                onClick = onScanAnother,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Scan Another Barcode", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun CameraPreviewView(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    isScanning: Boolean,
    isItemCodeScanning: Boolean,
    viewModel: InventoryViewModel,
    onImageCaptured: (Bitmap) -> Unit,
    onScanCode: (String) -> Unit,
    onClose: () -> Unit
) {
    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }
    val detectionSource by viewModel.detectionSource.collectAsState()

    DisposableEffect(lifecycleOwner) {
        onDispose {
            try {
                val cameraProvider = ProcessCameraProvider.getInstance(context).get()
                cameraProvider.unbindAll()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // --- Camera preview ---
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner, cameraSelector, preview, imageCapture
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // --- Top bar overlay with close button & title ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.5f)
            ) {
                Text(
                    text = "Scan Barcode / Item",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            Spacer(modifier = Modifier.size(44.dp))
        }

        // --- Scanning overlay ---
        if (isScanning || isItemCodeScanning) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        if (isItemCodeScanning) "Looking up Item Code in database…" else "Analyzing image…",
                        color = Color.White,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        if (isItemCodeScanning) "Querying Storage Location & Specific Rack" else "Trying on-device detection first",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                }
            }
        }

        // --- Viewfinder guide box ---
        if (!isScanning && !isItemCodeScanning) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(240.dp)
                    .background(Color.Transparent)
            )
            CornerFrame(modifier = Modifier.align(Alignment.Center))
        }

        // --- Capture button ---
        Button(
            onClick = {
                if (isScanning || isItemCodeScanning) return@Button
                val capture = imageCapture ?: return@Button
                capture.takePicture(
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            val bitmap = imageProxyToBitmap(image)
                            image.close()
                            onImageCaptured(bitmap)
                        }
                        override fun onError(exception: ImageCaptureException) {
                            exception.printStackTrace()
                        }
                    }
                )
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 36.dp)
                .size(72.dp),
            shape = CircleShape,
            enabled = !isScanning && !isItemCodeScanning,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(Icons.Default.CameraAlt, contentDescription = "Capture", modifier = Modifier.size(32.dp))
        }

        // --- Hint label ---
        if (!isScanning && !isItemCodeScanning) {
            Text(
                text = "Point camera at item or barcode to scan",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 116.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

/**
 * Sequential Step 4: Modal dialog that displays full item details when the user clicks the scanned item.
 * Retrieved directly from inventory_master with exact Storage Location and Specific Location / Rack.
 */
@Composable
fun ScannedItemDetailDialog(
    item: ScannedItemResult,
    viewModel: InventoryViewModel,
    onDismiss: () -> Unit,
    onScanAnother: () -> Unit
) {
    val context = LocalContext.current
    var wrongStoreError by remember { mutableStateOf<String?>(null) }

    if (wrongStoreError != null) {
        AlertDialog(
            onDismissRequest = { wrongStoreError = null },
            icon = { Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Wrong Store Location", fontWeight = FontWeight.Bold) },
            text = { Text(wrongStoreError ?: "") },
            confirmButton = {
                TextButton(onClick = { wrongStoreError = null }) {
                    Text("OK")
                }
            }
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header with title and close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.QrCodeScanner,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Scanned Item",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Item Code pill
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = "ITEM CODE: ${item.itemCode}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Item Name (Prominently displayed)
                Text(
                    text = item.itemName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Highlighted Card 1: Asset Type
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Default.Category,
                                contentDescription = "Asset Type",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                "Asset Type",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = item.assetType.ifEmpty { "Not Specified" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Highlighted Card 2: Specific Location / Rack
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Default.Place,
                                contentDescription = "Location",
                                tint = MaterialTheme.colorScheme.onTertiary,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                "Specific Location / Rack",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = item.specificLocation.ifEmpty { "Not Specified" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Storage Location & Stock details
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Storage Location", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = item.storageLocation.ifEmpty { "General" },
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Available Quantity", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = "${item.availableQuantity} ${item.unit}".trim(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Actions: Check In / Check Out quick buttons (strictly validates location)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            viewModel.manualEntry(item.itemCode, 1, TransactionType.CHECK_IN) { result ->
                                when (result.status) {
                                    EntryResult.SUCCESS -> {
                                        Toast.makeText(context, "Stored 1x ${item.itemName} in ${item.storageLocation}", Toast.LENGTH_SHORT).show()
                                        onDismiss()
                                    }
                                    EntryResult.WRONG_STORE -> {
                                        val msg = "Cannot update inventory for this item. Wrong store. Please go to the correct store: ${result.correctStore.ifEmpty { item.storageLocation }}"
                                        wrongStoreError = msg
                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    }
                                    EntryResult.INSUFFICIENT_STOCK -> {
                                        Toast.makeText(context, "Not enough stock", Toast.LENGTH_SHORT).show()
                                    }
                                    EntryResult.ITEM_NOT_FOUND -> {
                                        Toast.makeText(context, "Item Not Found", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                    ) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Check In")
                    }

                    Button(
                        onClick = {
                            viewModel.manualEntry(item.itemCode, 1, TransactionType.CHECK_OUT) { result ->
                                when (result.status) {
                                    EntryResult.SUCCESS -> {
                                        Toast.makeText(context, "Checked out 1x ${item.itemName} from ${item.storageLocation}", Toast.LENGTH_SHORT).show()
                                        onDismiss()
                                    }
                                    EntryResult.WRONG_STORE -> {
                                        val msg = "Cannot update inventory for this item. Wrong store. Please go to the correct store: ${result.correctStore.ifEmpty { item.storageLocation }}"
                                        wrongStoreError = msg
                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    }
                                    EntryResult.INSUFFICIENT_STOCK -> {
                                        Toast.makeText(context, "Not enough stock", Toast.LENGTH_SHORT).show()
                                    }
                                    EntryResult.ITEM_NOT_FOUND -> {
                                        Toast.makeText(context, "Item Not Found", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                    ) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Check Out")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = {
                        onDismiss()
                        onScanAnother()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Scan Another Item")
                }
            }
        }
    }
}

/** Draws a simple corner-bracket viewfinder frame. */
@Composable
private fun CornerFrame(modifier: Modifier = Modifier) {
    val strokeColor = Color.White.copy(alpha = 0.85f)
    val cornerSize = 32.dp
    val strokeWidth = 3.dp

    Box(modifier = modifier.size(240.dp)) {
        // Top-left
        Box(modifier = Modifier.align(Alignment.TopStart)) {
            Box(modifier = Modifier.width(cornerSize).height(strokeWidth).background(strokeColor))
            Box(modifier = Modifier.width(strokeWidth).height(cornerSize).background(strokeColor))
        }
        // Top-right
        Box(modifier = Modifier.align(Alignment.TopEnd)) {
            Box(modifier = Modifier.width(cornerSize).height(strokeWidth).background(strokeColor).align(Alignment.TopEnd))
            Box(modifier = Modifier.width(strokeWidth).height(cornerSize).background(strokeColor).align(Alignment.TopEnd))
        }
        // Bottom-left
        Box(modifier = Modifier.align(Alignment.BottomStart)) {
            Box(modifier = Modifier.width(strokeWidth).height(cornerSize).background(strokeColor).align(Alignment.BottomStart))
            Box(modifier = Modifier.width(strokeWidth).height(cornerSize).background(strokeColor).align(Alignment.BottomStart))
        }
        // Bottom-right
        Box(modifier = Modifier.align(Alignment.BottomEnd)) {
            Box(modifier = Modifier.width(cornerSize).height(strokeWidth).background(strokeColor).align(Alignment.BottomEnd))
            Box(modifier = Modifier.width(strokeWidth).height(cornerSize).background(strokeColor).align(Alignment.BottomEnd))
        }
    }
}

private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
    val buffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, null)

    val matrix = Matrix()
    matrix.postRotate(image.imageInfo.rotationDegrees.toFloat())
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
