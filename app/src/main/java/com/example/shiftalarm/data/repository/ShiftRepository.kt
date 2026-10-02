package com.example.shiftalarm.data.repository

import com.example.shiftalarm.data.JsonStorage
import com.example.shiftalarm.data.ScheduleExportData
import com.example.shiftalarm.data.ScheduleExportEntry
import com.example.shiftalarm.data.entity.ScheduleEntry
import com.example.shiftalarm.data.entity.ShiftTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ShiftRepository(
    private val storage: JsonStorage
) {

    suspend fun getAllTemplates(): List<ShiftTemplate> = withContext(Dispatchers.IO) {
        storage.loadTemplates()
    }

    suspend fun getTemplateById(id: Long): ShiftTemplate? = withContext(Dispatchers.IO) {
        storage.getTemplateById(id)
    }

    suspend fun saveTemplate(template: ShiftTemplate): Long = withContext(Dispatchers.IO) {
        storage.saveTemplate(template)
    }

    suspend fun updateTemplate(template: ShiftTemplate) = withContext(Dispatchers.IO) {
        storage.updateTemplate(template)
    }

    suspend fun deleteTemplateById(id: Long) = withContext(Dispatchers.IO) {
        storage.deleteTemplate(id)
    }

    suspend fun countFutureSchedulesByTemplate(templateId: Long): Int =
        withContext(Dispatchers.IO) {
            storage.countFutureSchedulesByTemplate(templateId)
        }

    // --- Schedule operations ---

    suspend fun getAllEntries(): List<ScheduleEntry> = withContext(Dispatchers.IO) {
        storage.loadEntries()
    }

    suspend fun getEntriesInRange(startDate: String, endDate: String): List<ScheduleEntry> =
        withContext(Dispatchers.IO) {
            storage.getEntriesInRange(startDate, endDate)
        }

    suspend fun getEntryByDate(date: String): ScheduleEntry? = withContext(Dispatchers.IO) {
        storage.getEntryByDate(date)
    }

    suspend fun setScheduleForDate(date: String, templateId: Long?) =
        withContext(Dispatchers.IO) {
            storage.setScheduleForDate(date, templateId)
        }

    suspend fun deleteEntryByDate(date: String) = withContext(Dispatchers.IO) {
        storage.deleteEntryByDate(date)
    }

    suspend fun getSchedulesWithTemplates(fromDate: String): List<ScheduleWithTemplate> =
        withContext(Dispatchers.IO) {
            val entries = storage.getEntriesFrom(fromDate)
            entries.map { entry ->
                val template = entry.templateId?.let { storage.getTemplateById(it) }
                ScheduleWithTemplate(entry, template)
            }
        }

    data class ScheduleWithTemplate(
        val entry: ScheduleEntry,
        val template: ShiftTemplate?
    )

    // --- Import / Export (schedule entries only: date + shiftLabel + location) ---

    suspend fun exportAllEntries(): ScheduleExportData = withContext(Dispatchers.IO) {
        val entries = storage.loadEntries()
        val templates = storage.loadTemplates().associateBy { it.id }

        val exportEntries = entries.mapNotNull { entry ->
            val template = entry.templateId?.let { templates[it] }
            if (template != null) {
                ScheduleExportEntry(
                    date = entry.date,
                    shiftLabel = template.shiftLabel,
                    location = template.location
                )
            } else null
        }
        ScheduleExportData(entries = exportEntries)
    }

    /**
     * Import every entry of a schedule file (multi-day). Templates are matched or
     * created by (shiftLabel, location), and each date gets the resolved template
     * assigned. Returns the number of entries imported.
     */
    suspend fun importScheduleEntries(entries: List<ScheduleExportEntry>): Int =
        withContext(Dispatchers.IO) {
            val templates = storage.loadTemplates().toMutableList()
            var imported = 0
            for (entry in entries) {
                if (entry.date.isBlank()) continue
                val existing = templates.find {
                    it.shiftLabel == entry.shiftLabel && it.location == entry.location
                }
                val templateId = if (existing != null) {
                    existing.id
                } else {
                    // Create new template with the given shiftLabel + location; other fields default
                    val newTemplate = ShiftTemplate(
                        id = System.currentTimeMillis(),
                        shiftLabel = entry.shiftLabel,
                        location = entry.location,
                        name = entry.shiftLabel // name defaults to shiftLabel
                    )
                    val savedId = storage.saveTemplate(newTemplate)
                    templates.add(newTemplate)
                    savedId
                }
                storage.setScheduleForDate(entry.date, templateId)
                imported++
            }
            imported
        }
}