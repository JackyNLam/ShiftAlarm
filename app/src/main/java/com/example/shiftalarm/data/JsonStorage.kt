package com.example.shiftalarm.data

import android.content.Context
import android.content.SharedPreferences
import com.example.shiftalarm.data.entity.ScheduleEntry
import com.example.shiftalarm.data.entity.ShiftTemplate
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight JSON-based storage using SharedPreferences.
 * Replaces Room/KSP to avoid annotation-processing issues with JDK 25.
 */
class JsonStorage(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("shift_alarm_data", Context.MODE_PRIVATE)

    // --- Templates ---

    fun loadTemplates(): List<ShiftTemplate> {
        val json = prefs.getString(KEY_TEMPLATES, "[]") ?: "[]"
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            val alarmTimes = mutableListOf<String>()
            val timesArr = obj.optJSONArray("alarmTimes")
            if (timesArr != null) {
                for (j in 0 until timesArr.length()) {
                    alarmTimes.add(timesArr.getString(j))
                }
            }
            ShiftTemplate(
                id = obj.getLong("id"),
                name = obj.getString("name"),
                shiftLabel = obj.optString("shiftLabel", ""),
                alarmTimes = alarmTimes,
                location = obj.optString("location", ""),
                sortOrder = obj.optInt("sortOrder", 0),
                color = obj.optLong("color", 0xFF42A5F5L)
            )
        }
    }

    fun saveTemplate(template: ShiftTemplate): Long {
        val templates = loadTemplates().toMutableList()
        val existingIndex = templates.indexOfFirst { it.id == template.id }
        val saved = if (template.id == 0L || existingIndex < 0) {
            val newId = System.currentTimeMillis()
            template.copy(id = newId)
        } else {
            template
        }
        if (existingIndex >= 0) {
            templates[existingIndex] = saved
        } else {
            templates.add(saved)
        }
        saveTemplatesList(templates)
        return saved.id
    }

    fun updateTemplate(template: ShiftTemplate) {
        val templates = loadTemplates().toMutableList()
        val index = templates.indexOfFirst { it.id == template.id }
        if (index >= 0) {
            templates[index] = template
            saveTemplatesList(templates)
        }
    }

    fun deleteTemplate(id: Long) {
        val templates = loadTemplates().toMutableList()
        templates.removeAll { it.id == id }
        saveTemplatesList(templates)
        // Also remove associated schedule entries
        val entries = loadEntries().toMutableList()
        entries.removeAll { it.templateId == id }
        saveEntriesList(entries)
    }

    fun getTemplateById(id: Long): ShiftTemplate? =
        loadTemplates().find { it.id == id }

    private fun saveTemplatesList(templates: List<ShiftTemplate>) {
        val arr = JSONArray()
        templates.sortedBy { it.sortOrder }.forEach { t ->
            val timesArr = JSONArray()
            t.alarmTimes.forEach { timesArr.put(it) }
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("name", t.name)
                put("shiftLabel", t.shiftLabel)
                put("alarmTimes", timesArr)
                put("location", t.location)
                put("sortOrder", t.sortOrder)
                put("color", t.color)
            })
        }
        prefs.edit().putString(KEY_TEMPLATES, arr.toString()).apply()
    }

    // --- Schedule Entries ---

    fun loadEntries(): List<ScheduleEntry> {
        val json = prefs.getString(KEY_ENTRIES, "[]") ?: "[]"
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            ScheduleEntry(
                id = obj.getLong("id"),
                date = obj.getString("date"),
                templateId = if (obj.has("templateId") && !obj.isNull("templateId"))
                    obj.getLong("templateId") else null
            )
        }
    }

    fun getEntryByDate(date: String): ScheduleEntry? =
        loadEntries().find { it.date == date }

    fun getEntriesInRange(startDate: String, endDate: String): List<ScheduleEntry> {
        return loadEntries().filter { entry ->
            entry.date >= startDate && entry.date <= endDate
        }
    }

    fun getEntriesFrom(date: String): List<ScheduleEntry> =
        loadEntries().filter { it.date >= date }

    fun setScheduleForDate(date: String, templateId: Long?) {
        val entries = loadEntries().toMutableList()
        val existing = entries.indexOfFirst { it.date == date }
        if (templateId == null) {
            if (existing >= 0) entries.removeAt(existing)
        } else {
            val entry = ScheduleEntry(
                id = if (existing >= 0) entries[existing].id else System.currentTimeMillis(),
                date = date,
                templateId = templateId
            )
            if (existing >= 0) {
                entries[existing] = entry
            } else {
                entries.add(entry)
            }
        }
        saveEntriesList(entries)
    }

    fun deleteEntryByDate(date: String) {
        val entries = loadEntries().toMutableList()
        entries.removeAll { it.date == date }
        saveEntriesList(entries)
    }

    fun deleteEntriesInRange(startDate: String, endDate: String) {
        val entries = loadEntries().toMutableList()
        entries.removeAll { it.date >= startDate && it.date <= endDate }
        saveEntriesList(entries)
    }

    fun countFutureSchedulesByTemplate(templateId: Long): Int =
        loadEntries().count { it.templateId == templateId && it.date >= todayString() }

    private fun saveEntriesList(entries: List<ScheduleEntry>) {
        val arr = JSONArray()
        entries.sortedBy { it.date }.forEach { e ->
            arr.put(JSONObject().apply {
                put("id", e.id)
                put("date", e.date)
                put("templateId", e.templateId ?: JSONObject.NULL)
            })
        }
        prefs.edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_TEMPLATES = "shift_templates"
        private const val KEY_ENTRIES = "schedule_entries"

        private fun todayString(): String {
            return java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        }
    }
}