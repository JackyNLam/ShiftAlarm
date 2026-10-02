package com.example.shiftalarm

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.shiftalarm.alarm.AlarmScheduler
import com.example.shiftalarm.ui.navigation.AppNavigation
import com.example.shiftalarm.ui.screen.template.TemplateEditScreen
import com.example.shiftalarm.ui.theme.ShiftAlarmTheme

class MainActivity : ComponentActivity() {

    // Use Compose-observable state so UI recomposes when navigation changes
    private var editingTemplateId: Long? by mutableStateOf(null)

    // Request POST_NOTIFICATIONS on Android 13+ so alarm notifications actually show/sound.
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied — either way we proceed */ }

    // Request SYSTEM_ALERT_WINDOW for overlay when auto-setting system alarm
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* proceed regardless */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as ShiftAlarmApp
        val scheduler = AlarmScheduler(this)

        // Reschedule alarms on app start (catches missed alarms)
        scheduler.rescheduleAllAlarms()

        // On Android 13+, POST_NOTIFICATIONS must be requested at runtime.
        // Without it, alarm notifications (which are how the alarm "rings") are silently suppressed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // On Android 6+, request SYSTEM_ALERT_WINDOW for the auto-set overlay.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(this)
        ) {
            // Request once; if denied, the overlay is simply skipped.
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${packageName}")
            )
            try {
                overlayPermissionLauncher.launch(intent)
            } catch (_: Exception) {
                // Activity may not exist (e.g. emulator without Settings)
            }
        }

        enableEdgeToEdge()
        setContent {
            ShiftAlarmTheme {
                if (editingTemplateId != null) {
                    // editingTemplateId == 0L means "new template", otherwise existing template ID
                    TemplateEditScreen(
                        repository = app.repository,
                        templateId = if (editingTemplateId == 0L) null else editingTemplateId,
                        onNavigateBack = {
                            editingTemplateId = null
                        }
                    )
                } else {
                    AppNavigation(
                        repository = app.repository,
                        onNavigateToTemplateEdit = { id ->
                            editingTemplateId = id ?: 0L
                        }
                    )
                }
            }
        }
    }
}