package com.example.shiftalarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BroadcastReceiver triggered by AlarmManager to fire [Intent.ACTION_SET_ALARM]
 * and silently add the alarm to the system Clock app.
 *
 * Scheduled by [AlarmScheduler.autoSetSystemAlarmIfNear] when the next shift
 * alarm is within 23 hours. Uses AlarmManager's [setExactAndAllowWhileIdle]
 * so it fires even in Doze mode, and the BroadcastReceiver's onReceive()
 * execution window makes [startActivity] more likely to succeed than calling
 * from a WorkManager worker.
 */
class SystemAlarmSetReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val hour = intent.getIntExtra(EXTRA_HOUR, -1)
        val minute = intent.getIntExtra(EXTRA_MINUTE, -1)
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: ""

        if (hour < 0 || minute < 0) {
            Log.w(TAG, "Invalid alarm data — hour=$hour minute=$minute")
            return
        }

        Log.i(TAG, "AlarmManager-triggered system alarm set: $hour:$minute \"$message\"")
        AlarmScheduler.fireSystemClockAlarm(context, hour, minute, message)

        // Clear the pending alarm from SharedPreferences since we've processed it.
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_PENDING_ALARMS).apply()
    }

    companion object {
        private const val TAG = "SystemAlarmSetReceiver"
        const val EXTRA_HOUR = "extra_sys_alarm_hour"
        const val EXTRA_MINUTE = "extra_sys_alarm_minute"
        const val EXTRA_MESSAGE = "extra_sys_alarm_message"
        private const val PREFS_NAME = "system_alarm_sync"
        private const val KEY_PENDING_ALARMS = "pending_alarms"
    }
}
