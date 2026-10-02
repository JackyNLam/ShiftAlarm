package com.example.shiftalarm.alarm

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.shiftalarm.MainActivity
import com.example.shiftalarm.ui.theme.ShiftAlarmTheme

/**
 * Full-screen alarm activity that shows over the lock screen when the alarm fires.
 *
 * Declared in AndroidManifest with [android:showWhenLocked]="true" and
 * [android:turnScreenOn]="true" so it bypasses the keyguard automatically.
 */
class AlarmActivity : ComponentActivity() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Acquire a wake lock so the screen stays on while this activity is visible.
        acquireWakeLock()

        // Dismiss the keyguard (unlock the screen) on this window — redundant with
        // showWhenLocked in the manifest but also works on some older devices.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        val entryDate = intent.getStringExtra(AlarmReceiver.EXTRA_ENTRY_DATE) ?: ""
        val templateName = intent.getStringExtra(AlarmReceiver.EXTRA_TEMPLATE_NAME) ?: ""
        val shiftLabel = intent.getStringExtra(AlarmReceiver.EXTRA_SHIFT_LABEL) ?: ""
        val location = intent.getStringExtra(AlarmReceiver.EXTRA_LOCATION) ?: ""
        val alarmIndex = intent.getIntExtra(AlarmReceiver.EXTRA_ALARM_INDEX, 0)
        val title = shiftLabel.ifBlank { templateName }

        setContent {
            ShiftAlarmTheme {
                var dismissed by remember { mutableStateOf(false) }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        // Time
                        Text(
                            text = java.time.LocalTime.now().format(
                                java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                            ),
                            fontSize = 72.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // Shift label / template name
                        Text(
                            text = title,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            textAlign = TextAlign.Center
                        )

                        if (templateName.isNotBlank() && shiftLabel.isNotBlank()) {
                            Text(
                                text = templateName,
                                fontSize = 20.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(0.7f),
                                textAlign = TextAlign.Center
                            )
                        }

                        if (location.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "\uD83D\uDCCD $location",
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(0.7f),
                                textAlign = TextAlign.Center
                            )
                        }

                        if (entryDate.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = entryDate,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(0.5f),
                                textAlign = TextAlign.Center
                            )
                        }

                        Spacer(modifier = Modifier.height(48.dp))

                        // Dismiss button
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.error,
                                    shape = CircleShape
                                )
                                .clickable {
                                    dismissed = true
                                    dismissAlarm(alarmIndex)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.AlarmOff,
                                contentDescription = "Dismiss",
                                tint = MaterialTheme.colorScheme.onError,
                                modifier = Modifier.size(40.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "點擊關閉 / Tap to dismiss",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(0.5f)
                        )

                        Spacer(modifier = Modifier.height(32.dp))

                        // Open full app
                        Row(
                            modifier = Modifier
                                .clickable {
                                    startActivity(
                                        Intent(this@AlarmActivity, MainActivity::class.java).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                        }
                                    )
                                    dismissAlarm(alarmIndex)
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "開啟 App / Open App",
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    // If dismissed, show a confirmation and finish soon
                    if (dismissed) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface.copy(0.9f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "已關閉 / Dismissed",
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
            "ShiftAlarm:AlarmActivity"
        )
        wakeLock?.acquire(5 * 60 * 1000L) // 5 minutes max
    }

    private fun dismissAlarm(alarmIndex: Int) {
        // Cancel the notification
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(1000 + alarmIndex)

        // Stop the app's own alarm service (continuous sound + vibration)
        AppAlarmService.stop(this)

        // Finish after a short delay so the user sees the "dismissed" UI
        window.decorView.postDelayed({
            finish()
        }, 600)
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
    }
}