package com.example.shiftalarm.alarm

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONArray

/**
 * Deferred system-alarm sync worker.
 *
 * Runs when the device is idle (locked screen), at which point many Chinese
 * ROMs (Honor MagicOS, Huawei EMUI, MIUI, etc.) grant a brief grace period
 * during which `ACTION_SET_ALARM` calls are allowed as a continuation of
 * recent user activity.
 *
 * Reads pending alarms from SharedPreferences, fires each one via
 * [AlarmClock.ACTION_SET_ALARM], then clears the queue.
 */
class SystemAlarmSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_PENDING_ALARMS, "[]") ?: "[]"
        val arr = JSONArray(json)

        if (arr.length() == 0) {
            Log.d(TAG, "No pending system alarms — nothing to sync")
            return Result.success()
        }

        Log.d(TAG, "Syncing ${arr.length()} pending system alarm(s) (idle-triggered)")

        val failedAlarms = JSONArray()

        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val hour = obj.getInt("hour")
            val minute = obj.getInt("minute")
            val message = obj.optString("message", "")

            try {
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(AlarmClock.EXTRA_MESSAGE, message.take(80))
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                applicationContext.startActivity(intent)
                Log.d(TAG, "SYNC OK: $hour:$minute \"$message\"")
            } catch (e: SecurityException) {
                Log.w(TAG, "SYNC FAIL (SecurityException) $hour:$minute: ${e.message}")
                failedAlarms.put(obj)
            } catch (e: Exception) {
                Log.w(TAG, "SYNC FAIL $hour:$minute: ${e.message}")
                failedAlarms.put(obj)
            }
        }

        // Only clear successfully synced entries.
        // Failed entries are kept in the queue for the next idle-triggered retry.
        if (failedAlarms.length() > 0) {
            prefs.edit().putString(KEY_PENDING_ALARMS, failedAlarms.toString()).apply()
            Log.d(TAG, "${failedAlarms.length()} alarm(s) failed — will retry on next idle period")
            return Result.retry()
        } else {
            prefs.edit().remove(KEY_PENDING_ALARMS).apply()
            Log.d(TAG, "All system alarms synced successfully")
            return Result.success()
        }
    }

    companion object {
        private const val TAG = "SystemAlarmSyncWorker"
        private const val PREFS_NAME = "system_alarm_sync"
        private const val KEY_PENDING_ALARMS = "pending_alarms"
    }
}