package com.example.shiftalarm.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Shows a brief semi-transparent overlay "Setting system alarm…" when
 * [AlarmScheduler.autoSetSystemAlarmIfNear] fires ACTION_SET_ALARM.
 *
 * The overlay stays visible for ~2 seconds so the user sees what happened
 * (the clock app briefly flashes on screen), then auto-dismisses.
 *
 * ## Permission
 * Requires [Settings.ACTION_MANAGE_OVERLAY_PERMISSION] (SYSTEM_ALERT_WINDOW).
 * The app shows a one-time request dialog on the Home screen if not granted.
 */
class SystemAlarmOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_DISMISS, false) == true) {
            dismissOverlay()
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (!canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted — skipping overlay")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val hour = intent?.getIntExtra(EXTRA_HOUR, -1) ?: -1
        val minute = intent?.getIntExtra(EXTRA_MINUTE, -1) ?: -1
        val message = intent?.getStringExtra(EXTRA_MESSAGE) ?: ""

        showOverlay(hour, minute, message)

        // Auto-dismiss after 2.5 seconds
        Executors.newSingleThreadScheduledExecutor().schedule({
            dismissWithCleanup(startId)
        }, 2500, TimeUnit.MILLISECONDS)

        return START_NOT_STICKY
    }

    private fun showOverlay(hour: Int, minute: Int, message: String) {
        val timeText = if (hour >= 0 && minute >= 0) {
            String.format("%02d:%02d", hour, minute)
        } else {
            ""
        }

        val tv = TextView(this).apply {
            text = buildString {
                append("\u23F0 正在設定系統鬧鐘 / Setting system alarm...")
                if (timeText.isNotBlank()) append("\n$timeText")
                if (message.isNotBlank()) append("\n$message")
            }
            setTextColor(android.graphics.Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(48, 32, 48, 32)
            setBackgroundColor(android.graphics.Color.argb(220, 30, 30, 30))
            elevation = 16f
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        try {
            windowManager.addView(tv, params)
            overlayView = tv
            Log.d(TAG, "Overlay shown: $timeText $message")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay: ${e.message}")
        }
    }

    private fun dismissOverlay() {
        overlayView?.let { view ->
            try {
                if (view.isAttachedToWindow || Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
                    windowManager.removeView(view)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error removing overlay: ${e.message}")
            }
            overlayView = null
        }
    }

    private fun dismissWithCleanup(startId: Int) {
        dismissOverlay()
        try {
            stopSelf(startId)
        } catch (e: Exception) {
            Log.w(TAG, "stopSelf error: ${e.message}")
        }
    }

    override fun onDestroy() {
        dismissOverlay()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AlarmOverlaySvc"

        private const val EXTRA_HOUR = "extra_hour"
        private const val EXTRA_MINUTE = "extra_minute"
        private const val EXTRA_MESSAGE = "extra_message"
        private const val EXTRA_DISMISS = "extra_dismiss"

        /** Check whether SYSTEM_ALERT_WINDOW is granted. */
        @JvmStatic
        fun canDrawOverlays(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else true
        }

        /** Start the overlay with alarm details. */
        @JvmStatic
        fun show(context: Context, hour: Int, minute: Int, message: String) {
            val intent = Intent(context, SystemAlarmOverlayService::class.java).apply {
                putExtra(EXTRA_HOUR, hour)
                putExtra(EXTRA_MINUTE, minute)
                putExtra(EXTRA_MESSAGE, message)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start overlay service: ${e.message}")
            }
        }

        /** Dismiss any visible overlay. */
        @JvmStatic
        fun dismiss(context: Context) {
            val intent = Intent(context, SystemAlarmOverlayService::class.java).apply {
                putExtra(EXTRA_DISMISS, true)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}