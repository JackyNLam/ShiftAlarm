package com.example.shiftalarm.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Import/export format for schedule entries.
 * Contains only date + shiftLabel + location — template details (alarmTimes, name, color)
 * are resolved by matching on shiftLabel+location at import time.
 *
 * Same format is used for both import and export (round-trip guaranteed).
 */
data class ScheduleExportEntry(
    val date: String,      // "yyyy-MM-dd"
    val shiftLabel: String,
    val location: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("date", date)
        put("shiftLabel", shiftLabel)
        put("location", location)
    }

    companion object {
        fun fromJson(obj: JSONObject): ScheduleExportEntry {
            return ScheduleExportEntry(
                date = obj.getString("date"),
                shiftLabel = obj.optString("shiftLabel", ""),
                location = obj.optString("location", "")
            )
        }
    }
}

data class ScheduleExportData(
    val version: Int = 1,
    val entries: List<ScheduleExportEntry>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", version)
        val arr = JSONArray()
        entries.forEach { arr.put(it.toJson()) }
        put("entries", arr)
    }

    fun toJsonString(prettyPrint: Boolean = true): String =
        if (prettyPrint) toJson().toString(2) else toJson().toString()

    companion object {
        fun fromJson(json: String): ScheduleExportData {
            val obj = JSONObject(json)
            // version tolerated as optional: AI-extracted output may omit it
            val version = obj.optInt("version", 1)
            val arr = obj.getJSONArray("entries")
            val entries = (0 until arr.length()).map {
                ScheduleExportEntry.fromJson(arr.getJSONObject(it))
            }
            return ScheduleExportData(version, entries)
        }
    }
}