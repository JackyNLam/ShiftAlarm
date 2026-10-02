package com.example.shiftalarm.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.shiftalarm.R

/**
 * Foreground service that plays the alarm sound and vibration directly
 * from the app itself (not relying on notification sound).
 *
 * The service runs continuously until the user explicitly dismisses the alarm
 * (via the notification action or from [AlarmActivity]).
 *
 * This is ADDED on top of the existing system alarm setup — the original
 * AlarmManager + AlarmReceiver + AlarmActivity flow is unchanged.
 */
class AppAlarmService : Service() {

    companion object {
        private const val TAG = "AppAlarmService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "app_alarm_channel"
        private const val TIMEOUT_MS = 10 * 60 * 1000L // 10 min safety auto-stop

        const val ACTION_DISMISS = "com.example.shiftalarm.action.DISMISS_APP_ALARM"

        /** Start the service from a BroadcastReceiver or Activity. */
        fun start(context: Context, intent: Intent) {
            val serviceIntent = Intent(context, AppAlarmService::class.java).apply {
                putExtra(AlarmReceiver.EXTRA_ENTRY_DATE,
                    intent.getStringExtra(AlarmReceiver.EXTRA_ENTRY_DATE))
                putExtra(AlarmReceiver.EXTRA_TEMPLATE_NAME,
                    intent.getStringExtra(AlarmReceiver.EXTRA_TEMPLATE_NAME))
                putExtra(AlarmReceiver.EXTRA_SHIFT_LABEL,
                    intent.getStringExtra(AlarmReceiver.EXTRA_SHIFT_LABEL))
                putExtra(AlarmReceiver.EXTRA_LOCATION,
                    intent.getStringExtra(AlarmReceiver.EXTRA_LOCATION))
                putExtra(AlarmReceiver.EXTRA_ALARM_INDEX,
                    intent.getIntExtra(AlarmReceiver.EXTRA_ALARM_INDEX, 0))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }

        /** Stop the service (call from AlarmActivity when dismissed). */
        fun stop(context: Context) {
            context.stopService(Intent(context, AppAlarmService::class.java))
        }
    }

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var isAlarmPlaying = false

    override fun onCreate() {
        super.onCreate()
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle dismiss action from notification
        if (intent?.action == ACTION_DISMISS) {
            Log.d(TAG, "Dismiss action received — stopping alarm")
            stopAlarm()
            stopSelf()
            return START_NOT_STICKY
        }

        val entryDate = intent?.getStringExtra(AlarmReceiver.EXTRA_ENTRY_DATE) ?: ""
        val templateName = intent?.getStringExtra(AlarmReceiver.EXTRA_TEMPLATE_NAME) ?: ""
        val shiftLabel = intent?.getStringExtra(AlarmReceiver.EXTRA_SHIFT_LABEL) ?: ""
        val location = intent?.getStringExtra(AlarmReceiver.EXTRA_LOCATION) ?: ""
        val alarmIndex = intent?.getIntExtra(AlarmReceiver.EXTRA_ALARM_INDEX, 0) ?: 0

        val title = shiftLabel.ifBlank { templateName }
        val body = buildString {
            append(templateName)
            if (location.isNotBlank()) append(" · $location")
        }

        Log.d(TAG, "Starting app alarm: $title — $body")

        // Start foreground with notification
        startForeground(NOTIFICATION_ID, createNotification(title, body, entryDate, alarmIndex))

        // Play alarm sound (looping)
        playAlarmSound()

        // Start vibration (repeating)
        startVibration()

        isAlarmPlaying = true

        // Safety auto-stop after timeout
        timeoutHandler.removeCallbacksAndMessages(null)
        timeoutHandler.postDelayed({
            if (isAlarmPlaying) {
                Log.d(TAG, "Auto-stopping alarm after ${TIMEOUT_MS / 60000} min timeout")
                stopAlarm()
                stopSelf()
            }
        }, TIMEOUT_MS)

        return START_STICKY
    }

    private fun playAlarmSound() {
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            mediaPlayer = MediaPlayer().apply {
                setAudioStreamType(AudioManager.STREAM_ALARM)
                setDataSource(this@AppAlarmService, alarmUri)
                isLooping = true
                prepare()
                start()
            }
            Log.d(TAG, "Alarm sound started (looping)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play alarm sound", e)
        }
    }

    private fun startVibration() {
        try {
            val pattern = longArrayOf(0, 500, 200, 500) // vibrate 500ms, pause 200ms
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(pattern, 0) // repeat from index 0
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
            Log.d(TAG, "Vibration started (repeating)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to vibrate", e)
        }
    }

    private fun createNotification(
        title: String,
        body: String,
        entryDate: String,
        alarmIndex: Int
    ): Notification {
        createChannel()

        // Dismiss action → sends ACTION_DISMISS back to this service
        val dismissIntent = Intent(this, AppAlarmService::class.java).apply {
            action = ACTION_DISMISS
        }
        val dismissPendingIntent = PendingIntent.getService(
            this, 2000 + alarmIndex, dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_alarm)
            .setContentTitle("⏰ $title")
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Dismiss / 關閉",
                dismissPendingIntent
            )
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App Alarm",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Ongoing alarm playback from ShiftAlarm app"
                enableVibration(false) // vibration is handled by Vibrator directly
                setBypassDnd(true)
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun stopAlarm() {
        isAlarmPlaying = false
        timeoutHandler.removeCallbacksAndMessages(null)

        mediaPlayer?.apply {
            if (isPlaying) stop()
            release()
        }
        mediaPlayer = null

        try {
            vibrator?.cancel()
        } catch (_: Exception) {}

        // Cancel the foreground notification
        stopForeground(STOP_FOREGROUND_REMOVE)

        Log.d(TAG, "Alarm stopped")
    }

    override fun onDestroy() {
        stopAlarm()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}