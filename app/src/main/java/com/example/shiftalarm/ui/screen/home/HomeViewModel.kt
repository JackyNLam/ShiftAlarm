package com.example.shiftalarm.ui.screen.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.shiftalarm.alarm.AlarmScheduler
import com.example.shiftalarm.data.DashScopeApi
import com.example.shiftalarm.data.ScheduleExportData
import com.example.shiftalarm.data.ScheduleImageStorage
import com.example.shiftalarm.data.repository.ShiftRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saved DashScope options for AI schedule extraction (persisted in SharedPreferences). */
data class AiOptions(
    val apiKey: String = "",
    val model: String = "qwen-vl-plus",
    val prompt: String = DEFAULT_AI_PROMPT
)

private const val DEFAULT_AI_PROMPT = "你是排班表解析助手。請分析圖片中的排班表，輸出為 JSON。\n" +
    "格式必須是：{\"version\":1,\"entries\":[{\"date\":\"yyyy-MM-dd\",\"shiftLabel\":\"班別\",\"location\":\"地點\"}]}\n" +
    "規則：\n" +
    "1. 每一天一筆 entry，date 用 yyyy-MM-dd 格式。\n" +
    "2. 圖片沒有標示年份時一律視為 2026 年。\n" +
    "3. 只輸出 JSON，不要任何前言、後語或 Markdown 標記。"

data class HomeUiState(
    val nextAlarm: AlarmScheduler.NextAlarm? = null,
    val templateCount: Int = 0,
    val scheduleCount: Int = 0,
    val hasExactAlarmPermission: Boolean = true,
    val canDrawOverlays: Boolean = false,
    val backgroundAlarmResult: Boolean? = null,
    val hourlyCheckResult: String? = null,
    val importExportResult: String? = null,
    val scheduleImages: List<String> = emptyList(),
    val aiOptions: AiOptions = AiOptions(),
    val aiBusy: Boolean = false,
    val aiResult: String? = null,
    val pendingAiExportJson: String? = null,
    val aiDebugLog: String = "",
    val aiStartedAt: Long? = null,
    val isLoading: Boolean = true
)

class HomeViewModel(
    private val repository: ShiftRepository,
    private val scheduler: AlarmScheduler,
    private val appContext: android.content.Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val imageStorage = ScheduleImageStorage(appContext)
    private val dashScopeApi = DashScopeApi()

    companion object {
        private const val PREFS_NAME = "home_ui"
        private const val KEY_BG_ALARM_RESULT = "background_alarm_result"

        // AI option persistence
        private const val PREFS_AI = "ai_options"
        private const val KEY_API_KEY = "dashscope_api_key"
        private const val KEY_MODEL = "dashscope_model"
        private const val KEY_PROMPT = "dashscope_prompt"
    }

    init {
        // Restore persisted background alarm result (survives tab switches + process death)
        val prefs = appContext.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        if (prefs.contains(KEY_BG_ALARM_RESULT)) {
            val saved = prefs.getBoolean(KEY_BG_ALARM_RESULT, true)
            _uiState.value = _uiState.value.copy(backgroundAlarmResult = saved)
        }
        // Restore saved DashScope options (API key / model / prompt)
        _uiState.value = _uiState.value.copy(aiOptions = loadAiOptions())
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            val templates = repository.getAllTemplates()
            val entries = repository.getAllEntries()
            val images = withContext(Dispatchers.IO) { imageStorage.listImages() }
            _uiState.value = _uiState.value.copy(
                templateCount = templates.size,
                scheduleCount = entries.size,
                hasExactAlarmPermission = scheduler.hasExactAlarmPermission(),
                canDrawOverlays = scheduler.hasOverlayPermission(),
                scheduleImages = images
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
     * Import the whole schedule file — every entry (date + shiftLabel + location) is
     * applied, matching/creating templates by shiftLabel + location (multi-day import).
     */
    fun importSchedule(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(importExportResult = null)
            try {
                val json = appContext.contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader(Charset.forName("UTF-8")).readText()
                } ?: throw Exception("無法讀取檔案 / Cannot read file")

                val data = ScheduleExportData.fromJson(json)
                if (data.entries.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        importExportResult = "❌ 檔案中沒有排程記錄 / No schedule entries in file"
                    )
                    return@launch
                }

                val count = repository.importScheduleEntries(data.entries)
                scheduler.rescheduleAllAlarms()
                refresh()
                _uiState.value = _uiState.value.copy(
                    importExportResult = "✅ 已匯入 $count 筆排程 / Imported $count schedules"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    importExportResult = "❌ 匯入失敗: ${e.message}"
                )
            }
        }
    }

    // --- Schedule images (photos of the paper schedule) ---

    fun importScheduleImage(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(importExportResult = null)
            try {
                val name = withContext(Dispatchers.IO) { imageStorage.saveImage(uri) }
                val images = withContext(Dispatchers.IO) { imageStorage.listImages() }
                _uiState.value = _uiState.value.copy(
                    scheduleImages = images,
                    importExportResult = "✅ 已儲存排程圖片 / Schedule image saved"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    importExportResult = "❌ 圖片匯入失敗: ${e.message}"
                )
            }
        }
    }

    fun deleteScheduleImage(name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { imageStorage.delete(name) }
            val images = withContext(Dispatchers.IO) { imageStorage.listImages() }
            _uiState.value = _uiState.value.copy(scheduleImages = images)
        }
    }

    // --- AI extraction (DashScope vision model) ---

    /**
     * Asks DashScope to parse the selected schedule image into import/export-format
     * JSON, then stages the JSON for export (pendingAiExportJson) — the UI auto-opens
     * a "save as" dialog so the result lands in the same format the app imports.
     */
    fun extractScheduleWithAi(apiKey: String, model: String, prompt: String, imageName: String) {
        viewModelScope.launch {
            val resolvedModel = model.ifBlank { "qwen-vl-plus" }
            _uiState.update {
                it.copy(
                    aiBusy = true,
                    aiResult = null,
                    pendingAiExportJson = null,
                    aiStartedAt = System.currentTimeMillis(),
                    aiDebugLog = "[${ts()}] 開始 AI 擷取 / Start extraction (model: $resolvedModel)"
                )
            }
            try {
                if (apiKey.isBlank()) throw Exception("請輸入 DashScope API Key")
                if (imageName.isBlank()) throw Exception("請選擇排程圖片 / Select a schedule image")
                val imageFile = imageStorage.fileFor(imageName)
                if (!imageFile.exists()) throw Exception("圖片不存在 / Image not found: $imageName")

                // Save options first so the config survives even if the call fails
                saveAiOptions(AiOptions(apiKey = apiKey, model = resolvedModel, prompt = prompt))
                appendAiLog("檢查通過: ${imageFile.name}（${imageFile.length()} bytes）")

                val content = withContext(Dispatchers.IO) {
                    dashScopeApi.extractContent(apiKey, resolvedModel, prompt, imageFile) { line ->
                        appendAiLog(line)
                    }
                }
                val data = ScheduleExportData.fromJson(cleanAiJson(content))
                if (data.entries.isEmpty()) {
                    throw Exception("AI 沒有解析到排程 / AI returned no entries")
                }
                appendAiLog("JSON 解析成功: ${data.entries.size} 筆排程")
                _uiState.update {
                    it.copy(
                        aiBusy = false,
                        aiStartedAt = null,
                        aiResult = "✅ 解析成功: ${data.entries.size} 筆排程，請選擇儲存位置…",
                        pendingAiExportJson = data.toJsonString(prettyPrint = true)
                    )
                }
            } catch (e: Exception) {
                appendAiLog("❌ 失敗: ${e.message}")
                _uiState.update {
                    it.copy(
                        aiBusy = false,
                        aiStartedAt = null,
                        aiResult = "❌ AI 擷取失敗: ${e.message}"
                    )
                }
            }
        }
    }

    /** Clears the on-screen AI debug log. */
    fun clearAiDebugLog() {
        _uiState.update { it.copy(aiDebugLog = "") }
    }

    private fun appendAiLog(line: String) {
        _uiState.update { it.copy(aiDebugLog = it.aiDebugLog + "\n[${ts()}] $line") }
    }

    private fun ts(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    /** Writes the pending AI-extracted JSON to the user-chosen file (SAF uri). */
    fun completeAiExport(uri: Uri) {
        viewModelScope.launch {
            val json = _uiState.value.pendingAiExportJson ?: return@launch
            try {
                appContext.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charset.forName("UTF-8")))
                }
                _uiState.value = _uiState.value.copy(
                    pendingAiExportJson = null,
                    aiResult = "✅ AI 排程已匯出 / AI schedule exported"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    pendingAiExportJson = null,
                    aiResult = "❌ 匯出失敗: ${e.message}"
                )
            }
        }
    }

    private fun loadAiOptions(): AiOptions {
        val prefs = appContext.getSharedPreferences(PREFS_AI, android.content.Context.MODE_PRIVATE)
        return AiOptions(
            apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
            model = prefs.getString(KEY_MODEL, "qwen-vl-plus") ?: "qwen-vl-plus",
            prompt = prefs.getString(KEY_PROMPT, DEFAULT_AI_PROMPT) ?: DEFAULT_AI_PROMPT
        )
    }

    private fun saveAiOptions(options: AiOptions) {
        appContext.getSharedPreferences(PREFS_AI, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_API_KEY, options.apiKey)
            .putString(KEY_MODEL, options.model)
            .putString(KEY_PROMPT, options.prompt)
            .apply()
    }

    /** Strips markdown fences the model may wrap around its JSON answer. */
    private fun cleanAiJson(content: String): String {
        var s = content.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```json").removePrefix("```").trim()
            s = s.removeSuffix("```").trim()
        }
        return s
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