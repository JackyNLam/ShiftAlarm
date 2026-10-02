package com.example.shiftalarm.alarm

/**
 * Represents a system clock alarm that needs to be set via ACTION_SET_ALARM.
 * Stored locally and processed deferred by [SystemAlarmSyncWorker] when the
 * device becomes idle (locked screen), matching the grace-period approach
 * used by apps like Shift Schedule.
 */
data class PendingSystemAlarm(
    val hour: Int,
    val minute: Int,
    val message: String
)