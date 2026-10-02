# ShiftAlarm

A Jetpack Compose Android app for managing shift-work alarms. Lets you define shift templates with alarm times, assign them to calendar dates, and receive reliable background alarms — including silent system-clock integration.

---

## Architecture

```
ShiftAlarmApp (Application) — schedules CheckNearAlarmWorker (1h periodic)
└── MainActivity (ComponentActivity)
    └── AppNavigation (bottom-nav: Home / Calendar / Templates)
        ├── HomeScreen        — next alarm, stats, permissions, background alarm trigger, schedule import/export
        ├── CalendarScreen    — monthly grid, shift assignment via bottom sheet
        └── TemplateListScreen → TemplateEditScreen — CRUD shift templates

Data Layer:
  JsonStorage (SharedPrefs) → ShiftRepository (suspend wrappers) → ViewModels
    ├── ShiftTemplate    { id, name, shiftLabel, alarmTimes[], location, sortOrder }
    └── ScheduleEntry    { id, date("yyyy-MM-dd"), templateId }

Alarm System:
  AlarmScheduler → AlarmManager (setAlarmClock + setExactAndAllowWhileIdle)
                → AlarmReceiver (BroadcastReceiver → Notification + FullScreenIntent)
                → AlarmActivity (full-screen lock-screen alarm UI)
                → SystemAlarmSetReceiver (AlarmManager-exact BroadcastReceiver → fireSystemClockAlarm)
                → SystemAlarmSyncWorker (deferred WorkManager → ACTION_SET_ALARM + SKIP_UI, backup)
                → CheckNearAlarmWorker (periodic WorkManager every 1h → autoSetSystemAlarmIfNear)
```

### Schedule Import / Export

Available on the **Home** screen. Export writes all scheduled entries to a JSON file; import reads a file and assigns the schedule for a user-selected date.

**Data format** (same for both directions — round-trip guaranteed):

```json
{
  "version": 1,
  "entries": [
    { "date": "2026-09-24", "shiftLabel": "早班", "location": "Factory A" }
  ]
}
```

| Field | Description |
|-------|-------------|
| `date` | "yyyy-MM-dd" — the calendar date |
| `shiftLabel` | Display label from the shift template (e.g. "早班") |
| `location` | Location string from the shift template |

Only `date`, `shiftLabel`, and `location` are stored in the file. Template details (alarm times, color, name) are resolved at import time by matching on `shiftLabel` + `location`:

- **Match found** — existing template is reused (its alarm times, color, etc. stay unchanged).
- **No match** — a new template is created with the given `shiftLabel` and `location`; alarm times default to empty.

### Entities (`data/entity/`)

| File | Description |
|------|-------------|
| `ShiftTemplate.kt` | Shift type: name, shiftLabel (e.g. "早班"), alarmTimes (list of "HH:mm"), location, sortOrder |
| `ScheduleEntry.kt` | Maps a date to a template: `date` + `templateId` (nullable = no shift) |

### Storage (`data/JsonStorage.kt`)
- JSON-over-SharedPreferences persistence (no Room/KSP, avoids JDK 25 annotation issues)
- `loadTemplates()`, `saveTemplate()`, `updateTemplate()`, `deleteTemplate()`
- `loadEntries()`, `setScheduleForDate(date, templateId?)`, `getEntriesInRange()`, `getEntriesFrom()`

### Repository (`data/repository/ShiftRepository.kt`)
- Thin `suspend` wrappers around `JsonStorage` on `Dispatchers.IO`
- `getSchedulesWithTemplates(fromDate)` — joins entries with templates for the Home screen

---

## Alarm System (`alarm/`)

### `AlarmScheduler.kt` — Central alarm logic
The main orchestrator. Key responsibilities:

| Method | Description |
|--------|-------------|
| `rescheduleAllAlarms()` | Cancels all past PendingIntents, re-creates them for the next 14 days. Called on app start and after schedule changes. |
| `getNextAlarm(callback)` | Finds the nearest future alarm across all entries/templates (single-thread executor). Returns `NextAlarm` data class. |
| `setNextAlarmInBackground(onResult)` | Schedules the next alarm via 4 complementary mechanisms (see Background Alarm below). |
| `autoSetSystemAlarmIfNear(onResult?)` | If next alarm is within 23 hours, schedules `SystemAlarmSetReceiver` via AlarmManager (primary) AND enqueues `SystemAlarmSyncWorker` (backup). Optional callback for debug/testing. |
| `fireSystemClockAlarm()` | Static method. 3 strategies: ACTION_SET_ALARM with SKIP_UI → direct HandleSetAlarm component → launch clock app. |
| `hasExactAlarmPermission()` | Checks `canScheduleExactAlarms()` on Android 12+. |
| `openExactAlarmSettings()` | Opens the SCHEDULE_EXACT_ALARM permission page. |
| `enqueueSystemAlarmSyncBatch()` | Batch-queues multiple pending system alarms for deferred sync. |

### Background Alarm — 4 complementary mechanisms

When user presses "Set Background Alarm":

1. **`setExactAndAllowWhileIdle`** (`SCHEDULE_EXACT_ALARM` permission) — primary mechanism, works in Doze mode
2. **`setAlarmClock`** — always exact (no permission needed), shows bell icon in status bar
3. **`ACTION_SET_ALARM` with `EXTRA_SKIP_UI=true`** — silently adds to system Clock app without user confirmation
4. **Deferred `SystemAlarmSyncWorker`** (WorkManager + `setRequiresDeviceIdle(true)`) — backup for OEM ROMs that block background Activity launches (Honor/Huawei/Xiaomi)

### Background System Alarm Sync — 2-tier (AlarmManager + WorkManager)

When `autoSetSystemAlarmIfNear()` detects the next alarm is within 23 hours:

1. **Primary → AlarmManager + `SystemAlarmSetReceiver` (BroadcastReceiver)**
   - Schedules `SystemAlarmSetReceiver` via `setExactAndAllowWhileIdle` (or `setAndAllowWhileIdle` fallback).
   - AlarmManager fires immediately (elevated process priority during `onReceive()`).
   - `onReceive()` calls `fireSystemClockAlarm()` to send `ACTION_SET_ALARM + SKIP_UI`.
   - More reliable than WorkManager because AlarmManager's execution window has fewer restrictions.

2. **Backup → Deferred `SystemAlarmSyncWorker` (WorkManager)**
   - `setRequiresDeviceIdle(true)` — only fires when device is locked/idle.
   - On Chinese ROMs (Honor, Huawei, MIUI), the idle grace period allows background Activity launches.
   - Reads pending alarms from SharedPreferences JSON queue, fires each one.
   - Failed entries are kept for retry on next idle period.

### Broadcast Receivers

| File | Description |
|------|-------------|
| `AlarmReceiver.kt` | Fires when AlarmManager triggers. Creates a high-priority notification with full-screen intent (AlarmActivity). Uses `TYPE_ALARM` ringtone, bypasses DND, vibrates. |
| `SystemAlarmSetReceiver.kt` | Fires when AlarmManager triggers (scheduled by `autoSetSystemAlarmIfNear`). Calls `fireSystemClockAlarm()` to silently add alarm to system Clock app via ACTION_SET_ALARM + SKIP_UI. Uses AlarmManager's execution window for reliable `startActivity()`. |
| `BootReceiver.kt` | `ACTION_BOOT_COMPLETED` — re-schedules all alarms after device reboot. |

### `AlarmActivity.kt`
Full-screen lock-screen alarm UI with:
- `showWhenLocked` + `turnScreenOn` (manifest-declared)
- WakeLock (5 min timeout)
- Max alarm volume
- Dismiss button + "Open App" link

### `SystemAlarmSyncWorker.kt`
Deferred WorkManager worker. When device is idle (locked), reads pending alarm queue from SharedPreferences and fires `ACTION_SET_ALARM` with `SKIP_UI` for each. This exploits the idle grace period on Chinese ROMs.

### `CheckNearAlarmWorker.kt`
Periodic WorkManager worker (every 1 hour). Creates an `AlarmScheduler` and calls `autoSetSystemAlarmIfNear()` — if the next alarm is within 23 hours, it schedules `SystemAlarmSetReceiver` via AlarmManager (primary) and enqueues a deferred `SystemAlarmSyncWorker` as backup, both targeting `ACTION_SET_ALARM + SKIP_UI` on the system Clock app. Scheduled from `ShiftAlarmApp.onCreate()` with `ExistingPeriodicWorkPolicy.UPDATE` (replaces the previous 3-hour / 6-hour schedule on installed devices) and survives device reboot via WorkManager's persistent scheduling.

### `PendingSystemAlarm.kt`
Simple data class: `hour`, `minute`, `message`.

### `SystemAlarmAccessibilityService.kt`
Optional AccessibilityService for auto-confirming the Honor/Huawei Clock app dialog. User must enable it in Settings > Accessibility. Uses `PREF_AUTO_CONFIRM` flag to only click when triggered by our app.

---

## UI Layer

### Navigation (`ui/navigation/AppNavigation.kt`)
- 3-tab bottom navigation: Home, Calendar, Templates
- Uses `mutableIntStateOf` for tab index (Activity-scoped: tab switches preserve ViewModel state)
- Template editing navigates via `MainActivity.editingTemplateId` state

### Home (`ui/screen/home/`)

| File | Key Details |
|------|-------------|
| `HomeScreen.kt` | Shows next alarm card with time/name/location, stats row, resync button, permission warnings. SharedPreferences-backed `backgroundAlarmResult` survives tab switches. Includes a "🧪 Test Hourly Check" debug card that calls `autoSetSystemAlarmIfNear()` with a callback — result auto-dismisses after 5 seconds. |
| `HomeViewModel.kt` | Persists `backgroundAlarmResult` to SharedPreferences (restored on `init`). `setSystemClockForNextAlarm()` calls `scheduler.setNextAlarmInBackground()`. `testHourlyCheck()` calls `scheduler.autoSetSystemAlarmIfNear { result → ... }`. |

### Calendar (`ui/screen/calendar/`)

| File | Key Details |
|------|-------------|
| `CalendarScreen.kt` | Monthly grid (7-column). Day cells show day number + shiftLabel (4 chars) + location (4 chars, faded). `weight(1f)` fills remaining vertical space. |
| `CalendarViewModel.kt` | Loads month range, maintains `scheduleMap`. Assigning a shift also enqueues deferred system alarm sync via `enqueueSystemAlarmSyncBatch()`. |

### Templates (`ui/screen/template/`)
- `TemplateListScreen` — list with drag-reorder, duplicate, delete with schedule-count confirmation
- `TemplateEditScreen` — name, shiftLabel, location, multiple alarm time fields

---

## Theme (`ui/theme/`)

- `Color.kt` — Calendar colors: `CalendarToday` (orange-tinted), `CalendarSelected` (blue-tinted), `CalendarShiftBorder` (grey)
- Shift colors: `ShiftMorning`, `ShiftAfternoon`, `ShiftNight`, `ShiftCustom`

---

## Permissions

| Permission | Purpose |
|-----------|---------|
| `SCHEDULE_EXACT_ALARM` | `setExactAndAllowWhileIdle` (Android 12+) |
| `POST_NOTIFICATIONS` | Alarm notification sound (Android 13+) |
| `RECEIVE_BOOT_COMPLETED` | Re-schedule alarms after reboot |
| `SYSTEM_ALERT_WINDOW` | Overlay for deferred system clock sync (optional) |
| `WAKE_LOCK` | Screen-on during alarm |
| `USE_FULL_SCREEN_INTENT` | Lock-screen alarm activity |

---

## Key Flows

### App Start
```
ShiftAlarmApp.onCreate()
  → initialize storage & repository
  → WorkManager: enqueue CheckNearAlarmWorker (every 1h, UPDATE policy)

MainActivity.onCreate()
  → scheduler.rescheduleAllAlarms()       // re-create PendingIntents for 14 days
  → request POST_NOTIFICATIONS (13+)      // alarm sound
  → request SYSTEM_ALERT_WINDOW (6+)      // overlay for deferred sync
  → setContent { AppNavigation() }        // bottom nav with Home/Calendar/Templates
```

### Alarm Fires
```
AlarmManager triggers PendingIntent
  → AlarmReceiver.onReceive()
    → createNotificationChannel (TYPE_ALARM ringtone, bypass DND)
    → Notification (high priority, fullScreenIntent → AlarmActivity)
    → AlarmActivity shows over lock screen (showWhenLocked + turnScreenOn)
    → User taps dismiss → finish()
```

### Set Background Alarm
```
User presses "Set Background Alarm" on HomeScreen
  → HomeViewModel.setSystemClockForNextAlarm()
    → scheduler.setNextAlarmInBackground() with 4 mechanisms:
      1. setExactAndAllowWhileIdle (SCHEDULE_EXACT_ALARM)
      2. setAlarmClock (always exact, status bar icon)
      3. ACTION_SET_ALARM + SKIP_UI (silent system clock)
      4. enqueueSystemAlarmSync (WorkManager, deferred)
    → persist result to SharedPreferences
```

### Periodic Near-Alarm Check (every 1 hour)
```
WorkManager fires CheckNearAlarmWorker (runs in background, no UI)
  → scheduler.autoSetSystemAlarmIfNear()
    → getNextAlarm() — find nearest future alarm
    → if within 23 hours:
      → PRIMARY: scheduleSystemAlarmViaAlarmManager(hour, minute, label)
        → AlarmManager fires SystemAlarmSetReceiver (setExactAndAllowWhileIdle)
          → onReceive() → fireSystemClockAlarm() → ACTION_SET_ALARM + SKIP_UI
      → BACKUP: enqueueSystemAlarmSync(hour, minute, label)
        → writes pending alarm to SharedPreferences
        → schedules SystemAlarmSyncWorker (device-idle-constrained)
        → when phone is locked, Worker fires ACTION_SET_ALARM + SKIP_UI
    → if > 23 hours or no alarm: no-op
```

### Assign Shift in Calendar
```
User taps date → bottom sheet → selects template
  → CalendarViewModel.setScheduleForSelectedDate()
    → repository.setScheduleForDate(date, templateId)
    → scheduler.rescheduleAllAlarms()
    → scheduler.enqueueSystemAlarmSyncBatch() (for all future alarm times)
    → scheduler.autoSetSystemAlarmIfNear()
```

---

## CI Build (GitHub Actions)

The `.github/workflows/build.yml` workflow compiles the app on GitHub's servers (no local SDK required) and uploads the APKs as a downloadable artifact. It runs on every push to `main`, on pull requests, and manually via **Actions → Build APK → Run workflow**.

Workflow steps: validate the Gradle wrapper → JDK 25 → Android SDK 35 → build
`assembleDebug` + `assembleRelease` + unit tests → upload `app-debug.apk` /
`app-release.apk`.

**Getting the APK:** open the workflow run → *Artifacts* → download `shiftalarm-apk`.
The release APK is signed with the debug key (or your keystore, see below), so it is
installable.

**Optional — sign release builds with your own keystore** (not required):

1. Encode your keystore: `base64 -w0 keystore.jks` and add the output as a repo
   secret `KEYSTORE_BASE64`.
2. Add secrets `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
3. The release APK is then signed with your key. Until then, releases fall back to
   the debug key so CI output stays installable.