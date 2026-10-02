package com.example.shiftalarm.ui.screen.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.shiftalarm.alarm.AlarmScheduler
import com.example.shiftalarm.data.ScheduleExportEntry
import com.example.shiftalarm.data.repository.ShiftRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.charset.Charset

data class HomeUiState(
    val nextAlarm: AlarmScheduler.NextAlarm? = null,
    val templateCount: Int = 0,
    val scheduleCount: Int = 0,
    val hasExactAlarmPermission: Boolean = true,
    val canDrawOverlays: Boolean = false,
    val backgroundAlarmResult: Boolean? = null,
    val hourlyCheckResult: String? = null,
    val importExportResult: String? = null,
    val isLoading: Boolean = true
)

class HomeViewModel(
    private val repository: ShiftRepository,
    private val scheduler: AlarmScheduler,
    private val appContext: android.content.Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    companion object {
        private const val PREFS_NAME = "home_ui"
        private const val KEY_BG_ALARM_RESULT = "background_alarm_result"
    }

    init {
        // Restore persisted background alarm result (survives tab switches + process death)
        val prefs = appContext.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        if (prefs.contains(KEY_BG_ALARM_RESULT)) {
            val saved = prefs.getBoolean(KEY_BG_ALARM_RESULT, true)
            _uiState.value = _uiState.value.copy(backgroundAlarmResult = saved)
        }
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            val templates = repository.getAllTemplates()
            val entries = repository.getAllEntries()
            _uiState.value = _uiState.value.copy(
                templateCount = templates.size,
                scheduleCount = entries.size,
                hasExactAlarmPermission = scheduler.hasExactAlarmPermission(),
                canDrawOverlays = scheduler.hasOverlayPermission()
            )
        }
        loadNextAlarm()
    }

    fun refresh() {
        loadData()
        // Check if we should auto-set the system clock alarm
        scheduler.autoSetSystemAlarmIfNear()
    }

    fun loadNextAlarm() {
        scheduler.getNextAlarm { alarm ->
            _uiState.value = _uiState.value.copy(
                nextAlarm = alarm,
                isLoading = false
            )
        }
    }

    fun openAlarmSettings() {
        scheduler.openExactAlarmSettings()
    }

    fun openOverlaySettings() {
        scheduler.openOverlaySettings()
    }

    fun rescheduleAlarms() {
        scheduler.rescheduleAllAlarms()
        loadNextAlarm()
    }

    fun setSystemClockForNextAlarm() {
        val next = _uiState.value.nextAlarm ?: return
        val parts = next.time.split(":")
        if (parts.size != 2) return
        val hour = parts[0].toIntOrNull() ?: return
        val minute = parts[1].toIntOrNull() ?: return
        val msg = buildString {
            append(next.shiftLabel)
            if (next.location.isNotBlank()) {
                append(" - ").append(next.location)
            }
        }
        scheduler.setNextAlarmInBackground { success ->
            _uiState.value = _uiState.value.copy(backgroundAlarmResult = success)
            // Persist so it survives tab switches and app reopen
            appContext.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_BG_ALARM_RESULT, success)
                .apply()
        }
    }

    fun clearBackgroundAlarmResult() {
        _uiState.value = _uiState.value.copy(backgroundAlarmResult = null)
        appContext.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_BG_ALARM_RESULT)
            .apply()
    }

    fun testHourlyCheck() {
        scheduler.autoSetSystemAlarmIfNear { result ->
            _uiState.value = _uiState.value.copy(hourlyCheckResult = result)
        }
    }

    fun clearHourlyCheckResult() {
        _uiState.value = _uiState.value.copy(hourlyCheckResult = null)
    }

    // --- Import / Export ---

    /**
     * Export all schedule entries to a JSON file via the given content URI.
     */
    fun exportSchedule(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(importExportResult = null)
            try {
                val data = repository.exportAllEntries()
                val json = data.toJsonString(prettyPrint = true)
                appContext.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charset.forName("UTF-8")))
                }
                _uiState.value = _uiState.value.copy(
                    importExportResult = "✅ 匯出成功 (${data.entries.size} 筆記錄) / Export successful"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    importExportResult = "❌ 匯出失敗: ${e.message}"
                )
            }
        }
    }

    /**
     * Import schedule for a specific date from a JSON file.
     * Matches by date → uses shiftLabel+location to find or create a template.
     */
    fun importSchedule(date: String, uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(importExportResult = null)
            try {
                val json = appContext.contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader(Charset.forName("UTF-8")).readText()
                } ?: throw Exception("無法讀取檔案 / Cannot read file")

                val data = com.example.shiftalarm.data.ScheduleExportData.fromJson(json)
                val entry = data.entries.find { it.date == date }

                if (entry == null) {
                    _uiState.value = _uiState.value.copy(
                        importExportResult = "❌ 檔案中未找到 $date 的排程 / No entry for $date in file"
                    )
                    return@launch
                }

                repository.importEntryForDate(date, entry)
                refresh() // reload counts and next alarm
                _uiState.value = _uiState.value.copy(
                    importExportResult = "✅ $date → ${entry.shiftLabel} 已匯入 / Imported"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    importExportResult = "❌ 匯入失敗: ${e.message}"
                )
            }
        }
    }

    fun clearImportExportResult() {
        _uiState.value = _uiState.value.copy(importExportResult = null)
    }

    class Factory(
        private val repository: ShiftRepository,
        private val scheduler: AlarmScheduler,
        private val appContext: android.content.Context
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(repository, scheduler, appContext) as T
        }
    }
}