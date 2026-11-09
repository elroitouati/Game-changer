package com.gamechanger.shield

import android.content.Context

/**
 * Effort levels ("השתדלות"). Each level filters more than the one before.
 * Every 30 days at a level the app offers to go one level up.
 * Going up is always allowed; going down only while the lock is open.
 */
object Levels {

    const val MAX = 5
    const val DAYS_PER_LEVEL = 30
    private const val PREFS = "shield_level"

    data class Level(
        val number: Int,
        val name: String,
        val description: String,
        /** Per NudeNet class threshold; values above 1 mean "not blocked". */
        val thresholds: FloatArray,
        /** Text filter strictness (1 = nudity/porn words, 2 = also underwear/swimwear words). */
        val textLevel: Int,
    )

    private const val OFF = 2f

    // Class indexes (see NudityDetector.LABELS)
    private const val F_GEN_COV = 0
    private const val BUTT_EXP = 2
    private const val F_BREAST_EXP = 3
    private const val F_GEN_EXP = 4
    private const val ANUS_EXP = 6
    private const val BELLY_EXP = 13
    private const val M_GEN_EXP = 14
    private const val F_BREAST_COV = 16
    private const val BUTT_COV = 17

    private fun t(vararg pairs: Pair<Int, Float>) = FloatArray(18) { OFF }.also { a ->
        pairs.forEach { (cls, v) -> a[cls] = v }
    }

    private val nudity = arrayOf(BUTT_EXP, F_BREAST_EXP, F_GEN_EXP, ANUS_EXP, M_GEN_EXP)

    val ALL = listOf(
        Level(
            1, "רמה 1: בסיס",
            "חוסם כל עירום (עליון ותחתון), פורנו, חיפושים ואתרים.",
            t(*nudity.map { it to 0.45f }.toTypedArray()), 1,
        ),
        Level(
            2, "רמה 2: רגיש יותר",
            "המודל רגיש יותר ותופס עירום גם כשהוא קטן או חלקי.",
            t(*nudity.map { it to 0.35f }.toTypedArray()), 1,
        ),
        Level(
            3, "רמה 3: גם בגדי ים והלבשה תחתונה",
            "חוסם גם תמונות בבגד ים או בהלבשה תחתונה, וגם חיפושים כאלה.",
            t(*nudity.map { it to 0.35f }.toTypedArray(),
                F_BREAST_COV to 0.50f, BUTT_COV to 0.50f, F_GEN_COV to 0.50f), 2,
        ),
        Level(
            4, "רמה 4: לבוש חושפני",
            "גם בטן חשופה ולבוש חושפני מאוד.",
            t(*nudity.map { it to 0.32f }.toTypedArray(),
                F_BREAST_COV to 0.42f, BUTT_COV to 0.42f, F_GEN_COV to 0.42f, BELLY_EXP to 0.50f), 2,
        ),
        Level(
            5, "רמה 5: מקסימום",
            "הרגישות הגבוהה ביותר בכל הקטגוריות.",
            t(*nudity.map { it to 0.28f }.toTypedArray(),
                F_BREAST_COV to 0.35f, BUTT_COV to 0.35f, F_GEN_COV to 0.35f, BELLY_EXP to 0.40f), 2,
        ),
    )

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun current(c: Context): Level = ALL[(prefs(c).getInt("level", 1) - 1).coerceIn(0, MAX - 1)]

    fun levelSince(c: Context): Long = prefs(c).getLong("since", 0).let {
        if (it == 0L) System.currentTimeMillis().also { now -> prefs(c).edit().putLong("since", now).apply() } else it
    }

    fun daysAtLevel(c: Context): Int = ((System.currentTimeMillis() - levelSince(c)) / 86_400_000L).toInt()

    /** True when 30 days passed at this level and there's a higher one. */
    fun shouldOfferUpgrade(c: Context): Boolean {
        val cur = current(c).number
        if (cur >= MAX) return false
        if (daysAtLevel(c) < DAYS_PER_LEVEL) return false
        val snoozed = prefs(c).getLong("snoozeUntil", 0)
        return System.currentTimeMillis() >= snoozed
    }

    fun snoozeOffer(c: Context, days: Int = 7) =
        prefs(c).edit().putLong("snoozeUntil", System.currentTimeMillis() + days * 86_400_000L).apply()

    /** Going down needs the lock open. Returns false if not allowed. */
    fun set(c: Context, level: Int, lock: Lock): Boolean {
        val target = level.coerceIn(1, MAX)
        if (target < current(c).number && !lock.isUnlocked()) return false
        prefs(c).edit().putInt("level", target).putLong("since", System.currentTimeMillis())
            .remove("snoozeUntil").apply()
        return true
    }
}
