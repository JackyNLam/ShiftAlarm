package com.example.shiftalarm.ui.screen.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.shiftalarm.alarm.AlarmScheduler
import com.example.shiftalarm.data.entity.ShiftTemplate
import com.example.shiftalarm.data.repository.ShiftRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

data class CalendarUiState(
    val currentMonth: YearMonth = YearMonth.now(),
    val templates: List<ShiftTemplate> = emptyList(),
    val scheduleMap: Map<String, Long?> = emptyMap(), // date -> templateId (null = no shift)
    val selectedDate: String? = null,
    val showBottomSheet: Boolean = false,
    val isLoading: Boolean = true
)

class CalendarViewModel(
    private val repository: ShiftRepository,
    private val scheduler: AlarmScheduler? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(CalendarUiState())
    val uiState: StateFlow<CalendarUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun refresh() {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            val templates = repository.getAllTemplates()
            _uiState.value = _uiState.value.copy(templates = templates)
        }
        loadMonth(YearMonth.now())
    }

    fun loadMonth(yearMonth: YearMonth) {
        _uiState.value = _uiState.value.copy(currentMonth = yearMonth, isLoading = true)
        val formatter = DateTimeFormatter.ISO_LOCAL_DATE
        val start = yearMonth.atDay(1).format(formatter)
        val end = yearMonth.atEndOfMonth().format(formatter)

        viewModelScope.launch {
            val entries = repository.getEntriesInRange(start, end)
            val map = mutableMapOf<String, Long?>()
            val daysInMonth = yearMonth.lengthOfMonth()
            for (day in 1..daysInMonth) {
                val date = yearMonth.atDay(day).format(formatter)
                map[date] = null
            }
            entries.forEach { entry ->
                map[entry.date] = entry.templateId
            }
            _uiState.value = _uiState.value.copy(
                scheduleMap = map,
                isLoading = false
            )
        }
    }

    fun goToPreviousMonth() {
        loadMonth(_uiState.value.currentMonth.minusMonths(1))
    }

    fun goToNextMonth() {
        loadMonth(_uiState.value.currentMonth.plusMonths(1))
    }

    fun goToToday() {
        loadMonth(YearMonth.now())
    }

    fun onDateClicked(date: String) {
        _uiState.value = _uiState.value.copy(
            selectedDate = date,
            showBottomSheet = true
        )
    }

    fun dismissBottomSheet() {
        _uiState.value = _uiState.value.copy(
            showBottomSheet = false,
            selectedDate = null
        )
    }

    fun setScheduleForSelectedDate(templateId: Long?) {
        val date = _uiState.value.selectedDate ?: return

        viewModelScope.launch {
            repository.setScheduleForDate(date, templateId)
            scheduler?.rescheduleAllAlarms()

            // Trigger all 4 background-alarm mechanisms for the next alarm
            // (same as pressing "Set Background Alarm" on HomeScreen).
            scheduler?.setNextAlarmInBackground {}

            _uiState.value = _uiState.value.copy(showBottomSheet = false)
            // Refresh current month
            loadMonth(_uiState.value.currentMonth)
        }
    }

    fun batchSetSchedule(startDate: LocalDate, endDate: LocalDate, templateId: Long?) {
        viewModelScope.launch {
            val formatter = DateTimeFormatter.ISO_LOCAL_DATE
            var current = startDate
            while (!current.isAfter(endDate)) {
                repository.setScheduleForDate(current.format(formatter), templateId)
                current = current.plusDays(1)
            }
            scheduler?.rescheduleAllAlarms()
            // Auto-set system alarm if the next one is within 23 hours
            scheduler?.autoSetSystemAlarmIfNear()
            loadMonth(_uiState.value.currentMonth)
        }
    }

    class Factory(
        private val repository: ShiftRepository,
        private val scheduler: AlarmScheduler? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CalendarViewModel(repository, scheduler) as T
        }
    }
}