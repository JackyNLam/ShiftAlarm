package com.example.shiftalarm.ui.screen.home

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.shiftalarm.data.DownloadJsonFile
import com.example.shiftalarm.data.DownloadJsonPicker
import com.example.shiftalarm.data.ScheduleImageStorage
import com.example.shiftalarm.data.repository.ShiftRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

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
    var showImportPicker by remember { mutableStateOf(false) }
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

    // Schedule-image import (photo of the paper schedule) — downscaled to a 2000px max side and stored.
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.importScheduleImage(it) }
    }

    // The Android photo picker only reveals the MediaStore _ID of the picked
    // photo, so the real file name + modified time are looked up in MediaStore
    // afterwards — that needs READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE. Ask
    // BEFORE the picker runs (the picked URI can only be read once).
    val context = LocalContext.current

    // Folder picker for the in-app import dialog — lets the user choose a
    // different folder when the Download directory cannot be read (API 33+).
    val pickerScope = rememberCoroutineScope()
    var folderPickerResult by remember { mutableStateOf<List<DownloadJsonFile>?>(null) }
    val chooseFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        showImportPicker = false
        pickerScope.launch {
            folderPickerResult = withContext(Dispatchers.IO) {
                DownloadJsonPicker.listJsonFilesFromTreeUri(context, uri)
            }
        }
    }

    val scheduleImagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                context,
                "未授權讀取相片，檔名/時間可能無法正確顯示 / Allow photo access to show real file names",
                Toast.LENGTH_LONG
            ).show()
        }
        imagePickerLauncher.launch("image/*")
    }
    val pickScheduleImage: () -> Unit = {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            scheduleImagePermissionLauncher.launch(permission)
        } else {
            imagePickerLauncher.launch("image/*")
        }
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
                        onClick = { showImportPicker = true }
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

    // Full-screen viewer for a saved schedule image — whole image, fitted
    viewingImage?.let { name ->
        val context = LocalContext.current
        val file = File(context.filesDir, ScheduleImageStorage.DIR_NAME).resolve(name)
        val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, name) {
            value = withContext(Dispatchers.IO) {
                if (file.exists())
                    android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                else null
            }
        }
        Dialog(
            onDismissRequest = { viewingImage = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                var zoomScale by remember { mutableStateOf(1f) }
                var zoomOffset by remember { mutableStateOf(Offset.Zero) }
                val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
                    zoomScale = (zoomScale * zoomChange).coerceIn(1f, 5f)
                    zoomOffset += panChange
                }
                val bmp = bitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = zoomScale
                                scaleY = zoomScale
                                translationX = zoomOffset.x
                                translationY = zoomOffset.y
                            }
                            .transformable(transformableState)
                    )
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
                IconButton(
                    onClick = {
                        zoomScale = 1f
                        zoomOffset = Offset.Zero
                    },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "重設縮放 / Reset zoom",
                        tint = Color.White
                    )
                }
                IconButton(
                    onClick = { viewingImage = null },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "關閉 / Close",
                        tint = Color.White
                    )
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 56.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = ScheduleImageStorage.originalNameOf(context, name),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.9f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // Show the source file's modified time, captured at import;
                    // images saved before it was recorded fall back to the copy's time.
                    if (file.exists()) {
                        val displayTime = ScheduleImageStorage
                            .originalModifiedOf(context, name) ?: file.lastModified()
                        Text(
                            text = "📷 " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                                .format(Date(displayTime)),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                ) {
                    TextButton(onClick = {
                        viewModel.deleteScheduleImage(name)
                        viewingImage = null
                    }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("刪除 / Delete", color = Color.White)
                    }
                    TextButton(onClick = { viewingImage = null }) {
                        Text("關閉 / Close", color = Color.White)
                    }
                }
            }
        }
    }

    // Import schedule: the app's own Download-folder list is shown first — the
    // system picker can display ghost entries of JSON files already deleted.
    // When the Download folder cannot be read (scoped storage on API 33+), a
    // "choose folder" option lets the user pick a different directory via SAF.
    if (showImportPicker) {
        DownloadJsonPickerDialog(
            onPick = { uri ->
                showImportPicker = false
                viewModel.importSchedule(uri)
            },
            onBrowse = {
                showImportPicker = false
                DownloadJsonPicker.refreshDownloadsCache(context) {
                    importFileLauncher.launch(arrayOf("application/json"))
                }
            },
            onChooseFolder = { chooseFolderLauncher.launch(null) },
            onDismiss = { showImportPicker = false }
        )
    }

    // Folder-picker results dialog — shows JSONs found in the user-chosen folder.
    if (folderPickerResult != null) {
        val folderFiles = folderPickerResult!!
        FolderResultDialog(
            files = folderFiles,
            onPick = { uri ->
                folderPickerResult = null
                viewModel.importSchedule(uri)
            },
            onDismiss = { folderPickerResult = null }
        )
    }

    // AI extraction bottom sheet
    if (showAiSheet) {
        AiExtractSheet(
            scheduleImages = state.scheduleImages,
            initialOptions = state.aiOptions,
            isBusy = state.aiBusy,
            result = state.aiResult,
            debugLog = state.aiDebugLog,
            startedAt = state.aiStartedAt,
            onExtract = { key, model, prompt, imageName, cropFile ->
                viewModel.extractScheduleWithAi(key, model, prompt, imageName, cropFile)
            },
            onClearDebug = viewModel::clearAiDebugLog,
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
                        AddImageTile { pickScheduleImage() }
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
    debugLog: String,
    startedAt: Long?,
    onExtract: (apiKey: String, model: String, prompt: String, imageName: String, cropFile: File?) -> Unit,
    onClearDebug: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var apiKey by remember { mutableStateOf(initialOptions.apiKey) }
    var showApiKey by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(initialOptions.model) }
    var prompt by remember { mutableStateOf(initialOptions.prompt) }
    var selectedImage by remember { mutableStateOf(scheduleImages.firstOrNull() ?: "") }
    var menuExpanded by remember { mutableStateOf(false) }
    // Optional ✂️ region crop — only that part of the image is sent to the AI.
    var cropBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var showCropDialog by remember { mutableStateOf(false) }
    val extractScope = rememberCoroutineScope()

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
                visualTransformation = if (showApiKey) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(
                            if (showApiKey) Icons.Default.VisibilityOff
                            else Icons.Default.Visibility,
                            contentDescription = if (showApiKey) "隱藏 / Hide" else "顯示 / Show"
                        )
                    }
                },
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
                        text = if (selectedImage.isNotBlank())
                            "🖼️ " + ScheduleImageStorage.originalNameOf(context, selectedImage)
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
                                Text(
                                    ScheduleImageStorage.originalNameOf(context, name),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            onClick = {
                                selectedImage = name
                                menuExpanded = false
                            }
                        )
                    }
                }
            }

            // ✂️ Optional region crop — only the selected part is sent to the AI
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { showCropDialog = true },
                    enabled = selectedImage.isNotBlank() && !isBusy,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.ContentCut,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    val cropLabel = cropBitmap
                    Text(
                        text = if (cropLabel != null)
                            "✂️ 已選擇區域 ${cropLabel.width}×${cropLabel.height}"
                        else "✂️ 選擇區域 / Select region",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (cropBitmap != null) {
                    TextButton(onClick = { cropBitmap = null }) {
                        Text(
                            "清除 / Clear",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            cropBitmap?.let { crop ->
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                ) {
                    Image(
                        bitmap = crop.asImageBitmap(),
                        contentDescription = "已選擇區域 / Selected region",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            if (showCropDialog && selectedImage.isNotBlank()) {
                AiCropDialog(
                    fileName = selectedImage,
                    onConfirm = { bmp ->
                        cropBitmap = bmp
                        showCropDialog = false
                    },
                    onUseWhole = {
                        cropBitmap = null
                        showCropDialog = false
                    },
                    onDismiss = { showCropDialog = false }
                )
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
                    val crop = cropBitmap
                    if (crop == null) {
                        onExtract(apiKey.trim(), model.trim(), prompt.trim(), selectedImage, null)
                    } else {
                        extractScope.launch {
                            val cropFile = withContext(Dispatchers.IO) {
                                val dir = context.cacheDir
                                dir.listFiles { f -> f.name.startsWith("ai_crop_") }
                                    ?.forEach { it.delete() }
                                val f = File(dir, "ai_crop_${System.currentTimeMillis()}.jpg")
                                f.outputStream().use { out ->
                                    crop.compress(
                                        android.graphics.Bitmap.CompressFormat.JPEG,
                                        90,
                                        out
                                    )
                                }
                                f
                            }
                            onExtract(apiKey.trim(), model.trim(), prompt.trim(), selectedImage, cropFile)
                        }
                    }
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

            // Live elapsed timer so a long wait is visibly progressing, not frozen
            if (isBusy) {
                var elapsedSec by remember { mutableStateOf(0L) }
                LaunchedEffect(startedAt, isBusy) {
                    while (true) {
                        val started = startedAt ?: return@LaunchedEffect
                        elapsedSec = (System.currentTimeMillis() - started) / 1000
                        delay(1000)
                    }
                }
                Text(
                    text = "⏳ 已等待 ${elapsedSec} 秒 / Waiting ${elapsedSec}s（單次嘗試上限約 150 秒）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // Copyable debug log (long-press to select & copy)
            if (debugLog.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📋 除錯日誌 / Debug Log",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    if (!isBusy) {
                        TextButton(onClick = onClearDebug) {
                            Text("清空 / Clear", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                SelectionContainer {
                    Text(
                        text = debugLog,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(10.dp)
                    )
                }
                Text(
                    text = "長按日誌即可選取複製 / Long-press to select & copy",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/**
 * Full-screen region selector for AI extraction: the user drags a rectangle over
 * the schedule image; only that cropped area is later sent to the AI.
 */
@Composable
private fun AiCropDialog(
    fileName: String,
    onConfirm: (android.graphics.Bitmap) -> Unit,
    onUseWhole: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, fileName) {
        value = withContext(Dispatchers.IO) {
            val file = File(context.filesDir, ScheduleImageStorage.DIR_NAME).resolve(fileName)
            if (file.exists()) android.graphics.BitmapFactory.decodeFile(file.absolutePath) else null
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            val bmp = bitmap
            if (bmp == null) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                var containerSize by remember { mutableStateOf(Offset.Zero) }
                var selStart by remember(bmp) { mutableStateOf<Offset?>(null) }
                var selEnd by remember(bmp) { mutableStateOf<Offset?>(null) }

                val imgRect = fittedImageRect(containerSize.x, containerSize.y, bmp.width, bmp.height)
                val start = selStart
                val end = selEnd
                val selection = if (start != null && end != null) {
                    Rect(
                        minOf(start.x, end.x), minOf(start.y, end.y),
                        maxOf(start.x, end.x), maxOf(start.y, end.y)
                    )
                } else null
                // The selection must map to at least 16×16 px in the actual bitmap
                val selectionValid = selection != null && imgRect.width > 0f &&
                    imgRect.height > 0f &&
                    (selection.width / imgRect.width * bmp.width) >= 16f &&
                    (selection.height / imgRect.height * bmp.height) >= 16f

                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = fileName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )

                // Dim everything outside the selection and outline it
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged {
                            containerSize = Offset(it.width.toFloat(), it.height.toFloat())
                        }
                        .pointerInput(bmp) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val rect = fittedImageRect(
                                        containerSize.x, containerSize.y, bmp.width, bmp.height
                                    )
                                    selStart = clampToImage(offset, rect)
                                    selEnd = selStart
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val rect = fittedImageRect(
                                        containerSize.x, containerSize.y, bmp.width, bmp.height
                                    )
                                    selEnd = clampToImage(change.position, rect)
                                }
                            )
                        }
                ) {
                    if (selection != null && imgRect.width > 0f && imgRect.height > 0f &&
                        selection.width >= 4f && selection.height >= 4f
                    ) {
                        val dim = Color.Black.copy(alpha = 0.55f)
                        // top / bottom / left / right bands around the selection
                        drawRect(
                            dim,
                            topLeft = Offset(0f, 0f),
                            size = Size(size.width, selection.top)
                        )
                        drawRect(
                            dim,
                            topLeft = Offset(0f, selection.bottom),
                            size = Size(size.width, size.height - selection.bottom)
                        )
                        drawRect(
                            dim,
                            topLeft = Offset(0f, selection.top),
                            size = Size(selection.left, selection.height)
                        )
                        drawRect(
                            dim,
                            topLeft = Offset(selection.right, selection.top),
                            size = Size(size.width - selection.right, selection.height)
                        )
                        drawRect(
                            Color.White,
                            topLeft = Offset(selection.left, selection.top),
                            size = Size(selection.width, selection.height),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }

                // Top bar: instructions + close
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "✂️ 拖曳選取要傳送給 AI 的範圍\nDrag to select the region to send to AI",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "關閉 / Close",
                            tint = Color.White
                        )
                    }
                }

                // Bottom actions
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消 / Cancel", color = Color.White)
                    }
                    TextButton(onClick = onUseWhole) {
                        Text("整張 / Whole image", color = Color.White)
                    }
                    Button(
                        onClick = {
                            val sel = selection
                            val rect = imgRect
                            if (sel != null && rect.width > 0f && rect.height > 0f) {
                                val scaleX = bmp.width / rect.width
                                val scaleY = bmp.height / rect.height
                                val px = (sel.left - rect.left) * scaleX
                                val py = (sel.top - rect.top) * scaleY
                                val pw = sel.width * scaleX
                                val ph = sel.height * scaleY
                                val cropRect = android.graphics.Rect(
                                    px.toInt().coerceIn(0, bmp.width),
                                    py.toInt().coerceIn(0, bmp.height),
                                    (px + pw).toInt().coerceIn(0, bmp.width),
                                    (py + ph).toInt().coerceIn(0, bmp.height)
                                )
                                if (cropRect.width() >= 16 && cropRect.height() >= 16) {
                                    val cropped = android.graphics.Bitmap.createBitmap(
                                        bmp,
                                        cropRect.left,
                                        cropRect.top,
                                        cropRect.width(),
                                        cropRect.height()
                                    )
                                    onConfirm(cropped)
                                }
                            }
                        },
                        enabled = selectionValid,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("使用選取範圍 / Use region")
                    }
                }
            }
        }
    }
}

/** The display rectangle the fitted image occupies inside a [containerW]×[containerH] area. */
private fun fittedImageRect(containerW: Float, containerH: Float, bmpW: Int, bmpH: Int): Rect {
    if (containerW <= 0f || containerH <= 0f || bmpW <= 0 || bmpH <= 0) {
        return Rect(0f, 0f, 0f, 0f)
    }
    val scale = minOf(containerW / bmpW, containerH / bmpH)
    val w = bmpW * scale
    val h = bmpH * scale
    return Rect(
        (containerW - w) / 2f,
        (containerH - h) / 2f,
        (containerW + w) / 2f,
        (containerH + h) / 2f
    )
}

/** Clamps a pointer position into the fitted image rectangle. */
private fun clampToImage(offset: Offset, imageRect: Rect): Offset = Offset(
    offset.x.coerceIn(imageRect.left, imageRect.right),
    offset.y.coerceIn(imageRect.top, imageRect.bottom)
)

/**
 * In-app picker for the schedule JSON import: lists .json files in the Download
 * folder (newest first) by walking the real filesystem, so already-deleted files
 * never appear. A "Browse…" fallback opens the system picker (with cache refresh).
 * A "Choose Folder" fallback lets the user pick any SAF-pickable folder.
 */
@Composable
private fun DownloadJsonPickerDialog(
    onPick: (Uri) -> Unit,
    onBrowse: () -> Unit,
    onChooseFolder: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var files by remember { mutableStateOf<List<DownloadJsonFile>?>(null) }
    LaunchedEffect(Unit) {
        files = withContext(Dispatchers.IO) {
            DownloadJsonPicker.listDownloadJsonFiles(context)
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp)
        ) {
            Text(
                text = "匯入排程 / Import Schedule",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "下載資料夾中的 JSON 檔案（最新的在上面）\nJSON files in the Download folder (newest first)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            val currentFiles = files
            when {
                currentFiles == null -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
                currentFiles.isEmpty() -> {
                    Text(
                        text = "下載資料夾中沒有 JSON 檔案\nNo JSON files in the Download folder",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onChooseFolder,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("選擇其他資料夾 / Choose Another Folder")
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(currentFiles, key = { it.uri.toString() }) { file ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(file.uri) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.FileDownload,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = file.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (file.lastModifiedMillis > 0) {
                                        Text(
                                            text = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                                                .format(Date(file.lastModifiedMillis)),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text("取消 / Cancel") }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onChooseFolder) { Text("資料夾 / Folder") }
                TextButton(onClick = onBrowse) { Text("瀏覽… / Browse…") }
            }
        }
    }
}

/**
 * Shows JSON files found in a user-picked folder (via SAF tree scan).
 * The user can tap a file to import it, or dismiss to go back.
 */
@Composable
private fun FolderResultDialog(
    files: List<DownloadJsonFile>,
    onPick: (Uri) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp)
        ) {
            Text(
                text = "匯入排程 / Import Schedule",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "選擇的資料夾中的 JSON 檔案\nJSON files in the selected folder",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (files.isEmpty()) {
                Text(
                    text = "該資料夾中沒有 JSON 檔案\nNo JSON files in this folder",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(files, key = { it.uri.toString() }) { file ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(file.uri) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.FileDownload,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = file.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (file.lastModifiedMillis > 0) {
                                    Text(
                                        text = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                                            .format(Date(file.lastModifiedMillis)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text("取消 / Cancel") }
            }
        }
    }
}