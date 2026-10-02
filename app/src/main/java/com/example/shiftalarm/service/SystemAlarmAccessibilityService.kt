package com.example.shiftalarm.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.SharedPreferences
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility Service that auto-confirms the alarm dialog in Honor/Huawei Clock app
 * when triggered by [AlarmScheduler].
 *
 * ## How it works
 * 1. [AlarmScheduler] sets `auto_confirm_enabled=true` in SharedPreferences just before firing
 *    the SET_ALARM intent.
 * 2. This service detects the HandleSetAlarm window in `com.hihonor.deskclock`.
 * 3. When the flag is set, it finds and clicks the confirm button (确定/OK/Save).
 * 4. After clicking, it clears the flag so it never clicks outside our app's trigger.
 *
 * ## User setup (one-time)
 * Settings > Accessibility > Installed Apps > Shift Alarm > Toggle ON
 */
class SystemAlarmAccessibilityService : AccessibilityService() {

    private var prefs: SharedPreferences? = null
    private var lastClickTime = 0L
    private val clickCooldownMs = 3000L

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 150
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            packageNames = CLOCK_PACKAGES.toTypedArray()
        }
        Log.d(TAG, "Service connected, monitoring: ${CLOCK_PACKAGES.joinToString()}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Only act when our app has requested auto-confirm
        if (prefs?.getBoolean(PREF_AUTO_CONFIRM, false) != true) return
        if (event.packageName == null) return

        // Debounce: avoid double-clicks
        val now = System.currentTimeMillis()
        if (now - lastClickTime < clickCooldownMs) return

        Log.d(TAG, "Event type=${event.eventType} pkg=${event.packageName} cls=${event.className}")

        tryAutoConfirm()
    }

    /**
     * Searches the active window for a confirm button and clicks it.
     */
    private fun tryAutoConfirm() {
        val root = rootInActiveWindow ?: run {
            Log.d(TAG, "No active window root")
            return
        }

        try {
            if (tryFindAndClick(root)) {
                lastClickTime = System.currentTimeMillis()
                prefs?.edit()?.putBoolean(PREF_AUTO_CONFIRM, false)?.apply()
                Log.d(TAG, "Auto-confirm OK, flag cleared")
            }
        } finally {
            root.recycle()
        }
    }

    /**
     * Tries multiple strategies to find and click the confirm button.
     */
    private fun tryFindAndClick(root: AccessibilityNodeInfo): Boolean {
        // Strategy 1: by known button text labels
        val searchTexts = arrayOf(
            "确定", "確定",       // Chinese (simplified / traditional)
            "OK", "ok", "Ok",    // English
            "Done", "done",
            "Save", "save", "保存",
            "Confirm", "confirm",
            "添加", "新增"        // Add / Create (some variants)
        )
        for (text in searchTexts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                if (tryClickNode(node)) return true
            }
        }

        // Strategy 2: by view ID (positive button pattern)
        val viewIds = arrayOf(
            "android:id/button1",                // Standard Android positive button
            "com.hihonor.deskclock:id/button1",
            "com.hihonor.deskclock:id/positive_button",
            "com.hihonor.deskclock:id/confirm",
            "com.hihonor.deskclock:id/done",
            "com.hihonor.deskclock:id/save",
            "com.android.deskclock:id/button1",
            "com.android.deskclock:id/positive_button",
            "com.huawei.deskclock:id/button1"
        )
        for (viewId in viewIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
            for (node in nodes) {
                if (tryClickNode(node)) return true
            }
        }

        Log.d(TAG, "No confirm button found in active window")
        return false
    }

    /**
     * Attempts to click an AccessibilityNodeInfo or its nearest clickable ancestor.
     */
    private fun tryClickNode(node: AccessibilityNodeInfo): Boolean {
        // Try the node itself first
        if (node.isClickable && node.isEnabled) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "Clicked: text='${node.text}' id='${node.viewIdResourceName}'")
            return true
        }
        // Walk up the tree to find a clickable parent (buttons are often wrapped)
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable && parent.isEnabled) {
                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Clicked parent: id='${parent.viewIdResourceName}'")
                return true
            }
            parent = parent.parent
        }
        return false
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    companion object {
        private const val TAG = "AlarmA11ySvc"
        private const val PREF_NAME = "alarm_accessibility"
        const val PREF_AUTO_CONFIRM = "auto_confirm_enabled"

        private val CLOCK_PACKAGES = listOf(
            "com.hihonor.deskclock",
            "com.android.deskclock",
            "com.huawei.deskclock"
        )
    }
}