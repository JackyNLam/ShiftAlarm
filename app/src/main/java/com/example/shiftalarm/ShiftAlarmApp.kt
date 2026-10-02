package com.example.shiftalarm

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.shiftalarm.alarm.CheckNearAlarmWorker
import com.example.shiftalarm.data.JsonStorage
import com.example.shiftalarm.data.repository.ShiftRepository
import java.util.concurrent.TimeUnit

class ShiftAlarmApp : Application() {

    lateinit var repository: ShiftRepository
        private set
    lateinit var storage: JsonStorage
        private set

    override fun onCreate() {
        super.onCreate()
        storage = JsonStorage(this)
        repository = ShiftRepository(storage)

        // Schedule a periodic check: every 1 hour, evaluate if the next alarm
        // is within 23 hours and, if so, silently add it to the system Clock app.
        // UPDATE policy replaces the old 6-hour schedule on already-installed devices.
        // WorkManager persists this schedule across device reboot automatically.
        val checkRequest = PeriodicWorkRequestBuilder<CheckNearAlarmWorker>(
            1, TimeUnit.HOURS
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            CheckNearAlarmWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            checkRequest
        )
    }
}