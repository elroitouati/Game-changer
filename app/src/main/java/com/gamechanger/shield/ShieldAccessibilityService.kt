package com.gamechanger.shield

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executor
import kotlin.math.abs

/**
 * The "inner camera". Runs all the time while enabled in Accessibility settings:
 *  - reads typed text, searches and URLs  -> blocks explicit searches/sites
 *  - takes a screenshot every ~0.35s while the screen is changing (slower when idle),
 *    runs the on-device nudity model on it -> blocks on detection
 *  - guards Settings so the shield can't be removed while locked
 * Nothing is saved and nothing leaves the phone.
 */
class ShieldAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "Shield"
        @Volatile var instance: ShieldAccessibilityService? = null
            private set

        private const val FAST_MS = 350L      // screen is changing (scroll, video)
        private const val IDLE_MS = 1500L     // screen is still
        private const val ACTIVE_WINDOW_MS = 2500L
        private const val BLOCK_SCREEN_MS = 2500L
        private const val SCAN_WIDTH = 480

        private val SETTINGS_PACKAGES = setOf(
            "com.android.settings", "com.samsung.android.settings", "com.miui.securitycenter",
            "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.samsung.android.packageinstaller", "com.miui.packageinstaller",
            "com.coloros.safecenter", "com.oplus.safecenter", "com.huawei.systemmanager",
            "com.samsung.android.lool", "com.samsung.android.sm.devicesecurity",
            "com.google.android.permissioncontroller", "com.android.permissioncontroller",
        )

        private val BROWSERS = setOf(
            "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.sec.android.app.sbrowser",
            "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.focus",
            "com.opera.browser", "com.opera.mini.native", "com.opera.gx", "com.brave.browser",
            "com.microsoft.emmx", "com.mi.globalbrowser", "com.android.browser",
            "com.duckduckgo.mobile.android", "com.vivaldi.browser", "com.kiwibrowser.browser",
            "com.UCMobile.intl", "com.yandex.browser", "com.heytap.browser",
        )

        private val GUARD_WORDS = listOf(
            "uninstall", "force stop", "clear data", "clear storage", "deactivate", "disable",
            "turn off", "stop", "remove", "use game changer", "accessibility",
            "הסר", "הסרת", "עצירה בכפייה", "עצור בכפייה", "כפה עצירה", "סגירה בכפייה", "נקה",
            "מחק נתונים", "ניקוי", "השבת", "בטל הפעלה", "בטל את ההפעלה", "כבה", "הפסק", "עצור",
            "השתמש ב", "נגישות",
        )
        private const val APP_NAME_KEY = "game changer"
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var scanThread: HandlerThread
    private lateinit var scanHandler: Handler
    private lateinit var textThread: HandlerThread
    private lateinit var textHandler: Handler
    private val scanExecutor = Executor { scanHandler.post(it) }

    private var detector: NudityDetector? = null
    private lateinit var lock: Lock
    private lateinit var stats: Stats
    @Volatile private var level: Levels.Level = Levels.ALL[0]
    private var levelReadAt = 0L

    @Volatile private var blocking = false
    @Volatile private var screenOn = true
    @Volatile private var lastEventAt = 0L
    @Volatile private var currentPackage = ""
    @Volatile private var textPending = false
    private var lastSignature: IntArray? = null
    private var lastFrameChanged = false
    private var overlay: View? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            screenOn = i.action != Intent.ACTION_SCREEN_OFF
            if (screenOn) scheduleScan(200)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        lock = Lock(this)
        stats = Stats(this)
        scanThread = HandlerThread("shield-scan").also { it.start() }
        scanHandler = Handler(scanThread.looper)
        textThread = HandlerThread("shield-text").also { it.start() }
        textHandler = Handler(textThread.looper)

        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })

        scanHandler.post {
            try {
                detector = NudityDetector(this)
                Log.i(TAG, "detector ready")
            } catch (t: Throwable) {
                Log.e(TAG, "detector failed to load", t)
            }
            scheduleScan(500)
        }
    }

    override fun onDestroy() {
        instance = null
        runCatching { unregisterReceiver(screenReceiver) }
        if (::scanHandler.isInitialized) {
            scanHandler.removeCallbacksAndMessages(null)
            scanHandler.post { detector?.close(); detector = null }
            scanThread.quitSafely()
            textThread.quitSafely()
        }
        hideOverlay()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // ---------------------------------------------------------------- events

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        lastEventAt = SystemClock.uptimeMillis()

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            currentPackage = pkg
        }

        refreshLevel()
        if (pkg in SETTINGS_PACKAGES) {
            scheduleTextCheck(pkg, guard = true)
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                // Typed text: check right away, no debounce.
                val typed = event.text?.joinToString(" ") ?: return
                TextFilter.isExplicitInput(typed, level.textLevel)?.let { reason ->
                    val src = event.source
                    main.post { block("typed: $reason", clearField = src) }
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                scheduleTextCheck(pkg, guard = false)
                scheduleScan(60, onlyIfSooner = true) // something changed on screen: look now
            }
        }
    }

    // ---------------------------------------------------------------- text

    private fun scheduleTextCheck(pkg: String, guard: Boolean) {
        if (textPending) return
        textPending = true
        textHandler.postDelayed({
            textPending = false
            try {
                if (guard) guardSettings() else checkScreenText()
            } catch (t: Throwable) {
                Log.w(TAG, "text check failed", t)
            }
        }, if (guard) 150 else 400)
    }

    private fun checkScreenText() {
        if (blocking) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() == packageName) return
        val screen = StringBuilder()
        var hit: String? = null
        var hitNode: AccessibilityNodeInfo? = null
        walk(root, 0) { node ->
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val id = node.viewIdResourceName?.lowercase().orEmpty()
            val isInput = node.isEditable || id.contains("url") || id.contains("omnibox") ||
                id.contains("location_bar") || id.contains("address") || id.contains("search")
            if (isInput && text.isNotEmpty()) {
                TextFilter.isExplicitInput(text, level.textLevel)?.let {
                    hit = it
                    hitNode = if (node.isEditable) node else null
                    return@walk false
                }
            }
            if (text.isNotEmpty()) screen.append(text).append('\n')
            if (desc.isNotEmpty()) screen.append(desc).append('\n')
            true
        }
        if (hit == null) hit = TextFilter.isExplicitOnScreen(screen)
        hit?.let { reason -> main.post { block("text: $reason", clearField = hitNode) } }
    }

    /** Depth-first walk. [visit] returns false to stop. */
    private fun walk(node: AccessibilityNodeInfo, depth: Int, visit: (AccessibilityNodeInfo) -> Boolean): Boolean {
        if (depth > 40) return true
        if (!visit(node)) return false
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            if (!walk(c, depth + 1, visit)) return false
        }
        return true
    }

    // ---------------------------------------------------------------- tamper guard

    private fun guardSettings() {
        if (lock.isUnlocked()) return
        val root = rootInActiveWindow ?: return
        val sb = StringBuilder()
        walk(root, 0) { n ->
            n.text?.let { sb.append(it).append(' ') }
            n.contentDescription?.let { sb.append(it).append(' ') }
            sb.length < 20_000
        }
        val all = sb.toString().lowercase()
        if (!all.contains(APP_NAME_KEY)) return
        if (GUARD_WORDS.none { all.contains(it) }) return
        main.post {
            performGlobalAction(GLOBAL_ACTION_BACK)
            performGlobalAction(GLOBAL_ACTION_HOME)
            Toast.makeText(this, "ההגנה נעולה. כדי לשנות, פתח נעילה בתוך Game Changer.", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------- image scan

    @Volatile private var nextScanAt = 0L
    @Volatile private var shotInFlight = false

    /** Schedules the next screenshot. Never pushes an earlier scheduled scan later. */
    private fun scheduleScan(delay: Long, onlyIfSooner: Boolean = false) {
        if (!::scanHandler.isInitialized) return
        val at = SystemClock.uptimeMillis() + delay
        if (onlyIfSooner && (shotInFlight || (nextScanAt > 0 && nextScanAt <= at))) return
        nextScanAt = at
        scanHandler.removeCallbacks(scanTick)
        scanHandler.postAtTime(scanTick, at)
    }

    private val scanTick = Runnable { takeShot() }

    private fun nextDelay(): Long {
        val recent = SystemClock.uptimeMillis() - lastEventAt < ACTIVE_WINDOW_MS
        return if (recent || lastFrameChanged) FAST_MS else IDLE_MS
    }

    /** Re-reads the level every few seconds so a change in the app applies quickly. */
    private fun refreshLevel() {
        val now = SystemClock.uptimeMillis()
        if (now - levelReadAt > 5000) { levelReadAt = now; level = Levels.current(this) }
    }

    private fun takeShot() {
        refreshLevel()
        nextScanAt = 0
        if (shotInFlight) return
        if (!screenOn || blocking || detector == null) {
            scheduleScan(IDLE_MS); return
        }
        shotInFlight = true
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, scanExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(shot: ScreenshotResult) {
                    shotInFlight = false
                    try {
                        analyze(shot)
                    } catch (t: Throwable) {
                        Log.w(TAG, "analyze failed", t)
                    } finally {
                        scheduleScan(nextDelay())
                    }
                }

                override fun onFailure(errorCode: Int) {
                    shotInFlight = false
                    when (errorCode) {
                        ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> {
                            // Browser incognito tabs hide themselves from screenshots,
                            // so images there can't be checked -> don't allow them.
                            if (currentPackage in BROWSERS) {
                                main.post { block("incognito") }
                            }
                            scheduleScan(IDLE_MS)
                        }
                        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> scheduleScan(FAST_MS)
                        else -> scheduleScan(IDLE_MS)
                    }
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "takeScreenshot threw", t)
            shotInFlight = false
            scheduleScan(IDLE_MS)
        }
    }

    private fun analyze(shot: ScreenshotResult) {
        val hb = shot.hardwareBuffer
        val small: Bitmap
        try {
            val hw = Bitmap.wrapHardwareBuffer(hb, shot.colorSpace) ?: return
            val full = hw.copy(Bitmap.Config.ARGB_8888, false)
            hw.recycle()
            val h = (full.height.toLong() * SCAN_WIDTH / full.width).toInt()
            small = Bitmap.createScaledBitmap(full, SCAN_WIDTH, h, true)
            if (small !== full) full.recycle()
        } finally {
            hb.close()
        }

        // Skip the model when the screen hasn't changed since the last clean frame.
        val sig = signature(small)
        val prev = lastSignature
        lastSignature = sig
        lastFrameChanged = prev == null || diff(prev, sig) > 3
        if (!lastFrameChanged) {
            small.recycle(); return
        }

        val r = detector?.scan(small, level.thresholds)
        small.recycle()
        if (r != null && r.blocked) {
            lastSignature = null
            main.post { block("image: ${r.label} ${"%.2f".format(r.score)}") }
        }
    }

    private fun signature(b: Bitmap): IntArray {
        val cols = 12; val rows = 24
        val out = IntArray(cols * rows)
        for (y in 0 until rows) for (x in 0 until cols) {
            val c = b.getPixel(x * b.width / cols + b.width / cols / 2, y * b.height / rows + b.height / rows / 2)
            out[y * cols + x] = (((c shr 16) and 0xFF) * 3 + ((c shr 8) and 0xFF) * 6 + (c and 0xFF)) / 10
        }
        return out
    }

    private fun diff(a: IntArray, b: IntArray): Int {
        var s = 0
        for (i in a.indices) s += abs(a[i] - b[i])
        return s / a.size
    }

    // ---------------------------------------------------------------- block

    /** Black screen + leave the app. Must run on the main thread. */
    private fun block(reason: String, clearField: AccessibilityNodeInfo? = null) {
        if (blocking) return
        blocking = true
        Log.i(TAG, "BLOCK ($reason) in $currentPackage")
        stats.recordBlock()
        showOverlay()
        clearField?.let {
            runCatching {
                it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                })
            }
        }
        performGlobalAction(GLOBAL_ACTION_BACK)
        performGlobalAction(GLOBAL_ACTION_HOME)
        main.postDelayed({
            hideOverlay()
            blocking = false
            lastSignature = null
            scheduleScan(100) // re-check right away in case it is still there
        }, BLOCK_SCREEN_MS)
    }

    private fun showOverlay() {
        if (overlay != null) return
        val wm = getSystemService(WindowManager::class.java)
        val v = FrameLayout(this).apply {
            setBackgroundColor(0xFF0D1013.toInt())
            addView(TextView(context).apply {
                text = "התוכן נחסם"
                setTextColor(Color.argb(110, 238, 241, 243))
                textSize = 16f
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(-1, -1))
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE,
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        runCatching { wm.addView(v, lp); overlay = v }
            .onFailure { Log.e(TAG, "overlay failed", it) }
    }

    private fun hideOverlay() {
        val v = overlay ?: return
        overlay = null
        runCatching { getSystemService(WindowManager::class.java).removeView(v) }
    }
}
