package com.eyecare.app

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

enum class EyeState { COMFORT, TIRED, EXHAUSTED }

/** Short-video / endless-scroll apps we watch. */
object Apps {
    val SHORT = setOf(
        "com.instagram.android", "com.instagram.lite",
        "com.google.android.youtube",
        "com.facebook.katana", "com.facebook.lite",
        "com.snapchat.android",
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
        "in.mohalla.sharechat"
    )
}

/** Live (in-memory) values shared between the services and the UI (same process). */
object Live {
    @Volatile var sessionSec = 0L        // continuous phone use (screen on), resets after 2 min screen-off
    @Volatile var streakSec = 0L         // continuous reels/shorts scrolling
    @Volatile var fgPkg: String? = null  // current foreground app
    @Volatile var lastScrollAt = 0L      // last scroll seen in a short-video app
    @Volatile var a11yOn = false         // accessibility service connected
}

/** Small persistent storage. */
object Store {
    private const val DAY = 24 * 3600_000L
    private fun p(c: Context) = c.getSharedPreferences("eyecare", Context.MODE_PRIVATE)

    fun voiceOn(c: Context) = p(c).getBoolean("voice", true)
    fun setVoice(c: Context, v: Boolean) = p(c).edit().putBoolean("voice", v).apply()

    private fun reelList(c: Context, cutoff: Long): List<Long> =
        (p(c).getString("reels", "") ?: "").split(",").mapNotNull { it.toLongOrNull() }.filter { it >= cutoff }

    @Synchronized
    fun addReel(c: Context, ts: Long) {
        val list = reelList(c, ts - DAY).toMutableList()
        list.add(ts)
        p(c).edit().putString("reels", list.takeLast(5000).joinToString(",")).apply()
    }

    /** Reels/shorts seen in the last 24 hours (rolling). */
    @Synchronized
    fun reels24h(c: Context): Int = reelList(c, System.currentTimeMillis() - DAY).size

    private fun dayKey() = "sv_" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    @Synchronized
    fun addShortSec(c: Context, n: Int) {
        val k = dayKey()
        p(c).edit().putLong(k, p(c).getLong(k, 0) + n).apply()
    }
    fun shortSecToday(c: Context): Long = p(c).getLong(dayKey(), 0)
}

object Usage {
    fun hasAccess(c: Context): Boolean {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        else
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun startOfDay(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** package -> foreground milliseconds today (own app excluded). */
    fun todayStats(c: Context): Map<String, Long> {
        val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        return usm.queryAndAggregateUsageStats(startOfDay(), now)
            .mapValues { it.value.totalTimeInForeground }
            .filter { it.value > 0 && it.key != c.packageName }
    }

    /** Latest app moved to foreground in the last minute, otherwise [prev]. */
    @Suppress("DEPRECATION")
    fun foregroundPkg(c: Context, prev: String?): String? {
        val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 60_000, now)
        val e = UsageEvents.Event()
        var cur = prev
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) cur = e.packageName
        }
        return cur
    }
}

object Fatigue {
    /**
     * App's own estimate (0-100) – NOT a medical measurement.
     * 40% continuous phone time (60 min = full), 30% continuous reels/shorts (30 min = full),
     * 20% total screen time today (6 h = full), +10 late-night use (11pm-5am).
     */
    fun score(sessionSec: Long, streakSec: Long, dayScreenSec: Long): Int {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val night = if (h >= 23 || h < 5) 10.0 else 0.0
        val s = 40 * min(sessionSec / 3600.0, 1.0) +
                30 * min(streakSec / 1800.0, 1.0) +
                20 * min(dayScreenSec / 21600.0, 1.0) + night
        return min(100, s.roundToInt())
    }

    fun state(score: Int, streakSec: Long): EyeState = when {
        score >= 65 || streakSec >= 1500 -> EyeState.EXHAUSTED
        score >= 35 || streakSec >= 600 -> EyeState.TIRED
        else -> EyeState.COMFORT
    }

    fun fmt(sec: Long): String =
        if (sec >= 3600) "${sec / 3600} घं ${(sec % 3600) / 60} मि" else "${sec / 60} मि"
}
