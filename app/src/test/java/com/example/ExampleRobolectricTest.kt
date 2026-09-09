package com.example

import android.content.Context
import com.example.smartinventory.R
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Smart Inventory", appName)
  }

  @Test
  fun `test cup stacking with multiple rims inside full cup`() {
    // A stack of 4 cups: 1 full_cup enclosing 4 cup_rims
    val fullCup = com.example.ml.ObjectDetector.DetectionResult(
      label = "full_cup",
      confidence = 0.90f,
      boundingBox = android.graphics.RectF(0.2f, 0.2f, 0.5f, 0.8f)
    )
    val rims = (1..4).map { i ->
      com.example.ml.ObjectDetector.DetectionResult(
        label = "cup_rim",
        confidence = 0.85f,
        boundingBox = android.graphics.RectF(0.25f, 0.2f + (i * 0.05f), 0.45f, 0.3f + (i * 0.05f))
      )
    }

    val results = com.example.viewmodel.InventoryViewModel.aggregateDetections(listOf(fullCup) + rims)
    assertEquals(1, results.size)
    assertEquals("Paper cup", results[0].itemName)
    assertEquals(4, results[0].quantity)
  }

  @Test
  fun `test standalone cup with zero rims counts as one cup`() {
    val standaloneCup = com.example.ml.ObjectDetector.DetectionResult(
      label = "full_cup",
      confidence = 0.92f,
      boundingBox = android.graphics.RectF(0.1f, 0.1f, 0.4f, 0.5f)
    )

    val results = com.example.viewmodel.InventoryViewModel.aggregateDetections(listOf(standaloneCup))
    assertEquals(1, results.size)
    assertEquals("Paper cup", results[0].itemName)
    assertEquals(1, results[0].quantity)
  }

  @Test
  fun `test two separate stacks on table`() {
    // Stack 1: 3 cups
    val stack1 = com.example.ml.ObjectDetector.DetectionResult(
      label = "full_cup",
      confidence = 0.90f,
      boundingBox = android.graphics.RectF(0.1f, 0.2f, 0.3f, 0.7f)
    )
    val rims1 = (1..3).map { i ->
      com.example.ml.ObjectDetector.DetectionResult(
        label = "cup_rim",
        confidence = 0.88f,
        boundingBox = android.graphics.RectF(0.12f, 0.2f + (i * 0.04f), 0.28f, 0.25f + (i * 0.04f))
      )
    }

    // Stack 2: 2 cups
    val stack2 = com.example.ml.ObjectDetector.DetectionResult(
      label = "full_cup",
      confidence = 0.95f,
      boundingBox = android.graphics.RectF(0.6f, 0.2f, 0.8f, 0.7f)
    )
    val rims2 = (1..2).map { i ->
      com.example.ml.ObjectDetector.DetectionResult(
        label = "cup_rim",
        confidence = 0.87f,
        boundingBox = android.graphics.RectF(0.62f, 0.2f + (i * 0.04f), 0.78f, 0.25f + (i * 0.04f))
      )
    }

    val results = com.example.viewmodel.InventoryViewModel.aggregateDetections(
      listOf(stack1, stack2) + rims1 + rims2
    )
    assertEquals(1, results.size)
    assertEquals("Paper cup", results[0].itemName)
    assertEquals(5, results[0].quantity) // 3 + 2 = 5 cups
  }
}
