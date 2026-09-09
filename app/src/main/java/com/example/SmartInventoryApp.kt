package com.example

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.worker.OverdueAuditWorker
import java.util.concurrent.TimeUnit

class SmartInventoryApp : Application() {

    override fun onCreate() {
        super.onCreate()
        setupOverdueAuditWorker()
    }

    private fun setupOverdueAuditWorker() {
        try {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val periodicWorkRequest = PeriodicWorkRequestBuilder<OverdueAuditWorker>(
                24, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                OverdueAuditWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicWorkRequest
            )
        } catch (e: Exception) {
            android.util.Log.e("SmartInventoryApp", "Failed to schedule OverdueAuditWorker", e)
        }
    }
}
