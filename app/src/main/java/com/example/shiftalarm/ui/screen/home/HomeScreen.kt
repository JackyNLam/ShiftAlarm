package com.example.shiftalarm.ui.screen.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.shiftalarm.data.repository.ShiftRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(
    repository: ShiftRepository,
    modifier: Modifier = Modifier
) {
    val viewModel = androidx.lifecycle.viewmodel.compose.viewModel<HomeViewModel>(
        factory = HomeViewModel.Factory(
            repository,
            com.example.shiftalarm.alarm.AlarmScheduler(
                androidx.compose.ui.platform.LocalContext.current
            ),
            androidx.compose.ui.platform.LocalContext.current.applicationContext
        )
    )
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.refresh() }

    // Auto-dismiss the background alarm result after 3 seconds
    LaunchedEffect(state.backgroundAlarmResult) {
        if (state.backgroundAlarmResult != null) {
            kotlinx.coroutines.delay(3000L)
            viewModel.clearBackgroundAlarmResult()
        }
    }

    // Auto-dismiss the hourly check result after 5 seconds
    LaunchedEffect(state.hourlyCheckResult) {
        if (state.hourlyCheckResult != null) {
            kotlinx.coroutines.delay(5000L)
            viewModel.clearHourlyCheckResult()
        }
    }

    // Auto-dismiss import/export result after 4 seconds
    LaunchedEffect(state.importExportResult) {
        if (state.importExportResult != null) {
            kotlinx.coroutines.delay(4000L)
            viewModel.clearImportExportResult()
        }
    }

    // --- Import / Export state and launchers ---
    var showImportDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState()

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { viewModel.exportSchedule(it) }
    }

    val importFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { fileUri ->
            val millis = datePickerState.selectedDateMillis ?: return@let
            val date = Instant.ofEpochMilli(millis)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .format(DateTimeFormatter.ISO_LOCAL_DATE)
            viewModel.importSchedule(date, fileUri)
        }
    }

    // Date picker dialog for import
    if (showImportDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showImportDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    showImportDatePicker = false
                    importFileLauncher.launch(arrayOf("application/json"))
                }) {
                    Text("確認 / Confirm")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDatePicker = false }) {
                    Text("取消 / Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Header
        Text(
            text = "Shift Alarm",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Next alarm card
        if (state.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        } else {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "下次鬧鐘 / Next Alarm",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val next = state.nextAlarm
                    if (next != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Alarm,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = next.time,
                                    style = MaterialTheme.typography.headlineLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = if (next.location.isNotBlank()) "[${next.shiftLabel}] ${next.location}"
                                           else next.shiftLabel,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "📝 ${next.templateName} · ${next.date}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(0.7f)
                                )
                            }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Alarm,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(0.5f)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "沒有即將到來的鬧鐘\nNo upcoming alarms",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(0.7f)
                            )
                        }
                    }

                    // "Set Background Alarm" button — only when a next alarm exists
                    if (next != null) {
                        Spacer(modifier = Modifier.height(12.dp))

                        // Feedback message after background set
                        state.backgroundAlarmResult?.let { success ->
                            Text(
                                text = if (success) "✅ 已設定背景鬧鐘 / Background alarm set" else "❌ 設定失敗 / Failed to set",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (success)
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(0.7f)
                                else
                                    MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { viewModel.setSystemClockForNextAlarm() }) {
                                Icon(
                                    Icons.Default.Alarm,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("自動設定背景鬧鐘 / Set Background Alarm")
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Permission warning
            if (!state.hasExactAlarmPermission) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "需要精確鬧鐘權限",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Exact alarm permission required",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        TextButton(onClick = { viewModel.openAlarmSettings() }) {
                            Text("設定 / Settings")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Overlay permission card — shows when SYSTEM_ALERT_WINDOW is NOT granted
            // This is optional: the background alarm uses AlarmManager + SCHEDULE_EXACT_ALARM
            // and does NOT need overlay permission. The overlay is only used by the
            // auto-set system alarm feature (23-hour deferred sync).
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && !state.canDrawOverlays) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Alarm,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "延遲同步系統鬧鐘 (覆疊層權限) — 選用",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                text = "Deferred system clock sync (overlay) — optional",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(0.7f)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "背景鬧鐘不需要此權限。啟用後可在裝置閒置時自動同步系統時鐘 / Background alarm works without this. When enabled, syncs system clock when device is idle",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(0.6f)
                            )
                        }
                        TextButton(onClick = { viewModel.openOverlaySettings() }) {
                            Text("授權 / Grant")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "${state.templateCount}",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "班次模板\nShift Templates",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "${state.scheduleCount}",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "已排程天數\nScheduled Days",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Reschedule button
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { viewModel.rescheduleAlarms() }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("重新同步鬧鐘 / Resync Alarms")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Import / Export buttons
            state.importExportResult?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (msg.startsWith("✅"))
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else
                        MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(
                    modifier = Modifier.weight(1f),
                    onClick = { showImportDatePicker = true }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("📥 匯入排程\nImport Schedule", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Card(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val dateStr = java.time.LocalDate.now()
                            .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                        exportLauncher.launch("shift_alarm_$dateStr.json")
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("📤 匯出排程\nExport Schedules", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            /* 🔽 Debug: Test hourly check — commented out, keep for future debugging
            Spacer(modifier = Modifier.height(16.dp))

            // Debug: Test hourly check chain
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { viewModel.testHourlyCheck() }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Alarm, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("🧪 測試每小時檢查 / Test Hourly Check")
                    }
                    state.hourlyCheckResult?.let { result ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = result,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            */
        }
    }
}