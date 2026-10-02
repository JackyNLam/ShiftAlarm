package com.example.shiftalarm.ui.screen.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.shiftalarm.data.ScheduleImageStorage
import com.example.shiftalarm.data.repository.ShiftRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
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
    var showAiSheet by remember { mutableStateOf(false) }
    var showImageManager by remember { mutableStateOf(false) }
    var viewingImage by remember { mutableStateOf<String?>(null) }
    var pendingDeleteImage by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { viewModel.exportSchedule(it) }
    }

    // Multi-day import: pick a JSON file, every entry in it is applied.
    val importFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importSchedule(it) }
    }

    // Schedule-image import (photo of the paper schedule) — resized to 800px and stored.
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.importScheduleImage(it) }
    }

    // AI result export — same JSON format as import/export schedule
    val aiExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { viewModel.completeAiExport(it) }
    }

    // Auto-export: as soon as AI extraction produces a result, ask where to save it.
    LaunchedEffect(state.pendingAiExportJson) {
        if (state.pendingAiExportJson != null) {
            val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            aiExportLauncher.launch("shift_alarm_ai_$today.json")
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
                // Left column: import schedule + import schedule image
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ScheduleActionCard(
                        icon = Icons.Default.FileUpload,
                        text = "📥 匯入排程\nImport Schedule",
                        onClick = { importFileLauncher.launch(arrayOf("application/json")) }
                    )
                    ScheduleActionCard(
                        icon = Icons.Default.Image,
                        text = "🖼️ 排程圖片\nSchedule Image",
                        onClick = { showImageManager = true }
                    )
                }
                // Right column: export schedule + AI extraction
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ScheduleActionCard(
                        icon = Icons.Default.FileDownload,
                        text = "📤 匯出排程\nExport Schedules",
                        onClick = {
                            val dateStr = LocalDate.now()
                                .format(DateTimeFormatter.ISO_LOCAL_DATE)
                            exportLauncher.launch("shift_alarm_$dateStr.json")
                        }
                    )
                    ScheduleActionCard(
                        icon = Icons.Default.AutoAwesome,
                        text = "🤖 AI 擷取排程\nAI Extract Schedule",
                        onClick = { showAiSheet = true }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

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

    // Full-screen viewer for a saved schedule image
    viewingImage?.let { name ->
        val context = LocalContext.current
        val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, name) {
            value = withContext(Dispatchers.IO) {
                val file = File(context.filesDir, ScheduleImageStorage.DIR_NAME)
                    .resolve(name)
                if (file.exists())
                    android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                else null
            }
        }
        Dialog(onDismissRequest = { viewingImage = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp)
            ) {
                Text(
                    text = "排程圖片 / Schedule Image",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                val bmp = bitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 520.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        viewModel.deleteScheduleImage(name)
                        viewingImage = null
                    }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("刪除 / Delete")
                    }
                    TextButton(onClick = { viewingImage = null }) {
                        Text("關閉 / Close")
                    }
                }
            }
        }
    }

    // AI extraction bottom sheet
    if (showAiSheet) {
        AiExtractSheet(
            scheduleImages = state.scheduleImages,
            initialOptions = state.aiOptions,
            isBusy = state.aiBusy,
            result = state.aiResult,
            onExtract = { key, model, prompt, imageName ->
                viewModel.extractScheduleWithAi(key, model, prompt, imageName)
            },
            onDismiss = { showAiSheet = false }
        )
    }

    // Delete confirmation for schedule images
    pendingDeleteImage?.let { name ->
        AlertDialog(
            onDismissRequest = { pendingDeleteImage = null },
            title = { Text("刪除圖片 / Delete Image") },
            text = { Text("確定要刪除這張排程圖片嗎？\nDelete this schedule image?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteScheduleImage(name)
                    pendingDeleteImage = null
                }) {
                    Text("刪除 / Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteImage = null }) {
                    Text("取消 / Cancel")
                }
            }
        )
    }

    // Schedule-image manager (view / add / delete) — opened from the 排程圖片 button
    if (showImageManager) {
        Dialog(onDismissRequest = { showImageManager = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp)
            ) {
                Text(
                    text = "排程圖片 / Schedule Images",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (state.scheduleImages.isEmpty())
                        "尚無排程圖片，點「＋」加入排班表照片\nNo images yet — tap + to add a photo"
                    else
                        "點圖片檢視，點 ✕ 刪除\nTap to view, ✕ to delete",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.scheduleImages, key = { it }) { name ->
                        ScheduleImageThumbnail(
                            fileName = name,
                            onClick = { viewingImage = name },
                            onDelete = { pendingDeleteImage = name }
                        )
                    }
                    item(key = "add_image") {
                        AddImageTile { imagePickerLauncher.launch("image/*") }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { showImageManager = false }) {
                        Text("關閉 / Close")
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleActionCard(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ScheduleImageThumbnail(
    fileName: String,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, fileName) {
        value = withContext(Dispatchers.IO) {
            val file = File(context.filesDir, ScheduleImageStorage.DIR_NAME).resolve(fileName)
            if (file.exists()) {
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }
                android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
            } else null
        }
    }
    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Delete image",
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun AddImageTile(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text("匯入 / Add", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiExtractSheet(
    scheduleImages: List<String>,
    initialOptions: AiOptions,
    isBusy: Boolean,
    result: String?,
    onExtract: (apiKey: String, model: String, prompt: String, imageName: String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var apiKey by remember { mutableStateOf(initialOptions.apiKey) }
    var model by remember { mutableStateOf(initialOptions.model) }
    var prompt by remember { mutableStateOf(initialOptions.prompt) }
    var selectedImage by remember { mutableStateOf(scheduleImages.firstOrNull() ?: "") }
    var menuExpanded by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "🤖 AI 排程擷取 / AI Schedule Extraction",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "使用 DashScope 視覺模型從排程圖片解析排程，並匯出與匯入/匯出相同格式的 JSON。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("DashScope API Key") },
                placeholder = { Text("sk-...") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("模型 / Model") },
                placeholder = { Text("qwen-vl-plus") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("提示詞 / Prompt") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Which saved image to extract from
            Box {
                OutlinedButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (selectedImage.isNotBlank()) "🖼️ $selectedImage"
                        else "請選擇排程圖片 / Select a schedule image",
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    scheduleImages.forEach { name ->
                        DropdownMenuItem(
                            text = {
                                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            onClick = {
                                selectedImage = name
                                menuExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            result?.let { msg ->
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

            Button(
                onClick = {
                    onExtract(apiKey.trim(), model.trim(), prompt.trim(), selectedImage)
                },
                enabled = !isBusy && selectedImage.isNotBlank() && apiKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isBusy) "擷取中… / Extracting…" else "擷取並匯出 JSON / Extract & Export JSON")
            }
        }
    }
}