package com.gamechanger.shield

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Calendar

/**
 * Protection lock.
 *
 * Settings, uninstalling and turning the shield off are only allowed while "unlocked".
 * Two ways to unlock:
 *  1. The trusted person types the password -> unlocked right away for [UNLOCK_WINDOW_MS].
 *  2. Ask for a delayed unlock -> after the chosen delay (min 30 minutes) it unlocks
 *     for [UNLOCK_WINDOW_MS]. The weak moment usually passes before that.
 */
class Lock(context: Context) {

    companion object {
        const val MIN_DELAY_MIN = 30
        const val UNLOCK_WINDOW_MS = 15 * 60 * 1000L
        private const val PREFS = "shield_lock"
    }

    private val p: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val hasPassword: Boolean get() = p.contains("hash")

    var delayMinutes: Int
        get() = p.getInt("delay", 60).coerceAtLeast(MIN_DELAY_MIN)
        private set(v) = p.edit().putInt("delay", v.coerceAtLeast(MIN_DELAY_MIN)).apply()

    /** Before a password exists, everything is open so setup can be finished. */
    fun isUnlocked(now: Long = System.currentTimeMillis()): Boolean {
        if (!hasPassword) return true
        return now < unlockedUntil(now)
    }

    fun unlockedUntil(now: Long = System.currentTimeMillis()): Long {
        val manual = p.getLong("unlockedUntil", 0)
        val req = p.getLong("requestedAt", 0)
        if (req > 0) {
            val opensAt = req + delayMinutes * 60_000L
            if (now >= opensAt) return maxOf(manual, opensAt + UNLOCK_WINDOW_MS)
        }
        return manual
    }

    /** Time when a pending delayed unlock opens, or 0 if none pending. */
    fun pendingOpensAt(now: Long = System.currentTimeMillis()): Long {
        val req = p.getLong("requestedAt", 0)
        if (req == 0L) return 0
        val opensAt = req + delayMinutes * 60_000L
        if (now >= opensAt + UNLOCK_WINDOW_MS) {
            p.edit().remove("requestedAt").apply() // window expired
            return 0
        }
        return if (now < opensAt) opensAt else 0
    }

    fun setPassword(password: String, delayMin: Int) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        p.edit()
            .putString("salt", salt.toHex())
            .putString("hash", hash(password, salt))
            .putInt("delay", delayMin.coerceAtLeast(MIN_DELAY_MIN))
            .remove("requestedAt")
            .remove("unlockedUntil")
            .apply()
    }

    fun checkPassword(password: String): Boolean {
        val salt = p.getString("salt", null)?.fromHex() ?: return false
        return hash(password, salt) == p.getString("hash", null)
    }

    fun unlockWithPassword(password: String): Boolean {
        if (!checkPassword(password)) return false
        p.edit().putLong("unlockedUntil", System.currentTimeMillis() + UNLOCK_WINDOW_MS)
            .remove("requestedAt").apply()
        return true
    }

    fun requestDelayedUnlock() {
        if (p.getLong("requestedAt", 0) == 0L) {
            p.edit().putLong("requestedAt", System.currentTimeMillis()).apply()
        }
    }

    fun cancelRequest() = p.edit().remove("requestedAt").apply()

    fun lockNow() = p.edit().remove("unlockedUntil").remove("requestedAt").apply()

    /** Making the delay longer is always allowed. Shorter only while unlocked. */
    fun changeDelay(newMin: Int): Boolean {
        if (newMin < MIN_DELAY_MIN) return false
        if (newMin < delayMinutes && !isUnlocked()) return false
        delayMinutes = newMin
        return true
    }

    private fun hash(password: String, salt: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        var h = salt + password.toByteArray()
        repeat(10_000) { h = md.digest(h) }
        return h.toHex()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun String.fromHex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

/** Simple counters shown on the main screen. */
class Stats(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("shield_stats", Context.MODE_PRIVATE)

    val total: Int get() = p.getInt("total", 0)
    val today: Int get() = if (p.getInt("day", -1) == dayKey()) p.getInt("today", 0) else 0
    val installedAt: Long
        get() = p.getLong("installedAt", 0).let {
            if (it == 0L) System.currentTimeMillis().also { now -> p.edit().putLong("installedAt", now).apply() } else it
        }

    fun recordBlock() {
        val d = dayKey()
        val todayCount = if (p.getInt("day", -1) == d) p.getInt("today", 0) else 0
        p.edit().putInt("total", total + 1).putInt("day", d).putInt("today", todayCount + 1).apply()
    }

    private fun dayKey(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }
}
