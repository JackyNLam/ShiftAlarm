package com.example.shiftalarm.alarm

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Periodic worker that checks if the next upcoming alarm is within 23 hours
 * and, if so, silently adds it to the system Clock app via a deferred
 * [SystemAlarmSyncWorker].
 *
 * Scheduled every 1 hour from [ShiftAlarmApp.onCreate] and survives
 * device reboot via WorkManager's persistent scheduling.
 */
class CheckNearAlarmWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "Periodic check: evaluating next alarm for 23-hour window")
        val scheduler = AlarmScheduler(applicationContext)
        scheduler.autoSetSystemAlarmIfNear()
        Log.d(TAG, "Periodic check complete")
        return Result.success()
    }

    companion object {
        private const val TAG = "CheckNearAlarmWorker"
        const val UNIQUE_WORK_NAME = "check_near_alarm"
    }
}