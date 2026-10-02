package com.example.shiftalarm.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.shiftalarm.MainActivity
import com.example.shiftalarm.data.JsonStorage
import com.example.shiftalarm.service.SystemAlarmOverlayService
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

class AlarmScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val storage = JsonStorage(context)
    private val executor = Executors.newSingleThreadExecutor()

    companion object {
        private const val TAG = "AlarmScheduler"
        private const val DAYS_AHEAD = 14
        private const val PREFS_SYNC = "system_alarm_sync"
        private const val KEY_PENDING = "pending_alarms"
        private const val UNIQUE_WORK_NAME = "system_alarm_sync"

        /**
         * Adds [addMinutes] to the given hour:minute, returning the adjusted (hour, minute) pair.
         * Handles minute/hour rollover and wraps past midnight.
         */
        fun addMinutes(hour: Int, minute: Int, addMinutes: Int = 1): Pair<Int, Int> {
            val total = hour * 60 + minute + addMinutes
            return Pair(total / 60 % 24, total % 60)
        }

        /**
         * Tries to set an alarm in the system Clock app, using multiple strategies:
         *
         * 1. ACTION_SET_ALARM intent with extras (standard Android API).
         * 2. Direct ComponentName targeting HandleSetAlarm (Honor-specific).
         * 3. Open the Clock app's launcher as last resort.
         *
         * Every step is logged so we can debug from logcat.
         */
        fun fireSystemClockAlarm(context: Context, hour: Int, minute: Int, message: String) {
            val displayMsg = message.take(80)

            // --- Strategy 1: ACTION_SET_ALARM with SKIP_UI (silent, no user confirmation) ---
            try {
                val intent = Intent("android.intent.action.SET_ALARM").apply {
                    putExtra("android.intent.extra.alarm.HOUR", hour)
                    putExtra("android.intent.extra.alarm.MINUTES", minute)
                    putExtra("android.intent.extra.alarm.MESSAGE", displayMsg)
                    putExtra("android.intent.extra.alarm.SKIP_UI", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Log.i(TAG, "S1 OK: ACTION_SET_ALARM $hour:$minute \"$displayMsg\"")
                return
            } catch (e: SecurityException) {
                Log.w(TAG, "S1 FAIL (SecurityException): ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "S1 FAIL: ${e.message}")
            }

            // --- Strategy 2: Direct component to HandleSetAlarm (Honor) with SKIP_UI ---
            try {
                val intent = Intent().apply {
                    component = ComponentName("com.hihonor.deskclock", "com.android.deskclock.HandleSetAlarm")
                    putExtra("android.intent.extra.alarm.HOUR", hour)
                    putExtra("android.intent.extra.alarm.MINUTES", minute)
                    putExtra("android.intent.extra.alarm.MESSAGE", displayMsg)
                    putExtra("android.intent.extra.alarm.SKIP_UI", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Log.i(TAG, "S2 OK: direct HandleSetAlarm $hour:$minute \"$displayMsg\"")
                return
            } catch (e: SecurityException) {
                Log.w(TAG, "S2 FAIL (SecurityException): ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "S2 FAIL: ${e.message}")
            }

            // --- Strategy 3: Open launcher of known clock packages ---
            val clockPackages = listOf(
                "com.hihonor.deskclock",
                "com.android.deskclock",
                "com.huawei.deskclock",
                "com.sec.android.app.clockpackage",
                "com.google.android.deskclock"
            )
            for (pkg in clockPackages) {
                try {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                        Log.i(TAG, "S3 OK: opened $pkg — please set $hour:$minute \"$displayMsg\"")
                        return
                    }
                    Log.d(TAG, "S3: $pkg → getLaunchIntentForPackage=null")
                } catch (e: Exception) {
                    Log.w(TAG, "S3: $pkg error: ${e.message}")
                }
            }

            Log.w(TAG, "All 3 strategies failed for $hour:$minute \"$displayMsg\"")
        }
    }

    /**
     * Convenience instance method — fires ACTION_SET_ALARM using this scheduler's context.
     * Shows a brief overlay (if SYSTEM_ALERT_WINDOW granted) so the user sees
     * what's happening.
     */
    fun openSystemClockAlarm(hour: Int, minute: Int, message: String) {
        // Show overlay (if permission granted) so the user sees a visual cue
        if (SystemAlarmOverlayService.canDrawOverlays(context)) {
            SystemAlarmOverlayService.show(context, hour, minute, message)
        }

        fireSystemClockAlarm(context, hour, minute, message)
    }

    /**
     * Sets the next upcoming alarm entirely in the background, without requiring
     * any user confirmation or UI interaction.
     *
     * Uses three complementary mechanisms (per Ref.md and Ref.txt):
     *
     * 1. [AlarmManager.setExactAndAllowWhileIdle] with SCHEDULE_EXACT_ALARM permission
     *    → guarantees exact delivery even in Doze mode (Ref.md method).
     * 2. [AlarmManager.setAlarmClock] → always-exact, shows status bar icon.
     * 3. ACTION_SET_ALARM with EXTRA_SKIP_UI → silently adds to system Clock app.
     * 4. Deferred [SystemAlarmSyncWorker] with setRequiresDeviceIdle → backup
     *    for OEM ROMs that block background Activity launches (Ref.txt method).
     *
     * @param onResult callback: true if an alarm was found and scheduled, false otherwise
     */
    fun setNextAlarmInBackground(onResult: (Boolean) -> Unit) {
        getNextAlarm { nextAlarm ->
            if (nextAlarm == null) {
                Log.d(TAG, "No next alarm to set in background")
                onResult(false)
                return@getNextAlarm
            }

            val parts = nextAlarm.time.split(":")
            if (parts.size != 2) { onResult(false); return@getNextAlarm }
            val hour = parts[0].toIntOrNull() ?: run { onResult(false); return@getNextAlarm }
            val minute = parts[1].toIntOrNull() ?: run { onResult(false); return@getNextAlarm }

            val msg = buildString {
                append(nextAlarm.shiftLabel.ifBlank { nextAlarm.templateName })
                if (nextAlarm.location.isNotBlank()) append(" - ").append(nextAlarm.location)
            }

            // ── 1 & 2: Schedule via AlarmManager (exact alarm, SCHEDULE_EXACT_ALARM) ──
            val canExact = hasExactAlarmPermission()
            val formatter = DateTimeFormatter.ISO_LOCAL_DATE
            val entryDate = LocalDate.parse(nextAlarm.date, formatter)
            val alarmDateTime = entryDate.atTime(LocalTime.of(hour, minute))
            val triggerTime = alarmDateTime.toEpochSecond(
                java.time.ZoneId.systemDefault().rules.getOffset(alarmDateTime)
            ) * 1000L

            if (triggerTime > System.currentTimeMillis()) {
                val requestCode = "${nextAlarm.date}_0".hashCode()
                val intent = Intent(context, AlarmReceiver::class.java).apply {
                    putExtra(AlarmReceiver.EXTRA_ENTRY_DATE, nextAlarm.date)
                    putExtra(AlarmReceiver.EXTRA_ALARM_INDEX, 0)
                    putExtra(AlarmReceiver.EXTRA_TEMPLATE_NAME, nextAlarm.templateName)
                    putExtra(AlarmReceiver.EXTRA_SHIFT_LABEL, nextAlarm.shiftLabel)
                    putExtra(AlarmReceiver.EXTRA_LOCATION, nextAlarm.location)
                }

                val pendingIntent = PendingIntent.getBroadcast(
                    context, requestCode, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                // Primary: setExactAndAllowWhileIdle (uses SCHEDULE_EXACT_ALARM permission)
                // Ref.md: this is the core API for reliable background exact alarms.
                if (canExact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent
                    )
                    Log.d(TAG, "Background: setExactAndAllowWhileIdle → ${nextAlarm.date} ${nextAlarm.time}")
                }

                // Secondary: setAlarmClock (always exact without permission, shows status bar icon)
                val showIntent = PendingIntent.getActivity(
                    context, requestCode,
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerTime, showIntent),
                    pendingIntent
                )
                Log.d(TAG, "Background: setAlarmClock → ${nextAlarm.date} ${nextAlarm.time}")
            } else {
                Log.w(TAG, "Alarm time is in the past — skipping AlarmManager scheduling")
            }

            // ── 3 & 4: System clock intents — only fire if next alarm is within 24 hours ──
            val timeUntilAlarm = triggerTime - System.currentTimeMillis()
            val twentyFourHoursMs = 24L * 60 * 60 * 1000

            if (timeUntilAlarm > 0 && timeUntilAlarm < twentyFourHoursMs) {
                Log.d(TAG, "Next alarm < 24 h away (${timeUntilAlarm / 1000 / 60} min) — firing system clock intents")

                // ── 3: Try ACTION_SET_ALARM with SKIP_UI (silent system clock) ──
                // The system clock alarm is set 1 minute later than the actual shift alarm
                // time so the app's own alarm (AlarmReceiver + AppAlarmService) fires first,
                // giving the user a brief heads-up before the system clock alarm rings.
                val (sysHour, sysMinute) = addMinutes(hour, minute, 1)
                fireSystemClockAlarm(context, sysHour, sysMinute, msg)

                // ── 4: Enqueue deferred system alarm sync as backup (Ref.txt method) ──
                // When the device is idle (locked screen), many OEM ROMs grant a brief
                // grace period during which background Activity launches succeed.
                enqueueSystemAlarmSync(sysHour, sysMinute, msg)
            } else {
                Log.d(TAG, "Next alarm >= 24 h away or in past — skipping system clock intents")
            }

            Log.d(TAG, "Background alarm set complete: ${nextAlarm.date} ${nextAlarm.time} (exactPerm=$canExact)")
            onResult(true)
        }
    }

    /**
     * Auto-sets a system clock alarm if the next upcoming alarm is within 23 hours.
     *
     * Uses [WorkManager] with `setRequiresDeviceIdle(true)` to defer the actual
     * ACTION_SET_ALARM call until the device is idle (locked screen). At that
     * point many Chinese ROMs (Honor MagicOS, Huawei EMUI, MIUI, etc.) grant
     * a brief grace period during which background activity launches are treated
     * as a continuation of user intent.
     *
     * This mirrors the "local storage + deferred sync" approach used by apps
     * like Shift Schedule (see Ref.txt for details).
     */
    fun autoSetSystemAlarmIfNear(onResult: ((String) -> Unit)? = null) {
        getNextAlarm { nextAlarm ->
            if (nextAlarm != null) {
                val now = System.currentTimeMillis()
                val timeUntilAlarm = nextAlarm.epochMillis - now
                val twentyThreeHoursMs = 23L * 60 * 60 * 1000

                if (timeUntilAlarm > 0 && timeUntilAlarm < twentyThreeHoursMs) {
                    Log.i(TAG, "Next alarm within 23 h (${timeUntilAlarm / 1000 / 60} min) — scheduling system alarm via AlarmManager")
                    val parts = nextAlarm.time.split(":")
                    if (parts.size == 2) {
                        val hour = parts[0].toIntOrNull()
                        val minute = parts[1].toIntOrNull()
                        if (hour != null && minute != null) {
                            val msg = buildString {
                                append(nextAlarm.shiftLabel)
                                if (nextAlarm.location.isNotBlank()) {
                                    append(" - ").append(nextAlarm.location)
                                }
                            }
                            // System clock alarm is shifted +1 minute (same reason as in setNextAlarmInBackground)
                            val (sysHour, sysMinute) = addMinutes(hour, minute, 1)

                            // Primary: Schedule via AlarmManager + BroadcastReceiver.
                            // AlarmManager gives a brief execution window with elevated process
                            // priority, making startActivity(ACTION_SET_ALARM) more likely to
                            // succeed than calling from a WorkManager worker.
                            scheduleSystemAlarmViaAlarmManager(sysHour, sysMinute, msg)

                            // Backup: idle-constrained worker (only fires when device is idle/locked)
                            enqueueSystemAlarmSync(sysHour, sysMinute, msg)

                            onResult?.invoke("✅ Scheduled: ${nextAlarm.time} → system alarm $sysHour:$sysMinute")
                        }
                    }
                } else {
                    Log.i(TAG, "Next alarm > 23 h away or in past — skipping system alarm sync")
                    onResult?.invoke("ℹ️ Next alarm > 23 h away — nothing to set")
                }
            } else {
                Log.i(TAG, "No upcoming alarm — skipping system alarm sync")
                onResult?.invoke("ℹ️ No upcoming alarm")
            }
        }
    }

    /**
     * Schedules a one-time AlarmManager alarm that fires [SystemAlarmSetReceiver],
     * which in turn calls [fireSystemClockAlarm] to send [AlarmClock.ACTION_SET_ALARM]
     * with [AlarmClock.EXTRA_SKIP_UI].
     *
     * Uses [AlarmManager.setExactAndAllowWhileIdle] when SCHEDULE_EXACT_ALARM permission
     * is granted (guarantees delivery even in Doze), otherwise falls back to
     * [AlarmManager.setAndAllowWhileIdle].
     */
    private fun scheduleSystemAlarmViaAlarmManager(hour: Int, minute: Int, message: String) {
        val requestCode = ("sys_alarm_$hour:$minute").hashCode()
        val intent = Intent(context, SystemAlarmSetReceiver::class.java).apply {
            putExtra(SystemAlarmSetReceiver.EXTRA_HOUR, hour)
            putExtra(SystemAlarmSetReceiver.EXTRA_MINUTE, minute)
            putExtra(SystemAlarmSetReceiver.EXTRA_MESSAGE, message)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Fire ASAP — the AlarmManager execution window makes startActivity reliable
        val triggerTime = System.currentTimeMillis()

        if (hasExactAlarmPermission() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent
            )
            Log.i(TAG, "Scheduled SystemAlarmSetReceiver via setExactAndAllowWhileIdle")
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent
            )
            Log.i(TAG, "Scheduled SystemAlarmSetReceiver via setAndAllowWhileIdle")
        } else {
            alarmManager.set(
                AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent
            )
            Log.i(TAG, "Scheduled SystemAlarmSetReceiver via set")
        }
    }

    // ── Deferred system alarm sync (WorkManager + setRequiresDeviceIdle) ──

    /**
     * Saves a pending system alarm to local storage and schedules a
     * [SystemAlarmSyncWorker] to fire ACTION_SET_ALARM when the device is idle.
     *
     * Use this for **background** auto-set calls (e.g. after schedule changes).
     * For explicit user-triggered calls, use [openSystemClockAlarm] instead.
     */
    fun enqueueSystemAlarmSync(hour: Int, minute: Int, message: String) {
        val prefs = context.getSharedPreferences(PREFS_SYNC, Context.MODE_PRIVATE)
        // Merge with any existing failed entries still awaiting retry.
        val existing = prefs.getString(KEY_PENDING, "[]") ?: "[]"
        val arr = JSONArray(existing)

        // Deduplicate: skip if this exact alarm is already queued.
        val alreadyQueued = (0 until arr.length()).any { i ->
            val obj = arr.getJSONObject(i)
            obj.getInt("hour") == hour && obj.getInt("minute") == minute && obj.optString("message") == message
        }

        if (!alreadyQueued) {
            arr.put(JSONObject().apply {
                put("hour", hour)
                put("minute", minute)
                put("message", message)
            })
        }

        prefs.edit().putString(KEY_PENDING, arr.toString()).apply()
        scheduleSystemAlarmWork()
    }

    /**
     * Batch-queues multiple pending system alarms in a single write.
     */
    fun enqueueSystemAlarmSyncBatch(alarms: List<PendingSystemAlarm>) {
        if (alarms.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS_SYNC, Context.MODE_PRIVATE)
        // Merge with any existing failed entries still awaiting retry.
        val existing = prefs.getString(KEY_PENDING, "[]") ?: "[]"
        val arr = JSONArray(existing)

        for (alarm in alarms) {
            // Deduplicate: skip if this exact alarm is already queued.
            val alreadyQueued = (0 until arr.length()).any { i ->
                val obj = arr.getJSONObject(i)
                obj.getInt("hour") == alarm.hour && obj.getInt("minute") == alarm.minute && obj.optString("message") == alarm.message
            }

            if (!alreadyQueued) {
                arr.put(JSONObject().apply {
                    put("hour", alarm.hour)
                    put("minute", alarm.minute)
                    put("message", alarm.message)
                })
            }
        }

        prefs.edit().putString(KEY_PENDING, arr.toString()).apply()
        scheduleSystemAlarmWork()
    }

    /**
     * Enqueues a [SystemAlarmSyncWorker] that runs when the device is idle
     * (locked screen). Uses [ExistingWorkPolicy.REPLACE] so only one worker
     * is ever pending — it processes ALL queued alarms when it fires.
     */
    private fun scheduleSystemAlarmWork() {
        val constraints = Constraints.Builder()
            .setRequiresDeviceIdle(true)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<SystemAlarmSyncWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            workRequest
        )
        Log.d(TAG, "System alarm sync work enqueued (device-idle constrained)")
    }

    fun hasExactAlarmPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    fun hasOverlayPermission(): Boolean = SystemAlarmOverlayService.canDrawOverlays(context)

    fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            Log.w(TAG, "Could not open overlay permission settings")
        }
    }

    fun rescheduleAllAlarms() {
        executor.execute {
            try {
                cancelAllAlarms()
                val today = LocalDate.now()
                val formatter = DateTimeFormatter.ISO_LOCAL_DATE
                val endDate = today.plusDays(DAYS_AHEAD.toLong())

                val templates = storage.loadTemplates().associateBy { it.id }
                val rawEntries = storage.getEntriesFrom(today.format(formatter))
                val limited = rawEntries.filter { entry ->
                    val entryDate = LocalDate.parse(entry.date, formatter)
                    !entryDate.isAfter(endDate)
                }

                for (entry in limited) {
                    val template = entry.templateId?.let { templates[it] } ?: continue

                    for ((index, timeStr) in template.alarmTimes.withIndex()) {
                        val parts = timeStr.split(":")
                        if (parts.size != 2) continue
                        val hour = parts[0].toIntOrNull() ?: continue
                        val minute = parts[1].toIntOrNull() ?: continue

                        scheduleAlarm(
                            entryDateStr = entry.date,
                            hour = hour,
                            minute = minute,
                            alarmIndex = index,
                            templateName = template.name,
                            shiftLabel = template.shiftLabel,
                            location = template.location
                        )
                    }
                }
                Log.d(TAG, "Rescheduled alarms for $DAYS_AHEAD days ahead")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reschedule alarms", e)
            }
        }
        // Call this OUTSIDE the executor to avoid deadlock with getNextAlarm
        // which also uses the same single-thread executor.
        autoSetSystemAlarmIfNear()
    }

    private fun scheduleAlarm(
        entryDateStr: String,
        hour: Int,
        minute: Int,
        alarmIndex: Int,
        templateName: String,
        shiftLabel: String,
        location: String
    ) {
        val formatter = DateTimeFormatter.ISO_LOCAL_DATE
        val entryDate = LocalDate.parse(entryDateStr, formatter)
        val alarmTime = LocalTime.of(hour, minute)
        val alarmDateTime = entryDate.atTime(alarmTime)

        val triggerTime = alarmDateTime.toEpochSecond(
            java.time.ZoneId.systemDefault().rules.getOffset(alarmDateTime)
        ) * 1000L

        val now = System.currentTimeMillis()
        if (triggerTime <= now) return

        val requestCode = "${entryDateStr}_${alarmIndex}".hashCode()
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(AlarmReceiver.EXTRA_ENTRY_DATE, entryDateStr)
            putExtra(AlarmReceiver.EXTRA_ALARM_INDEX, alarmIndex)
            putExtra(AlarmReceiver.EXTRA_TEMPLATE_NAME, templateName)
            putExtra(AlarmReceiver.EXTRA_SHIFT_LABEL, shiftLabel)
            putExtra(AlarmReceiver.EXTRA_LOCATION, location)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Uses setAlarmClock so the system shows the alarm clock in the UI
        // (status bar bell icon, lock-screen "upcoming alarm").
        // setAlarmClock is always exact and does NOT require SCHEDULE_EXACT_ALARM permission.
        val showIntent = PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerTime, showIntent),
            pendingIntent
        )

        // Secondary: also schedule via setExactAndAllowWhileIdle for better Doze-mode
        // delivery on some OEM ROMs (Honor, Huawei, Xiaomi etc.).
        // The BroadcastReceiver handles both; duplicate delivery is harmless — it just
        // re-posts the same notification which is replaced by the same ID.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
                Log.d(TAG, "Secondary setExactAndAllowWhileIdle scheduled for $entryDateStr index=$alarmIndex")
            }
        }
    }

    private fun cancelAllAlarms() {
        // Cancel every PendingIntent that was previously scheduled for any entry + alarm index.
        // This is necessary because rescheduleAllAlarms() may remove entries, and stale
        // PendingIntents would otherwise keep firing.
        val templates = storage.loadTemplates().associateBy { it.id }
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val futureEntries = storage.getEntriesFrom(today)
        for (entry in futureEntries) {
            val template = entry.templateId?.let { templates[it] } ?: continue
            for (index in template.alarmTimes.indices) {
                val requestCode = "${entry.date}_${index}".hashCode()
                val intent = Intent(context, AlarmReceiver::class.java)
                val pi = PendingIntent.getBroadcast(
                    context, requestCode, intent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                pi?.let { alarmManager.cancel(it) }
            }
        }
    }

    data class NextAlarm(
        val date: String,
        val time: String,
        val templateName: String,
        val shiftLabel: String,
        val location: String,
        val epochMillis: Long
    )

    fun getNextAlarm(onResult: (NextAlarm?) -> Unit) {
        executor.execute {
            try {
                val today = LocalDate.now()
                val formatter = DateTimeFormatter.ISO_LOCAL_DATE
                val rawEntries = storage.getEntriesFrom(today.format(formatter))
                val templates = storage.loadTemplates().associateBy { it.id }

                var next: NextAlarm? = null
                val now = System.currentTimeMillis()

                for (entry in rawEntries) {
                    val template = entry.templateId?.let { templates[it] } ?: continue
                    val entryDate = LocalDate.parse(entry.date, formatter)

                    for (timeStr in template.alarmTimes) {
                        val parts = timeStr.split(":")
                        if (parts.size != 2) continue
                        val hour = parts[0].toIntOrNull() ?: continue
                        val minute = parts[1].toIntOrNull() ?: continue

                        val alarmDateTime = entryDate.atTime(LocalTime.of(hour, minute))
                        val epochMillis = alarmDateTime.toEpochSecond(
                            java.time.ZoneId.systemDefault().rules.getOffset(alarmDateTime)
                        ) * 1000L

                        if (epochMillis > now && (next == null || epochMillis < next.epochMillis)) {
                            next = NextAlarm(
                                date = entry.date,
                                time = timeStr,
                                templateName = template.name,
                                shiftLabel = template.shiftLabel,
                                location = template.location,
                                epochMillis = epochMillis
                            )
                        }
                    }
                }

                onResult(next)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get next alarm", e)
                onResult(null)
            }
        }
    }
}