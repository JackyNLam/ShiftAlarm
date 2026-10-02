package com.example.shiftalarm.ui.screen.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.shiftalarm.data.entity.ShiftTemplate
import com.example.shiftalarm.data.repository.ShiftRepository
import com.example.shiftalarm.ui.theme.CalendarSelected
import com.example.shiftalarm.ui.theme.CalendarShiftBorder
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    repository: ShiftRepository,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewModel = androidx.lifecycle.viewmodel.compose.viewModel<CalendarViewModel>(
        factory = CalendarViewModel.Factory(
            repository,
            com.example.shiftalarm.alarm.AlarmScheduler(context)
        )
    )
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("排程日曆 / Schedule Calendar") }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp)
        ) {
            // Month navigation
            MonthNavigation(
                yearMonth = state.currentMonth,
                onPrevious = { viewModel.goToPreviousMonth() },
                onNext = { viewModel.goToNextMonth() },
                onToday = { viewModel.goToToday() }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Day-of-week header
            DayOfWeekHeader()

            Spacer(modifier = Modifier.height(4.dp))

            // Calendar grid — weight(1f) applied here in ColumnScope
            CalendarGrid(
                yearMonth = state.currentMonth,
                scheduleMap = state.scheduleMap,
                templates = state.templates,
                onDateClick = { viewModel.onDateClicked(it) },
                onDateLongClick = { viewModel.onDateClicked(it) },
                modifier = Modifier.weight(1f)
            )
        }
    }

    // Bottom sheet for date selection
    if (state.showBottomSheet && state.selectedDate != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewModel.dismissBottomSheet() },
            sheetState = sheetState
        ) {
            DateSelectionSheet(
                date = state.selectedDate!!,
                templates = state.templates,
                currentTemplateId = state.scheduleMap[state.selectedDate],
                onSelectTemplate = { viewModel.setScheduleForSelectedDate(it) },
                onDismiss = { viewModel.dismissBottomSheet() }
            )
        }
    }
}

@Composable
private fun MonthNavigation(
    yearMonth: YearMonth,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous")
            }
            Text(
                text = yearMonth.format(DateTimeFormatter.ofPattern("yyyy 年 M 月")),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            IconButton(onClick = onNext) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next")
            }
        }
        IconButton(onClick = onToday) {
            Icon(Icons.Default.Today, contentDescription = "Today")
        }
    }
}

@Composable
private fun DayOfWeekHeader() {
    val days = listOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY
    )
    Row(modifier = Modifier.fillMaxWidth()) {
        days.forEach { day ->
            Text(
                text = day.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                    .take(2),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun CalendarGrid(
    yearMonth: YearMonth,
    scheduleMap: Map<String, Long?>,
    templates: List<ShiftTemplate>,
    onDateClick: (String) -> Unit,
    onDateLongClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val formatter = DateTimeFormatter.ISO_LOCAL_DATE
    val firstDayOfMonth = yearMonth.atDay(1)
    val daysInMonth = yearMonth.lengthOfMonth()
    val startDayOfWeek = (firstDayOfMonth.dayOfWeek.value + 6) % 7 // Monday=0

    val today = LocalDate.now()
    val todayStr = today.format(formatter)

    val days = mutableListOf<CalendarDay>()
    // Empty cells before first day — each needs a unique key
    for (i in 0 until startDayOfWeek) {
        days.add(CalendarDay.Empty(index = i))
    }
    // Actual days
    for (day in 1..daysInMonth) {
        val date = yearMonth.atDay(day)
        val dateStr = date.format(formatter)
        val templateId = scheduleMap[dateStr]
        val template = templates.find { it.id == templateId }
        days.add(
            CalendarDay.Day(
                day = day,
                dateStr = dateStr,
                isToday = dateStr == todayStr,
                template = template
            )
        )
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(7),
        modifier = modifier.fillMaxWidth()
    ) {
        items(days, key = { it.key }) { day ->
            when (day) {
                is CalendarDay.Empty -> {
                    Box(modifier = Modifier.aspectRatio(0.85f))
                }
                is CalendarDay.Day -> {
                    DayCell(
                        day = day,
                        onClick = { onDateClick(day.dateStr) }
                    )
                }
            }
        }
    }
}

private sealed class CalendarDay(val key: String) {
    data class Empty(val index: Int) : CalendarDay("empty_$index")
    data class Day(
        val day: Int,
        val dateStr: String,
        val isToday: Boolean,
        val template: ShiftTemplate?
    ) : CalendarDay("day_$dateStr")
}

@Composable
private fun DayCell(
    day: CalendarDay.Day,
    onClick: () -> Unit
) {
    val templateColor = day.template?.color?.let { Color(it) }
    // Only shift color as background — no CalendarToday override
    val bgColor = when {
        day.template != null -> (templateColor ?: CalendarSelected).copy(alpha = 0.2f)
        else -> Color.Transparent
    }

    Box(
        modifier = Modifier
            .aspectRatio(0.85f)
            .padding(1.5.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .then(
                if (day.template != null) {
                    Modifier.border(
                        1.dp,
                        templateColor ?: CalendarShiftBorder,
                        RoundedCornerShape(8.dp)
                    )
                } else Modifier
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 4.dp, start = 1.dp, end = 1.dp)
        ) {
            // Today indicator: a circular ring around the day number
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .then(
                        if (day.isToday) {
                            Modifier.border(
                                1.5.dp,
                                MaterialTheme.colorScheme.primary,
                                CircleShape
                            )
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${day.day}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal,
                    color = if (day.isToday) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                )
            }
            if (day.template != null) {
                Text(
                    text = day.template.shiftLabel.take(4),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = templateColor ?: MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold
                )
                if (day.template.location.isNotBlank()) {
                    Text(
                        text = day.template.location.take(4),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 7.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = (templateColor ?: MaterialTheme.colorScheme.primary).copy(0.7f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun DateSelectionSheet(
    date: String,
    templates: List<ShiftTemplate>,
    currentTemplateId: Long?,
    onSelectTemplate: (Long?) -> Unit,
    onDismiss: () -> Unit
) {
    val formatter = DateTimeFormatter.ISO_LOCAL_DATE
    val localDate = LocalDate.parse(date, formatter)
    val displayText = "${localDate.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} " +
            "${localDate.dayOfMonth}, ${localDate.year}"

    Column(modifier = Modifier.padding(bottom = 32.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = displayText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
        }

        HorizontalDivider()

        Spacer(modifier = Modifier.height(8.dp))

        // Clear schedule option
        ListItem(
            headlineContent = { Text("無排班 / No Shift") },
            leadingContent = {
                if (currentTemplateId == null) {
                    Box(
                        modifier = Modifier
                            .width(24.dp)
                            .height(24.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("✓", color = Color.White, fontSize = 12.sp)
                    }
                }
            },
            modifier = Modifier.clickable { onSelectTemplate(null) }
        )

        templates.forEach { template ->
            val colorIndicator = Color(template.color)
            val isCurrent = currentTemplateId == template.id
            ListItem(
                headlineContent = {
                    Text(
                        text = "[${template.shiftLabel}] ${template.location.ifBlank { "-" }}",
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                    )
                },
                supportingContent = {
                    Text(
                        text = "📝 ${template.name} · ${template.alarmTimes.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                leadingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(colorIndicator)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        if (isCurrent) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("✓", color = Color.White, fontSize = 12.sp)
                            }
                        }
                    }
                },
                modifier = Modifier.clickable { onSelectTemplate(template.id) }
            )
        }

        if (templates.isEmpty()) {
            Text(
                text = "尚未建立班次模板\n請先在「模板」頁面建立\n\nNo templates yet.\nCreate one in the Templates tab.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp)
            )
        }
    }
}