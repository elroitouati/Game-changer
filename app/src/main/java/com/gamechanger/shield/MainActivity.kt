package com.gamechanger.shield

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        const val DNS_HOST = "family-filter-dns.cleanbrowsing.org"
        private const val BG = 0xFF0E1116.toInt()
        private const val CARD = 0xFF181C24.toInt()
        private const val TEXT = 0xFFE8EAED.toInt()
        private const val MUTED = 0xFF9AA0A6.toInt()
        private const val GOOD = 0xFF34C759.toInt()
        private const val BAD = 0xFFFF453A.toInt()
        private const val ACCENT = 0xFF4C8DFF.toInt()
    }

    private lateinit var lock: Lock
    private lateinit var stats: Stats
    private lateinit var root: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var pendingRelease: Updater.Release? = null
    private var updateStatus: String? = null
    private var checkedUpdate = false

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lock = Lock(this)
        stats = Stats(this)
        stats.installedAt
        window.statusBarColor = BG
        window.navigationBarColor = BG
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(24), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        render()
        if (!checkedUpdate) checkUpdate(silent = true)
        handler.postDelayed(ticker, 1000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    // ------------------------------------------------------------------ state

    private fun shieldOn(): Boolean {
        if (ShieldAccessibilityService.instance != null) return true
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.contains("$packageName/")
    }

    private fun adminOn(): Boolean {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        return dpm.isAdminActive(ComponentName(this, ShieldAdminReceiver::class.java))
    }

    private fun dnsOn(): Boolean = runCatching {
        val mode = Settings.Global.getString(contentResolver, "private_dns_mode")
        val host = Settings.Global.getString(contentResolver, "private_dns_specifier")
        mode == "hostname" && host != null && (host.contains("cleanbrowsing") || host.contains("family"))
    }.getOrDefault(false)

    private fun batteryOk(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    // ------------------------------------------------------------------ UI

    /** Rebuilds the screen. Skipped while the user is typing so input isn't lost. */
    private fun render() {
        val focused = currentFocus
        if (focused is EditText) return
        root.removeAllViews()

        val on = shieldOn()
        title("Game Changer")
        root.addView(TextView(this).apply {
            text = if (on) "🛡️  ההגנה פעילה" else "⚠️  ההגנה כבויה"
            setTextColor(if (on) GOOD else BAD)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(12))
        })

        updateCard()
        levelCard()

        if (!lock.hasPassword) {
            passwordSetupCard()
        }

        // ---- setup checklist
        card {
            heading("הגדרה")
            step(
                on, "1. הפעלת המגן (נגישות)",
                "בהגדרות > נגישות > אפליקציות מותקנות > Game Changer > הפעל.\n" +
                    "אם האפשרות אפורה: הגדרות > אפליקציות > Game Changer > ⋮ (למעלה) > \"אפשר הגדרות מוגבלות\", ואז שוב.",
                "פתח נגישות", { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                "הגדרות מוגבלות", { openAppDetails() },
            )
            step(
                adminOn(), "2. מניעת מחיקה",
                "מונע מחיקה של האפליקציה ברגע חלש.",
                "הפעל", {
                    startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this@MainActivity, ShieldAdminReceiver::class.java))
                        putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "כדי שלא יהיה אפשר למחוק את ההגנה ברגע חלש.")
                    })
                },
            )
            step(
                dnsOn(), "3. סינון אתרים ברמת הרשת",
                "הגדרות > חיבורים > הגדרות חיבור נוספות > DNS פרטי > שם מארח:\n$DNS_HOST\n" +
                    "חוסם אתרי פורנו לפני שהם בכלל נטענים, ומכריח חיפוש בטוח בגוגל וביוטיוב.",
                "העתק כתובת", {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("dns", DNS_HOST))
                    toast("הועתק")
                },
                "פתח הגדרות", { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) },
            )
            step(
                batteryOk(), "4. לא להירדם ברקע",
                "כדי שהטלפון לא יכבה את המגן כדי לחסוך סוללה (חשוב בשיאומי ובסמסונג).",
                "אשר", {
                    runCatching {
                        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                    }.onFailure { openAppDetails() }
                },
            )
        }

        if (lock.hasPassword) lockCard()

        card {
            heading("נתונים")
            body("נחסם היום: ${stats.today}    •    סה\"כ: ${stats.total}")
            val days = ((System.currentTimeMillis() - stats.installedAt) / 86_400_000L).toInt()
            body("ימים עם המגן: $days")
        }

        card {
            heading("חשוב לדעת")
            body(
                "• הכל קורה בתוך הטלפון. שום תמונה לא נשמרת ושום דבר לא נשלח.\n" +
                    "• נטפליקס, דיסני+, yes, HOT ואפליקציות דומות חוסמות טכנית צילום מסך, ולכן המגן לא רואה מה קורה בתוך הסרטון. " +
                    "בהן כדאי להפעיל פרופיל עם הגבלת גיל.\n" +
                    "• גלישה בסתר (אינקוגניטו) נחסמת, כי אי אפשר לבדוק בה תמונות.\n" +
                    "• הזיהוי מהיר (חלקיק שנייה), אבל אף מערכת לא מושלמת. אם משהו עבר, ספר לי ונשפר."
            )
        }

        root.addView(TextView(this).apply {
            text = "גרסה ${BuildConfig.VERSION_NAME}"
            setTextColor(MUTED)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun levelCard() {
        val cur = Levels.current(this)
        val days = Levels.daysAtLevel(this)
        val offer = Levels.shouldOfferUpgrade(this)
        card(if (offer) GOOD else null) {
            heading("📈 ${cur.name}")
            body(cur.description)
            if (cur.number < Levels.MAX) {
                val left = (Levels.DAYS_PER_LEVEL - days).coerceAtLeast(0)
                body(if (offer) "עברו $days ימים ברמה הזאת. כל הכבוד! 🎉" else "יום $days ברמה הזאת. עוד $left ימים עד ההצעה לעלות רמה.")
            } else {
                body("אתה ברמה הגבוהה ביותר. כל הכבוד! 👑")
            }
            if (cur.number < Levels.MAX) {
                val next = Levels.ALL[cur.number]
                if (offer) {
                    body("מוכן לעלות? ${next.name}\n${next.description}")
                    button("עולה רמה 💪", GOOD) { Levels.set(this, next.number, lock); toast("עלית ל${next.name}"); render() }
                    button("עוד שבוע", CARD) { Levels.snoozeOffer(this); render() }
                } else {
                    button("לעלות עכשיו ל${next.name}", CARD) {
                        Levels.set(this, next.number, lock); toast("עלית רמה 💪"); render()
                    }
                }
            }
            if (cur.number > 1 && lock.isUnlocked()) {
                button("ירידת רמה (רק כשהנעילה פתוחה)", CARD) {
                    Levels.set(this, cur.number - 1, lock); render()
                }
            }
        }
    }

    private fun updateCard() {
        val rel = pendingRelease
        if (rel == null) {
            card {
                heading("עדכונים")
                body(updateStatus ?: "בודק...")
                button("בדוק עדכונים", ACCENT) { checkUpdate(silent = false) }
            }
            return
        }
        card(ACCENT) {
            heading("✨ יש גרסה חדשה: ${rel.name}")
            if (rel.notes.isNotBlank()) body(rel.notes.take(600))
            button("עדכן עכשיו", GOOD) {
                if (!packageManager.canRequestPackageInstalls()) {
                    toast("צריך לאשר ל-Game Changer להתקין עדכונים")
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    return@button
                }
                updateStatus = "מוריד..."
                Updater.downloadAndInstall(this@MainActivity, rel,
                    onProgress = { pct -> updateStatus = "מוריד... $pct%" },
                    onError = { e -> toast("שגיאה: $e") })
                toast("מוריד את העדכון...")
            }
        }
    }

    private fun checkUpdate(silent: Boolean) {
        checkedUpdate = true
        updateStatus = "בודק..."
        Updater.check { rel, err ->
            pendingRelease = rel
            updateStatus = when {
                err != null -> "לא הצלחתי לבדוק עדכונים ($err)"
                rel == null -> "יש לך את הגרסה האחרונה ✓"
                else -> null
            }
            if (!silent && rel == null && err == null) toast("אין עדכון חדש")
            render()
        }
    }

    private fun passwordSetupCard() = card(ACCENT) {
        heading("🔐 הגדרת נעילה")
        body("תן עכשיו את הטלפון לאדם האמון. הוא בוחר סיסמה שרק הוא יודע.\nבלי הסיסמה אי אפשר לכבות, למחוק או לשנות הגדרות. האפשרות היחידה תהיה לחכות את זמן ההשהיה.")
        val p1 = input("סיסמה (לאדם האמון)", password = true)
        val p2 = input("סיסמה שוב", password = true)
        body("זמן השהיה לפתיחה בלי סיסמה (דקות, מינימום ${Lock.MIN_DELAY_MIN}):")
        val d = input("60", number = true)
        button("שמור ונעל", GOOD) {
            val a = p1.text.toString()
            val delay = d.text.toString().toIntOrNull() ?: 60
            when {
                a.length < 4 -> toast("סיסמה של 4 תווים לפחות")
                a != p2.text.toString() -> toast("הסיסמאות לא זהות")
                delay < Lock.MIN_DELAY_MIN -> toast("מינימום ${Lock.MIN_DELAY_MIN} דקות")
                else -> {
                    lock.setPassword(a, delay)
                    clearFocusAndRender()
                    toast("ננעל 🔒")
                }
            }
        }
    }

    private fun lockCard() = card {
        val now = System.currentTimeMillis()
        val unlocked = lock.isUnlocked(now)
        val pending = lock.pendingOpensAt(now)
        heading(if (unlocked) "🔓 פתוח זמנית" else "🔒 נעול")

        if (unlocked) {
            body("אפשר לשנות הגדרות או לכבות עד ${time(lock.unlockedUntil(now))}.")
            button("נעל עכשיו", ACCENT) { lock.lockNow(); render() }
            body("החלפת סיסמה (לאדם האמון):")
            val np = input("סיסמה חדשה", password = true)
            body("זמן השהיה (דקות):")
            val nd = input(lock.delayMinutes.toString(), number = true)
            button("שמור", GOOD) {
                val delay = nd.text.toString().toIntOrNull() ?: lock.delayMinutes
                val pass = np.text.toString()
                if (delay < Lock.MIN_DELAY_MIN) { toast("מינימום ${Lock.MIN_DELAY_MIN} דקות"); return@button }
                if (pass.isNotEmpty()) {
                    if (pass.length < 4) { toast("סיסמה של 4 תווים לפחות"); return@button }
                    lock.setPassword(pass, delay)
                } else {
                    lock.changeDelay(delay)
                }
                clearFocusAndRender()
                toast("נשמר")
            }
            return@card
        }

        val pw = input("סיסמת האדם האמון", password = true)
        button("פתח עם סיסמה", ACCENT) {
            if (lock.unlockWithPassword(pw.text.toString())) {
                clearFocusAndRender(); toast("נפתח ל-15 דקות")
            } else toast("סיסמה שגויה")
        }
        if (pending > 0) {
            val left = (pending - now) / 1000
            body("⏳ פתיחה מושהית תיפתח בעוד ${left / 60}:${"%02d".format(left % 60)}")
            button("בטל בקשה (כל הכבוד 💪)", GOOD) { lock.cancelRequest(); render() }
        } else {
            button("בקש פתיחה בלי סיסמה (${lock.delayMinutes} דק' המתנה)", CARD) {
                lock.requestDelayedUnlock(); render()
            }
        }
        body("הגדלת זמן ההשהיה (תמיד מותר):")
        val nd = input(lock.delayMinutes.toString(), number = true)
        button("עדכן השהיה", CARD) {
            val v = nd.text.toString().toIntOrNull() ?: 0
            if (lock.changeDelay(v)) { clearFocusAndRender(); toast("עודכן") }
            else toast("אפשר רק להגדיל כשנעול")
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun clearFocusAndRender() {
        currentFocus?.clearFocus()
        root.requestFocus()
        root.removeAllViews()
        render()
    }

    private fun openAppDetails() =
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

    private fun time(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private var current: LinearLayout? = null

    private fun title(s: String) = root.addView(TextView(this).apply {
        text = s
        setTextColor(TEXT)
        textSize = 30f
        typeface = Typeface.DEFAULT_BOLD
    })

    private fun card(border: Int? = null, content: () -> Unit) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(CARD)
                cornerRadius = dp(16).toFloat()
                if (border != null) setStroke(dp(2), border)
            }
        }
        root.addView(c, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        val prev = current
        current = c
        content()
        current = prev
    }

    private fun target() = current ?: root

    private fun heading(s: String) = target().addView(TextView(this).apply {
        text = s
        setTextColor(TEXT)
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 0, 0, dp(6))
    })

    private fun body(s: String) = target().addView(TextView(this).apply {
        text = s
        setTextColor(MUTED)
        textSize = 14f
        setLineSpacing(0f, 1.15f)
        setPadding(0, dp(2), 0, dp(6))
    })

    private fun input(hint: String, password: Boolean = false, number: Boolean = false): EditText {
        val e = EditText(this).apply {
            this.hint = hint
            setHintTextColor(0xFF5F6368.toInt())
            setTextColor(TEXT)
            textDirection = View.TEXT_DIRECTION_RTL
            inputType = when {
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                number -> InputType.TYPE_CLASS_NUMBER
                else -> InputType.TYPE_CLASS_TEXT
            }
        }
        target().addView(e, LinearLayout.LayoutParams(-1, -2))
        return e
    }

    private fun button(s: String, color: Int, onClick: () -> Unit) {
        val b = Button(this).apply {
            text = s
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), 0xFF2A303B.toInt())
            }
            setOnClickListener { onClick() }
        }
        target().addView(b, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) })
    }

    private fun step(
        done: Boolean, label: String, help: String,
        action: String, onAction: () -> Unit,
        action2: String? = null, onAction2: (() -> Unit)? = null,
    ) {
        target().addView(TextView(this).apply {
            text = (if (done) "✅  " else "⬜  ") + label
            setTextColor(if (done) GOOD else TEXT)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(10), 0, dp(2))
        })
        if (!done) {
            body(help)
            button(action, ACCENT, onAction)
            if (action2 != null && onAction2 != null) button(action2, CARD, onAction2)
        }
    }
}
