package com.example.shiftalarm.data.entity

data class ShiftTemplate(
    val id: Long = System.currentTimeMillis(),
    val name: String = "",
    val shiftLabel: String = "",
    val alarmTimes: List<String> = emptyList(),
    val location: String = "",
    val sortOrder: Int = 0,
    val color: Long = 0xFF42A5F5L // default blue
)