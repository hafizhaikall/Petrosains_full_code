package com.example.ml

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CameraX ImageAnalysis Analyzer for real-time object detection.
 * Converts incoming CameraX ImageProxy frames into Bitmaps and feeds them to the ObjectDetector.
 */
class RealTimeAnalyzer(
    private val detector: ObjectDetector,
    private val roiRect: android.graphics.RectF? = null,
    private val onDetections: (List<ObjectDetector.DetectionResult>, Int, Int) -> Unit
) : ImageAnalysis.Analyzer {

    private val scope = CoroutineScope(Dispatchers.Default)
    private var isProcessing = false

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        // Drop frames if we are already processing one to prevent backlog
        if (isProcessing) {
            imageProxy.close()
            return
        }
        isProcessing = true

        val bitmap = imageProxy.toBitmap()
        
        // The camera sensor might be rotated, so we rotate the bitmap to upright
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees.toFloat()
        val rotatedBitmap = if (rotationDegrees != 0f) {
            val matrix = Matrix().apply { postRotate(rotationDegrees) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }

        val frameWidth = rotatedBitmap.width
        val frameHeight = rotatedBitmap.height

        scope.launch {
            try {
                // Run detection on the background thread
                val results = detector.detect(rotatedBitmap)
                
                // Filter results to only keep items inside the Region of Interest (ROI)
                val filtered = if (roiRect != null) {
                    results.filter { result ->
                        val cx = (result.boundingBox.left + result.boundingBox.right) / 2f
                        val cy = (result.boundingBox.top + result.boundingBox.bottom) / 2f
                        roiRect.contains(cx, cy)
                    }
                } else {
                    results
                }
                
                withContext(Dispatchers.Main) {
                    onDetections(filtered, frameWidth, frameHeight)
                }
            } finally {
                imageProxy.close()
                isProcessing = false
            }
        }
    }
}
