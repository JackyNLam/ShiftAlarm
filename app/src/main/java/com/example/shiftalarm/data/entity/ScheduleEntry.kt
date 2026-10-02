package com.example.shiftalarm.data.entity

data class ScheduleEntry(
    val id: Long = System.currentTimeMillis(),
    val date: String = "", // "yyyy-MM-dd"
    val templateId: Long? = null
)