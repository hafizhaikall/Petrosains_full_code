package com.example.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * On-device object detector using raw TFLite / LiteRT Interpreter.
 * Fully supports Ultralytics YOLOv8 / YOLOv10 exports:
 * - NCHW [1, 3, 640, 640] or NHWC [1, 640, 640, 3] input formats
 * - Transposed [1, 4+classes, 8400] or [1, 8400, 4+classes] or [1, 300, 6] output formats
 * - Non-Maximum Suppression (NMS) for accurate multi-object counting
 */
class ObjectDetector(private val context: Context) {

    data class DetectionResult(
        val label: String,
        val confidence: Float,
        val boundingBox: RectF
    )

    private val interpreter: Interpreter
    private val labels: List<String>

    private val isNCHW: Boolean
    private val inputWidth: Int
    private val inputHeight: Int

    companion object {
        private const val TAG = "ObjectDetector"
        private const val CONFIDENCE_THRESHOLD = 0.25f
        private const val IOU_THRESHOLD = 0.45f
    }

    init {
        val model = loadModelFile(context, "weightsv10.tflite")
        val options = Interpreter.Options().apply {
            numThreads = 4
        }
        interpreter = Interpreter(model, options)

        // Read input tensor shape
        val inputTensor = interpreter.getInputTensor(0)
        val inShape = inputTensor.shape()
        Log.i(TAG, "Model input shape: ${inShape.contentToString()}, dtype: ${inputTensor.dataType()}")

        if (inShape.size == 4 && inShape[1] == 3) {
            // NCHW: [1, 3, H, W]
            isNCHW = true
            inputHeight = inShape[2]
            inputWidth  = inShape[3]
        } else {
            // NHWC: [1, H, W, 3]
            isNCHW = false
            inputHeight = inShape[1]
            inputWidth  = inShape[2]
        }
        Log.i(TAG, "Configured input: ${inputWidth}x${inputHeight}, isNCHW=$isNCHW")

        // Read output tensor shape
        val outTensor = interpreter.getOutputTensor(0)
        Log.i(TAG, "Model output shape: ${outTensor.shape().contentToString()}, dtype: ${outTensor.dataType()}")

        // Load labels
        labels = context.assets.open("labels.txt").bufferedReader().readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        Log.i(TAG, "Loaded ${labels.size} labels: $labels")
    }

    /**
     * Run inference on bitmap.
     * @return detections sorted by confidence descending.
     */
    fun detect(bitmap: Bitmap): List<DetectionResult> {
        return try {
            val resized = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
            val inputBuffer = bitmapToByteBuffer(resized)

            val outShape = interpreter.getOutputTensor(0).shape()
            val rawDetections = mutableListOf<DetectionResult>()

            if (outShape.size == 3 && outShape[1] < outShape[2]) {
                // Shape [1, 19, 8400]: dim1 = 4 + numClasses (19), dim2 = numBoxes (8400)
                val features = outShape[1]
                val numBoxes = outShape[2]
                val output = Array(1) { Array(features) { FloatArray(numBoxes) } }
                interpreter.run(inputBuffer, output)

                val featArray = output[0]
                for (j in 0 until numBoxes) {
                    var maxScore = 0f
                    var maxClassId = 0
                    for (c in 0 until labels.size) {
                        val score = featArray[4 + c][j]
                        if (score > maxScore) {
                            maxScore = score
                            maxClassId = c
                        }
                    }

                    if (maxScore >= CONFIDENCE_THRESHOLD) {
                        var cx = featArray[0][j]
                        var cy = featArray[1][j]
                        var w  = featArray[2][j]
                        var h  = featArray[3][j]

                        // Normalize coordinates to 0..1 if in pixel coordinates
                        if (cx > 1.0f || w > 1.0f) {
                            cx /= inputWidth
                            cy /= inputHeight
                            w  /= inputWidth
                            h  /= inputHeight
                        }

                        val box = RectF(
                            (cx - w / 2f).coerceIn(0f, 1f),
                            (cy - h / 2f).coerceIn(0f, 1f),
                            (cx + w / 2f).coerceIn(0f, 1f),
                            (cy + h / 2f).coerceIn(0f, 1f)
                        )
                        val label = labels.getOrElse(maxClassId) { "item_$maxClassId" }
                        Log.i(TAG, "Detection candidate: maxClassId=$maxClassId, label='$label', score=$maxScore")
                        rawDetections.add(DetectionResult(label, maxScore, box))
                    }
                }

            } else if (outShape.size == 3 && outShape[1] >= outShape[2]) {
                // Shape [1, numBoxes, features]
                val numBoxes = outShape[1]
                val features = outShape[2]
                val output = Array(1) { Array(numBoxes) { FloatArray(features) } }
                interpreter.run(inputBuffer, output)

                val rows = output[0]
                for (i in 0 until numBoxes) {
                    val row = rows[i]
                    if (features == 6) {
                        // [x1, y1, x2, y2, score, class_id]
                        val score = row[4]
                        if (score >= CONFIDENCE_THRESHOLD) {
                            val classId = row[5].toInt().coerceIn(0, labels.size - 1)
                            val label = labels.getOrElse(classId) { "item_$classId" }
                            val box = RectF(
                                row[0].coerceIn(0f, 1f),
                                row[1].coerceIn(0f, 1f),
                                row[2].coerceIn(0f, 1f),
                                row[3].coerceIn(0f, 1f)
                            )
                            Log.i(TAG, "Detection candidate (features=6): classId=$classId, label='$label', score=$score")
                            rawDetections.add(DetectionResult(label, score, box))
                        }
                    } else if (features >= 4 + labels.size) {
                        var maxScore = 0f
                        var maxClassId = 0
                        for (c in 0 until labels.size) {
                            val score = row[4 + c]
                            if (score > maxScore) {
                                maxScore = score
                                maxClassId = c
                            }
                        }
                        if (maxScore >= CONFIDENCE_THRESHOLD) {
                            var cx = row[0]; var cy = row[1]; var w = row[2]; var h = row[3]
                            if (cx > 1.0f || w > 1.0f) {
                                cx /= inputWidth; cy /= inputHeight
                                w  /= inputWidth; h  /= inputHeight
                            }
                            val box = RectF(
                                (cx - w / 2f).coerceIn(0f, 1f),
                                (cy - h / 2f).coerceIn(0f, 1f),
                                (cx + w / 2f).coerceIn(0f, 1f),
                                (cy + h / 2f).coerceIn(0f, 1f)
                            )
                            val label = labels.getOrElse(maxClassId) { "item_$maxClassId" }
                            Log.i(TAG, "Detection candidate (features=$features): maxClassId=$maxClassId, label='$label', score=$maxScore")
                            rawDetections.add(DetectionResult(label, maxScore, box))
                        }
                    }
                }
            }

            // Apply Non-Maximum Suppression (NMS)
            val filtered = applyNms(rawDetections, IOU_THRESHOLD)
            Log.i(TAG, "Detections: raw=${rawDetections.size}, after NMS=${filtered.size}")
            filtered.forEach { Log.i(TAG, " -> ${it.label}: ${(it.confidence * 100).toInt()}% at ${it.boundingBox}") }
            filtered

        } catch (e: Exception) {
            Log.e(TAG, "Error during inference", e)
            emptyList()
        }
    }

    private fun applyNms(detections: List<DetectionResult>, iouThreshold: Float): List<DetectionResult> {
        val sorted = detections.sortedByDescending { it.confidence }.toMutableList()
        val kept = mutableListOf<DetectionResult>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            kept.add(best)

            val it = sorted.iterator()
            while (it.hasNext()) {
                val candidate = it.next()
                if (candidate.label == best.label) {
                    val iou = computeIoU(best.boundingBox, candidate.boundingBox)
                    if (iou >= iouThreshold) {
                        it.remove()
                    }
                }
            }
        }
        return kept
    }

    private fun computeIoU(boxA: RectF, boxB: RectF): Float {
        val xA = maxOf(boxA.left, boxB.left)
        val yA = maxOf(boxA.top, boxB.top)
        val xB = minOf(boxA.right, boxB.right)
        val yB = minOf(boxA.bottom, boxB.bottom)

        val interArea = maxOf(0f, xB - xA) * maxOf(0f, yB - yA)
        val boxAArea = (boxA.right - boxA.left) * (boxA.bottom - boxA.top)
        val boxBArea = (boxB.right - boxB.left) * (boxB.bottom - boxB.top)
        val unionArea = boxAArea + boxBArea - interArea

        return if (unionArea > 0f) interArea / unionArea else 0f
    }

    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(1 * 3 * inputHeight * inputWidth * 4)
        buffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(inputWidth * inputHeight)
        bitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        if (isNCHW) {
            // NCHW: All Red, then all Green, then all Blue
            // R channel
            for (p in pixels) {
                buffer.putFloat(((p shr 16) and 0xFF) / 255.0f)
            }
            // G channel
            for (p in pixels) {
                buffer.putFloat(((p shr 8) and 0xFF) / 255.0f)
            }
            // B channel
            for (p in pixels) {
                buffer.putFloat((p and 0xFF) / 255.0f)
            }
        } else {
            // NHWC: RGB interleaved
            for (p in pixels) {
                buffer.putFloat(((p shr 16) and 0xFF) / 255.0f)
                buffer.putFloat(((p shr 8) and 0xFF) / 255.0f)
                buffer.putFloat((p and 0xFF) / 255.0f)
            }
        }

        buffer.rewind()
        return buffer
    }

    private fun loadModelFile(context: Context, filename: String): MappedByteBuffer {
        val assetFd = context.assets.openFd(filename)
        val inputStream = FileInputStream(assetFd.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, assetFd.startOffset, assetFd.declaredLength)
    }

    fun close() {
        interpreter.close()
    }
}
