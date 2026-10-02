package com.example.shiftalarm.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.shiftalarm.MainActivity
import com.example.shiftalarm.R

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_ENTRY_DATE = "extra_entry_date"
        const val EXTRA_ALARM_INDEX = "extra_alarm_index"
        const val EXTRA_TEMPLATE_NAME = "extra_template_name"
        const val EXTRA_SHIFT_LABEL = "extra_shift_label"
        const val EXTRA_LOCATION = "extra_location"

        // Changed channel ID to force fresh channel with proper alarm sound settings
        private const val CHANNEL_ID = "shift_alarm_channel_v2"
        private const val NOTIFICATION_ID_BASE = 1000
        private const val ICON_ALARM = R.drawable.ic_notification_alarm
    }

    override fun onReceive(context: Context, intent: Intent) {
        val entryDate = intent.getStringExtra(EXTRA_ENTRY_DATE) ?: return
        val alarmIndex = intent.getIntExtra(EXTRA_ALARM_INDEX, 0)
        val templateName = intent.getStringExtra(EXTRA_TEMPLATE_NAME) ?: "?"
        val shiftLabel = intent.getStringExtra(EXTRA_SHIFT_LABEL) ?: ""
        val location = intent.getStringExtra(EXTRA_LOCATION) ?: ""

        createNotificationChannel(context)

        val title = if (shiftLabel.isNotBlank()) shiftLabel else templateName
        val body = buildString {
            append(templateName)
            if (location.isNotBlank()) {
                append(" · $location")
            }
        }

        // Full-screen intent: launches AlarmActivity (with showWhenLocked + turnScreenOn)
        // so the alarm UI appears over the lock screen.
        val fullScreenIntent = Intent(context, AlarmActivity::class.java).apply {
            putExtra(EXTRA_ENTRY_DATE, entryDate)
            putExtra(EXTRA_ALARM_INDEX, alarmIndex)
            putExtra(EXTRA_TEMPLATE_NAME, templateName)
            putExtra(EXTRA_SHIFT_LABEL, shiftLabel)
            putExtra(EXTRA_LOCATION, location)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context, 2000 + alarmIndex, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Directly launch AlarmActivity as a backup — on some devices the notification's
        // fullScreenIntent doesn't fire reliably, so this ensures the full-screen popup
        // always appears alongside the notification.
        context.startActivity(fullScreenIntent)

        // Normal tap intent: opens MainActivity
        val activityIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // NOTE: Notification does NOT set sound or vibration — those are handled
        // by AppAlarmService (MediaPlayer + Vibrator) to avoid double-ringing.
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(ICON_ALARM)
            .setContentTitle(title)
            .setContentText(body)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID_BASE + alarmIndex, notification)

        // Also start the app's own alarm service for continuous sound + vibration.
        // This runs alongside the existing notification + AlarmActivity flow.
        // Wrapped in try-catch to avoid crashing the receiver if the service fails
        // (e.g. missing foreground-service permission on API 34+).
        try {
            AppAlarmService.start(context, intent)
        } catch (e: Exception) {
            Log.e("AlarmReceiver", "Failed to start AppAlarmService", e)
        }
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // No sound/vibration on the channel — AppAlarmService handles audio.
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Shift Alarm",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for shift alarm reminders"
                enableVibration(false)
                setBypassDnd(true)
            }
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}