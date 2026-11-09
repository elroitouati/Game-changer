package com.gamechanger.shield

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.gamechanger.shield.Kit.Kind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        const val DNS_HOST = "family-filter-dns.cleanbrowsing.org"
    }

    private lateinit var lock: Lock
    private lateinit var stats: Stats
    private lateinit var k: Kit
    private lateinit var root: LinearLayout
    private val handler = Handler(Looper.getMainLooper())

    // UI state
    private var expandedStep: Int? = null
    private var showFullLadder = false
    private var pendingRelease: Updater.Release? = null
    private var updateLine = "בודק אם יש גרסה חדשה…"
    private var downloading = false
    private var checkedUpdate = false
    private var lastLockKey = ""
    private var countdown: TextView? = null
    private var updateProgress: TextView? = null

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lock = Lock(this)
        stats = Stats(this)
        k = Kit(this)
        stats.installedAt
        window.statusBarColor = T.BG
        window.navigationBarColor = T.BG
        root = k.column(T.S5, T.S6).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(T.BG)
            isFillViewport = true
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        render()
        if (!checkedUpdate) checkUpdate(userAsked = false)
        handler.postDelayed(ticker, 1000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    // ------------------------------------------------------------------ state

    private fun shieldOn(): Boolean {
        if (ShieldAccessibilityService.instance != null) return true
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.contains("$packageName/")
    }

    private fun adminOn() = getSystemService(DevicePolicyManager::class.java)
        .isAdminActive(ComponentName(this, ShieldAdminReceiver::class.java))

    private fun dnsOn(): Boolean = runCatching {
        val mode = Settings.Global.getString(contentResolver, "private_dns_mode")
        val host = Settings.Global.getString(contentResolver, "private_dns_specifier")
        mode == "hostname" && host != null && (host.contains("cleanbrowsing") || host.contains("family"))
    }.getOrDefault(false)

    private fun batteryOk() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun lockKey(): String {
        val now = System.currentTimeMillis()
        return "${lock.hasPassword}|${lock.isUnlocked(now)}|${lock.pendingOpensAt(now) > 0}|${shieldOn()}"
    }

    /** Light per-second refresh: only text that changes. Full rebuild only when state flips. */
    private fun tick() {
        val key = lockKey()
        if (key != lastLockKey && currentFocus !is EditText) { render(); return }
        val now = System.currentTimeMillis()
        val pending = lock.pendingOpensAt(now)
        if (pending > 0) countdown?.text = "נפתח בעוד ${mmss(pending - now)}"
    }

    // ------------------------------------------------------------------ screen

    private fun render() {
        lastLockKey = lockKey()
        countdown = null
        updateProgress = null
        root.removeAllViews()

        header()
        stats()
        pendingRelease?.let { updateCard(it) }
        setupCard()
        levelCard()
        if (lock.hasPassword) lockCard()
        limitsCard()
        footer()
    }

    private fun add(v: View, top: Int = 0, bottom: Int = 0, parent: LinearLayout = root) =
        parent.addView(v, k.lp(top = top, bottom = bottom))

    // ---- header: status

    private fun header() {
        val on = shieldOn()
        add(k.label("Game Changer"))
        val row = k.row()
        row.addView(Dot(this, if (on) T.ACCENT else T.WARN), LinearLayout.LayoutParams(k.dp(12), k.dp(12)).apply {
            marginEnd = k.dp(T.S3)
        })
        row.addView(k.text(if (on) "ההגנה פעילה" else "ההגנה כבויה", T.STATUS, T.TEXT_1, k.bold, 0.95f).apply {
            isAccessibilityHeading = true
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        })
        add(row, top = T.S2)
        add(
            k.text(
                if (on) "המסך, החיפושים והאתרים נבדקים בתוך הטלפון. שום דבר לא נשמר ולא נשלח."
                else "המגן לא רואה את המסך כרגע. כדי להפעיל אותו, אשר את הרשאת הנגישות."
            ), top = T.S2
        )
        if (!on) add(k.button("הפעלת ההגנה") { openAccessibility() }, top = T.S4)
    }

    // ---- stats: the hero number

    private fun stats() {
        val days = ((System.currentTimeMillis() - stats.installedAt) / 86_400_000L).toInt()
        val card = k.card()
        card.addView(k.label("ימים עם המגן"))
        card.addView(k.number(days.toString(), T.HERO), k.lp(top = T.S1))
        val row = k.row().apply { isBaselineAligned = false }
        fun cell(label: String, value: Int) = k.column().apply {
            addView(k.label(label))
            addView(k.number(value.toString(), T.TITLE), k.lp(top = T.S1))
        }
        row.addView(cell("נחסם היום", stats.today), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(cell("נחסם בסך הכול", stats.total), LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(divider(), k.lp(h = k.dp(1), top = T.S4, bottom = T.S4))
        card.addView(row)
        card.contentDescription = "$days ימים עם המגן. נחסם היום ${stats.today}, בסך הכול ${stats.total}."
        add(card, top = T.S6)
    }

    private fun divider() = View(this).apply { setBackgroundColor(T.BORDER) }

    // ---- update

    private fun updateCard(rel: Updater.Release) {
        val card = k.card(border = T.ACCENT)
        card.addView(k.label("עדכון זמין"))
        card.addView(k.title("גרסה ${rel.versionCode} מוכנה להתקנה"), k.lp(top = T.S1))
        if (rel.notes.isNotBlank()) card.addView(k.text(rel.notes.take(500)), k.lp(top = T.S2))
        val progress = k.text(if (downloading) "מוריד…" else "", T.LABEL, T.TEXT_2).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = if (downloading) View.VISIBLE else View.GONE
        }
        updateProgress = progress
        card.addView(progress, k.lp(top = T.S2))
        card.addView(k.button("עדכון עכשיו") { startUpdate(rel) }.apply {
            isEnabled = !downloading; alpha = if (downloading) .5f else 1f
        }, k.lp(top = T.S4))
        add(card, top = T.S4)
    }

    private fun startUpdate(rel: Updater.Release) {
        if (!packageManager.canRequestPackageInstalls()) {
            toast("צריך לאשר ל-Game Changer להתקין עדכונים. אחרי האישור, חזור ולחץ שוב.")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        downloading = true
        render()
        Updater.downloadAndInstall(this, rel,
            onProgress = { pct ->
                updateProgress?.apply { visibility = View.VISIBLE; text = "מוריד… $pct%" }
                if (pct >= 100) downloading = false
            },
            onError = { e ->
                downloading = false
                render()
                updateProgress?.apply {
                    visibility = View.VISIBLE
                    setTextColor(T.WARN)
                    text = "ההורדה לא הושלמה. בדוק שיש חיבור לאינטרנט ונסה שוב. ($e)"
                }
            })
    }

    private fun checkUpdate(userAsked: Boolean) {
        checkedUpdate = true
        updateLine = "בודק אם יש גרסה חדשה…"
        Updater.check { rel, err ->
            pendingRelease = rel
            updateLine = when {
                err != null -> "לא הצלחתי לבדוק עדכונים. בדוק את החיבור לאינטרנט."
                rel == null -> "יש לך את הגרסה האחרונה."
                else -> "יש גרסה חדשה."
            }
            if (userAsked && rel == null && err == null) toast("אין גרסה חדשה")
            if (currentFocus !is EditText) render()
        }
    }

    // ---- setup steps

    private data class Step(val title: String, val done: Boolean, val body: () -> View)

    private fun setupCard() {
        val steps = listOf(
            Step("הפעלת המגן", shieldOn()) { stepAccessibility() },
            Step("מניעת מחיקה", adminOn()) { stepAdmin() },
            Step("סינון ברמת הרשת", dnsOn()) { stepDns() },
            Step("פעולה רציפה ברקע", batteryOk()) { stepBattery() },
            Step("נעילה אצל אדם אמון", lock.hasPassword) { stepPassword() },
        )
        val doneCount = steps.count { it.done }
        if (doneCount == steps.size) return

        val firstOpen = steps.indexOfFirst { !it.done }
        val open = expandedStep?.takeIf { !steps[it].done } ?: firstOpen

        val card = k.card()
        card.addView(k.title("השלמת ההגדרה"))
        card.addView(k.text("$doneCount מתוך ${steps.size} שלבים הושלמו", T.LABEL, T.TEXT_3), k.lp(top = T.S1))
        card.addView(Bar(this, doneCount / steps.size.toFloat()), k.lp(h = k.dp(6), top = T.S3, bottom = T.S3))

        steps.forEachIndexed { i, s ->
            val state = when {
                s.done -> Rail.State.DONE
                i == open -> Rail.State.CURRENT
                else -> Rail.State.AHEAD
            }
            val content = k.column().apply { setPadding(0, k.dp(T.S3), 0, k.dp(T.S3)) }
            content.addView(k.text(s.title, T.BODY, if (s.done) T.TEXT_2 else T.TEXT_1, k.medium, 1.0f))
            if (s.done) {
                content.contentDescription = "${s.title}: הושלם"
            } else if (i == open) {
                content.addView(s.body(), k.lp(top = T.S2))
            } else {
                content.contentDescription = "${s.title}: לא הושלם. הקש לפתיחה."
                content.isClickable = true
                content.minimumHeight = k.dp(T.TOUCH)
                content.setOnClickListener { expandedStep = i; render() }
            }
            card.addView(railRow(state, i == 0, i == steps.lastIndex, s.done, content))
        }
        add(card, top = T.S4)
    }

    private fun railRow(state: Rail.State, first: Boolean, last: Boolean, lineDone: Boolean, content: View): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Rail(this, state, first, last, lineDone).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(k.dp(28), -1).apply { marginEnd = k.dp(T.S3) })
        row.addView(content, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun stepBlock(text: String, vararg buttons: View): View = k.column().apply {
        addView(k.text(text))
        buttons.forEachIndexed { i, b -> addView(b, k.lp(top = if (i == 0) T.S3 else T.S2)) }
    }

    private fun stepAccessibility() = stepBlock(
        "בהגדרות, היכנס ל״נגישות״ ← ״אפליקציות מותקנות״ ← Game Changer, והפעל.\n" +
            "אם המתג אפור: פתח את פרטי האפליקציה, לחץ על שלוש הנקודות למעלה ובחר ״אפשר הגדרות מוגבלות״. אחר כך חזור לנגישות.",
        k.button("פתיחת הגדרות נגישות") { openAccessibility() },
        k.button("פתיחת פרטי האפליקציה", Kind.SECONDARY) { openAppDetails() },
    )

    private fun stepAdmin() = stepBlock(
        "כך אי אפשר למחוק את האפליקציה בלי לפתוח קודם את הנעילה.",
        k.button("הפעלת מניעת מחיקה") {
            startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this@MainActivity, ShieldAdminReceiver::class.java))
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "כדי שאי אפשר יהיה למחוק את ההגנה בלי לפתוח קודם את הנעילה.")
            })
        },
    )

    private fun stepDns(): View {
        val host = k.text(DNS_HOST, T.BODY, T.TEXT_1, k.medium).apply {
            textDirection = View.TEXT_DIRECTION_LTR
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setTextIsSelectable(true)
        }
        return k.column().apply {
            addView(k.text("אתרים למבוגרים לא ייטענו בכלל, וחיפוש בטוח יופעל בגוגל וביוטיוב.\nבהגדרות: ״חיבורים״ ← ״הגדרות חיבור נוספות״ ← ״DNS פרטי״ ← ״שם מארח של ספק DNS פרטי״. הדבק את הכתובת:"))
            addView(host, k.lp(top = T.S2))
            addView(k.button("העתקת הכתובת") {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("DNS", DNS_HOST))
                toast("הכתובת הועתקה")
            }, k.lp(top = T.S3))
            addView(k.button("פתיחת הגדרות חיבור", Kind.SECONDARY) {
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            }, k.lp(top = T.S2))
        }
    }

    private fun stepBattery() = stepBlock(
        "יש טלפונים, בעיקר של סמסונג ושיאומי, שמכבים אפליקציות ברקע כדי לחסוך סוללה. האישור הזה משאיר את המגן פעיל.",
        k.button("אישור פעולה ברקע") {
            runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }.onFailure { openAppDetails() }
        },
    )

    private fun stepPassword(): View {
        val col = k.column()
        col.addView(k.text("זה השלב האחרון. מסור עכשיו את הטלפון לאדם שאתה סומך עליו, והוא יבחר סיסמה שרק הוא יודע.\nבלי הסיסמה אי אפשר לכבות, למחוק או להוריד רמה. הדרך היחידה בלעדיה היא לחכות את זמן ההשהיה."))
        val p1 = k.input("סיסמה", password = true)
        val p2 = k.input("הסיסמה שוב", password = true)
        val delay = k.input("60", number = true).apply { setText(lock.delayMinutes.toString()) }
        val err = errorText()
        col.addView(k.label("סיסמה (לפחות 4 תווים)"), k.lp(top = T.S4))
        col.addView(p1, k.lp(top = T.S1))
        col.addView(p2, k.lp(top = T.S2))
        col.addView(k.label("זמן השהיה לפתיחה בלי סיסמה, בדקות (לפחות ${Lock.MIN_DELAY_MIN})"), k.lp(top = T.S4))
        col.addView(delay, k.lp(top = T.S1))
        col.addView(err, k.lp(top = T.S2))
        col.addView(k.button("שמירה ונעילה") {
            val a = p1.text.toString()
            val d = delay.text.toString().toIntOrNull() ?: 0
            val problem = when {
                a.length < 4 -> "הסיסמה צריכה להיות באורך 4 תווים לפחות."
                a != p2.text.toString() -> "הסיסמאות לא זהות. הקלד את אותה סיסמה בשני השדות."
                d < Lock.MIN_DELAY_MIN -> "זמן ההשהיה צריך להיות ${Lock.MIN_DELAY_MIN} דקות לפחות."
                else -> null
            }
            if (problem != null) showError(err, problem) else {
                lock.setPassword(a, d)
                hideKeyboard()
                toast("הנעילה הופעלה")
                render()
            }
        }, k.lp(top = T.S3))
        return col
    }

    // ---- levels

    private fun levelCard() {
        val cur = Levels.current(this)
        val days = Levels.daysAtLevel(this)
        val offer = Levels.shouldOfferUpgrade(this)
        val atTop = cur.number >= Levels.MAX
        val card = k.card(border = if (offer) T.ACCENT else T.BORDER)

        card.addView(k.label("רמה ${cur.number} מתוך ${Levels.MAX}"))
        card.addView(k.title(cur.name), k.lp(top = T.S1))
        card.addView(k.text(cur.description), k.lp(top = T.S2))

        if (!atTop) {
            val shown = days.coerceAtMost(Levels.DAYS_PER_LEVEL)
            card.addView(k.row().apply {
                addView(k.label("ימים ברמה"), LinearLayout.LayoutParams(0, -2, 1f))
                addView(k.text("$shown מתוך ${Levels.DAYS_PER_LEVEL}", T.LABEL, T.TEXT_2, k.medium, 1.0f))
            }, k.lp(top = T.S4))
            card.addView(Bar(this, shown / Levels.DAYS_PER_LEVEL.toFloat()), k.lp(h = k.dp(6), top = T.S2))
        } else {
            card.addView(k.text("זו הרמה הגבוהה ביותר.", T.BODY, T.ACCENT, k.medium), k.lp(top = T.S3))
        }

        // The ladder
        val lastShown = if (showFullLadder) Levels.MAX else (cur.number + 1).coerceAtMost(Levels.MAX)
        val ladder = k.column()
        for (n in 1..lastShown) {
            val l = Levels.ALL[n - 1]
            val state = when {
                n < cur.number -> Rail.State.DONE
                n == cur.number -> Rail.State.CURRENT
                else -> Rail.State.AHEAD
            }
            val content = k.column().apply {
                setPadding(0, k.dp(T.S3), 0, k.dp(T.S2))
                addView(k.text(l.name, T.BODY, if (n == cur.number) T.TEXT_1 else T.TEXT_2,
                    if (n == cur.number) k.bold else k.regular, 1.0f))
                if (n == cur.number + 1) addView(k.text(l.description, T.LABEL, T.TEXT_3, k.regular, 1.0f), k.lp(top = T.S1))
                contentDescription = "רמה $n, ${l.name}: " + when (state) {
                    Rail.State.DONE -> "הושלמה"
                    Rail.State.CURRENT -> "הרמה הנוכחית"
                    Rail.State.AHEAD -> "עוד לא"
                }
            }
            ladder.addView(railRow(state, n == 1, n == lastShown, n < cur.number, content))
        }
        card.addView(ladder, k.lp(top = T.S4))
        if (cur.number + 1 < Levels.MAX) {
            card.addView(k.button(if (showFullLadder) "הסתרת המסלול" else "הצגת כל ${Levels.MAX} הרמות", Kind.QUIET) {
                showFullLadder = !showFullLadder; render()
            })
        }

        if (!atTop) {
            val next = Levels.ALL[cur.number]
            if (offer) {
                card.addView(k.text("עברו $days ימים ברמה הזאת. אפשר לעלות לרמה הבאה: ${next.name}.", T.BODY, T.TEXT_1, k.medium),
                    k.lp(top = T.S3))
                card.addView(k.button("מעבר לרמה ${next.number}") {
                    Levels.set(this, next.number, lock); toast("עברת לרמה ${next.number}: ${next.name}"); render()
                }, k.lp(top = T.S3))
                card.addView(k.button("להזכיר לי בעוד שבוע", Kind.SECONDARY) {
                    Levels.snoozeOffer(this); render()
                }, k.lp(top = T.S2))
            } else {
                card.addView(k.button("מעבר לרמה ${next.number} עכשיו", Kind.SECONDARY) {
                    Levels.set(this, next.number, lock); toast("עברת לרמה ${next.number}: ${next.name}"); render()
                }, k.lp(top = T.S3))
            }
        }
        if (cur.number > 1 && lock.isUnlocked() && lock.hasPassword) {
            card.addView(k.button("ירידה לרמה ${cur.number - 1}", Kind.QUIET) {
                Levels.set(this, cur.number - 1, lock); render()
            }, k.lp(top = T.S1))
        }
        add(card, top = T.S4)
    }

    // ---- lock

    private fun lockCard() {
        val now = System.currentTimeMillis()
        val unlocked = lock.isUnlocked(now)
        val pending = lock.pendingOpensAt(now)
        val card = k.card(border = if (unlocked) T.WARN else T.BORDER)

        card.addView(k.label("נעילה"))
        card.addView(k.title(if (unlocked) "פתוח עד ${hhmm(lock.unlockedUntil(now))}" else "נעול"), k.lp(top = T.S1))

        if (unlocked) {
            card.addView(k.text("עכשיו אפשר לשנות הגדרות, להוריד רמה או לכבות את המגן. אחרי השעה הזו הכל יינעל שוב אוטומטית."), k.lp(top = T.S2))
            card.addView(k.button("נעילה עכשיו") { lock.lockNow(); render() }, k.lp(top = T.S4))

            val np = k.input("סיסמה חדשה (לא חובה)", password = true)
            val nd = k.input(lock.delayMinutes.toString(), number = true).apply { setText(lock.delayMinutes.toString()) }
            val err = errorText()
            card.addView(divider(), k.lp(h = k.dp(1), top = T.S5, bottom = T.S4))
            card.addView(k.label("החלפת סיסמה"))
            card.addView(np, k.lp(top = T.S1))
            card.addView(k.label("זמן השהיה בדקות"), k.lp(top = T.S3))
            card.addView(nd, k.lp(top = T.S1))
            card.addView(err, k.lp(top = T.S2))
            card.addView(k.button("שמירת השינויים", Kind.SECONDARY) {
                val d = nd.text.toString().toIntOrNull() ?: 0
                val pass = np.text.toString()
                when {
                    d < Lock.MIN_DELAY_MIN -> showError(err, "זמן ההשהיה צריך להיות ${Lock.MIN_DELAY_MIN} דקות לפחות.")
                    pass.isNotEmpty() && pass.length < 4 -> showError(err, "הסיסמה צריכה להיות באורך 4 תווים לפחות.")
                    else -> {
                        if (pass.isNotEmpty()) lock.setPassword(pass, d) else lock.changeDelay(d)
                        hideKeyboard(); toast("השינויים נשמרו"); render()
                    }
                }
            }, k.lp(top = T.S2))
        } else {
            card.addView(k.text("כדי לשנות הגדרות, להוריד רמה או לכבות את המגן צריך לפתוח את הנעילה. אפשר לפתוח עם הסיסמה של האדם האמון, או לבקש פתיחה ולחכות ${lock.delayMinutes} דקות."), k.lp(top = T.S2))
            val pw = k.input("הסיסמה של האדם האמון", password = true)
            val err = errorText()
            card.addView(pw, k.lp(top = T.S4))
            card.addView(err, k.lp(top = T.S2))
            card.addView(k.button("פתיחה עם סיסמה", Kind.SECONDARY) {
                if (lock.unlockWithPassword(pw.text.toString())) {
                    hideKeyboard(); toast("הנעילה פתוחה ל-15 דקות"); render()
                } else showError(err, "הסיסמה לא נכונה.")
            }, k.lp(top = T.S2))

            card.addView(divider(), k.lp(h = k.dp(1), top = T.S5, bottom = T.S4))
            if (pending > 0) {
                card.addView(k.label("בקשת פתיחה ממתינה"))
                countdown = k.number("נפתח בעוד ${mmss(pending - now)}", T.TITLE).apply {
                    textDirection = View.TEXT_DIRECTION_RTL
                }
                card.addView(countdown, k.lp(top = T.S1))
                card.addView(k.text("אם הרגע עבר, אפשר לבטל את הבקשה. זה שווה הרבה."), k.lp(top = T.S2))
                card.addView(k.button("ביטול הבקשה") { lock.cancelRequest(); render() }, k.lp(top = T.S3))
            } else {
                card.addView(k.button("בקשת פתיחה בלי סיסמה", Kind.QUIET) {
                    lock.requestDelayedUnlock(); render()
                })
            }
            val nd = k.input(lock.delayMinutes.toString(), number = true).apply { setText(lock.delayMinutes.toString()) }
            val err2 = errorText()
            card.addView(k.label("הארכת זמן ההשהיה (תמיד מותר)"), k.lp(top = T.S4))
            card.addView(nd, k.lp(top = T.S1))
            card.addView(err2, k.lp(top = T.S2))
            card.addView(k.button("הארכת ההשהיה", Kind.SECONDARY) {
                val v = nd.text.toString().toIntOrNull() ?: 0
                if (lock.changeDelay(v)) { hideKeyboard(); toast("זמן ההשהיה עודכן"); render() }
                else showError(err2, "כשהנעילה סגורה אפשר רק להאריך. הזן מספר גדול מ-${lock.delayMinutes}.")
            }, k.lp(top = T.S2))
        }
        add(card, top = T.S4)
    }

    // ---- limits

    private fun limitsCard() {
        val card = k.card(fill = T.BG)
        card.addView(k.title("מה המגן לא יכול לראות"))
        card.addView(k.text(
            "נטפליקס, דיסני+, yes ו-HOT חוסמות צילום מסך, ולכן המגן לא רואה את הווידאו שם. בהן מומלץ להשתמש בפרופיל עם הגבלת גיל.\n" +
                "גלישה בסתר נחסמת, כי אי אפשר לבדוק בה תמונות.\n" +
                "החסימה קורה תוך חלקיק שנייה מהרגע שמשהו מופיע. אף מערכת לא מושלמת, ואם משהו עבר אפשר להעלות רמה."
        ), k.lp(top = T.S2))
        add(card, top = T.S4)
    }

    private fun footer() {
        val col = k.column().apply { setPadding(0, k.dp(T.S4), 0, 0) }
        col.addView(k.text("גרסה ${BuildConfig.VERSION_NAME}. $updateLine", T.LABEL, T.TEXT_3))
        col.addView(k.button("בדיקת עדכונים", Kind.QUIET) { checkUpdate(userAsked = true) })
        add(col, top = T.S2)
    }

    // ------------------------------------------------------------------ helpers

    private fun errorText() = k.text("", T.LABEL, T.WARN, k.medium, 1.4f).apply {
        visibility = View.GONE
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
    }

    private fun showError(v: TextView, msg: String) {
        v.text = msg
        v.visibility = View.VISIBLE
    }

    private fun hideKeyboard() {
        currentFocus?.let {
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0)
            it.clearFocus()
        }
    }

    private fun openAccessibility() = startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    private fun openAppDetails() =
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

    private fun hhmm(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    private fun mmss(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
