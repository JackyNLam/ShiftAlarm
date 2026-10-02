package com.example.shiftalarm.ui.screen.template

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.shiftalarm.data.repository.ShiftRepository
import com.example.shiftalarm.ui.theme.TemplateColors
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateEditScreen(
    repository: ShiftRepository,
    templateId: Long?,
    onNavigateBack: () -> Unit
) {
    // Use a unique key per templateId so editing a different template creates a fresh ViewModel
    val viewModelKey = "template_edit_${templateId ?: "new"}"
    val viewModel = androidx.lifecycle.viewmodel.compose.viewModel<TemplateEditViewModel>(
        key = viewModelKey,
        factory = TemplateEditViewModel.Factory(repository, templateId)
    )
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.saved) {
        if (state.saved) {
            viewModel.resetSaved()
            onNavigateBack()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(if (state.isEditing) "編輯模板 / Edit Template" else "新增模板 / New Template")
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.save() }) {
                        Icon(Icons.Default.Save, contentDescription = "Save")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Template name
            OutlinedTextField(
                value = state.name,
                onValueChange = { viewModel.updateName(it) },
                label = { Text("模板名稱 / Template Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Shift label
            OutlinedTextField(
                value = state.shiftLabel,
                onValueChange = { viewModel.updateShiftLabel(it) },
                label = { Text("班次標籤 / Shift Label") },
                placeholder = { Text("e.g. 早班, Morning") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Location
            OutlinedTextField(
                value = state.location,
                onValueChange = { viewModel.updateLocation(it) },
                label = { Text("工作地點 / Location") },
                placeholder = { Text("e.g. KCC, WFH, Office") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(20.dp))

            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            // Color picker
            Text(
                text = "日曆顏色 / Calendar Color",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))

            ColorPicker(
                selectedColor = state.color,
                onColorSelected = { viewModel.updateColor(it) }
            )

            // Preview
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(Color(state.color)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${state.shiftLabel.ifBlank { "Shift" }} · ${state.location.ifBlank { "Location" }}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            // Alarm times section
            Text(
                text = "鬧鐘時間 / Alarm Times",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            state.alarmTimes.forEachIndexed { index, time ->
                AlarmTimeRow(
                    time = time,
                    canRemove = state.alarmTimes.size > 1,
                    onTimeChange = { viewModel.updateAlarmTime(index, it) },
                    onRemove = { viewModel.removeAlarmTime(index) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            FilledTonalButton(
                onClick = { viewModel.addAlarmTime() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("新增鬧鐘時間 / Add Alarm Time")
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { viewModel.save() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("儲存 / Save")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmTimeRow(
    time: String,
    canRemove: Boolean,
    onTimeChange: (String) -> Unit,
    onRemove: () -> Unit
) {
    var showTimePicker by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = time,
            onValueChange = onTimeChange,
            label = { Text("HH:mm") },
            placeholder = { Text("07:30") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
            readOnly = true,
            enabled = false
        )

        Spacer(modifier = Modifier.width(8.dp))

        OutlinedButton(onClick = { showTimePicker = true }) {
            Text(text = if (time.isNotBlank()) time else "Select")
        }

        if (canRemove) {
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "Remove")
            }
        }
    }

    if (showTimePicker) {
        val defaultHour = try {
            time.split(":")[0].toInt()
        } catch (e: Exception) { 8 }
        val defaultMinute = try {
            time.split(":")[1].toInt()
        } catch (e: Exception) { 0 }

        val timePickerState = rememberTimePickerState(
            initialHour = defaultHour,
            initialMinute = defaultMinute,
            is24Hour = true
        )

        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("選擇時間 / Select Time") },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                TextButton(onClick = {
                    val formatted = String.format(
                        "%02d:%02d",
                        timePickerState.hour,
                        timePickerState.minute
                    )
                    onTimeChange(formatted)
                    showTimePicker = false
                }) {
                    Text("確定 / OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text("取消 / Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPicker(
    selectedColor: Long,
    onColorSelected: (Long) -> Unit
) {
    var showCustomPicker by remember { mutableStateOf(false) }

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TemplateColors.forEach { colorLong ->
            val isSelected = colorLong == selectedColor
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(colorLong))
                    .then(
                        if (isSelected) {
                            Modifier.border(3.dp, Color.White, CircleShape)
                        } else Modifier
                    )
                    .clickable { onColorSelected(colorLong) },
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Custom color button
        val isCustom = selectedColor !in TemplateColors
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    if (isCustom) Color(selectedColor)
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .then(
                    if (isCustom) {
                        Modifier.border(3.dp, Color.White, CircleShape)
                    } else Modifier
                )
                .clickable { showCustomPicker = true },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.ColorLens,
                contentDescription = "Custom color",
                tint = if (isCustom) Color.White
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }

    if (showCustomPicker) {
        CustomColorPickerDialog(
            initialColor = if (selectedColor in TemplateColors) 0xFF42A5F5L else selectedColor,
            onDismiss = { showCustomPicker = false },
            onColorSelected = { colorLong ->
                onColorSelected(colorLong)
                showCustomPicker = false
            }
        )
    }
}

@Composable
private fun CustomColorPickerDialog(
    initialColor: Long,
    onDismiss: () -> Unit,
    onColorSelected: (Long) -> Unit
) {
    // Convert Long color to HSV
    val argb = (initialColor and 0xFFFFFFFFL).toInt()
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(argb, hsv)

    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var saturation by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }

    // Preview color derived from current HSV
    val previewColor = remember(hue, saturation, value) {
        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)))
    }
    val previewLong = remember(hue, saturation, value) {
        (android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)).toLong() and 0xFFFFFFFFL)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自訂顏色 / Custom Color") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Color preview
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(previewColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "#%06X".format(previewLong and 0xFFFFFFL),
                        color = Color.White,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Hue slider
                Text("色調 / Hue", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = hue,
                    onValueChange = { hue = it },
                    valueRange = 0f..360f,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Saturation slider
                Text("飽和度 / Saturation", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = saturation,
                    onValueChange = { saturation = it },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Value (brightness) slider
                Text("亮度 / Brightness", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onColorSelected(previewLong) }) {
                Text("確定 / OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消 / Cancel")
            }
        }
    )
}