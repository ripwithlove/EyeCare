package com.eyecare.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import java.util.Locale

/**
 * Runs in the background (foreground service) and every second:
 *  - tracks continuous phone use and continuous reels/shorts scrolling
 *  - shows the camera-side eye pill while you are inside a short-video app
 *  - fires banner popup + voice at the right moments (20-20-20 rule and own reel limits)
 */
class EyeMonitorService : Service(), TextToSpeech.OnInitListener {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var overlays: Overlays
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var tickN = 0
    private var offSec = 0
    private var notShortSec = 0
    private var pendingShort = 0
    private var lastSessionBucket = 0L
    private var lastStreakLevel = 0
    private var dayScreenSec = 0L
    private var reelCount = 0

    private val ticker = object : Runnable {
        override fun run() {
            try { tick() } catch (_: Exception) {}
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        val n = NotificationCompat.Builder(this, "monitor")
            .setSmallIcon(R.drawable.ic_eye)
            .setContentTitle("आई केयर चालू है")
            .setContentText("आपके स्क्रीन टाइम पर नज़र रखी जा रही है")
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        overlays = Overlays(this)
        tts = TextToSpeech(this, this)
        handler.post(ticker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "TEST") {
            alert(
                "आपकी आँखें थक रही हैं 👀",
                "(टेस्ट) आँखों को आराम दें: 20 सेकंड के लिए दूर देखें।",
                "आपकी आँखें थक रही हैं। आँखों को आराम दें, बीस सेकंड के लिए दूर देखें।",
                EyeState.TIRED
            )
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        overlays.hideAll()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val r = tts?.setLanguage(Locale("hi", "IN"))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale.getDefault()
            }
            ttsReady = true
        }
    }

    private fun tick() {
        tickN++
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) {
            offSec++
            if (offSec >= 120) {                       // 2 min away from screen = a real break
                Live.sessionSec = 0; Live.streakSec = 0
                lastSessionBucket = 0; lastStreakLevel = 0
            }
            overlays.hideAll()
            return
        }
        offSec = 0
        Live.sessionSec++

        val hasUsage = Usage.hasAccess(this)
        if (tickN % 2 == 0 && hasUsage) Live.fgPkg = Usage.foregroundPkg(this, Live.fgPkg)
        if (tickN % 30 == 1 && hasUsage) dayScreenSec = Usage.todayStats(this).values.sum() / 1000
        if (tickN % 3 == 0) reelCount = Store.reels24h(this)

        val now = System.currentTimeMillis()
        val inShortApp = Live.fgPkg in Apps.SHORT
        val scrolling = now - Live.lastScrollAt < 20_000

        if (inShortApp && (scrolling || !Live.a11yOn)) {
            Live.streakSec++
            pendingShort++
        }
        if (inShortApp) notShortSec = 0 else notShortSec++
        val idle = if (Live.a11yOn) now - Live.lastScrollAt > 90_000 else notShortSec > 90
        if (idle) { Live.streakSec = 0; lastStreakLevel = 0 }
        if (pendingShort >= 10) { Store.addShortSec(this, pendingShort); pendingShort = 0 }

        // ---- 20-20-20 style reminders on continuous phone use ----
        val bucket = Live.sessionSec / 1200
        if (bucket > lastSessionBucket) {
            lastSessionBucket = bucket
            sessionAlert(bucket)
        }
        // ---- reels / shorts limits ----
        val level = when {
            Live.streakSec < 600 -> 0
            Live.streakSec < 1500 -> 1
            else -> 2 + ((Live.streakSec - 1500) / 600).toInt()
        }
        if (level > lastStreakLevel) {
            lastStreakLevel = level
            streakAlert(level)
        }

        // ---- camera-side pill ----
        val score = Fatigue.score(Live.sessionSec, Live.streakSec, dayScreenSec)
        val st = Fatigue.state(score, Live.streakSec)
        if (inShortApp && overlays.canDraw()) {
            overlays.showPill(st, "🎬 $reelCount · ${Live.streakSec / 60}मि")
        } else {
            overlays.hidePill()
        }
    }

    private fun sessionAlert(bucket: Long) {
        when {
            bucket == 1L -> alert(
                "आँखें थक रही हैं 👀",
                "20 मिनट हो गए। 20 सेकंड के लिए 20 फ़ीट दूर देखें और पलकें झपकाएँ।",
                "आपकी आँखें थक रही हैं। आँखों को आराम दें, बीस सेकंड के लिए दूर देखें।",
                EyeState.TIRED
            )
            bucket == 2L -> alert(
                "आँखें सूख सकती हैं 😟",
                "लगातार 40 मिनट हो गए। 2 मिनट फ़ोन रखकर दूर देखें।",
                "लगातार चालीस मिनट हो गए। आँखें सूख सकती हैं। दो मिनट फ़ोन रखकर दूर देखें।",
                EyeState.TIRED
            )
            else -> alert(
                "अब ब्रेक ज़रूरी है 😵",
                "लगातार ${bucket * 20} मिनट से फ़ोन चल रहा है। 10 मिनट का ब्रेक लें, थोड़ा टहलें।",
                "लगातार ${bucket * 20} मिनट से फ़ोन चल रहा है। अब दस मिनट का ब्रेक लें।",
                EyeState.EXHAUSTED
            )
        }
    }

    private fun streakAlert(level: Int) {
        if (level == 1) alert(
            "रील्स में आँखें थकने लगीं 😟",
            "10 मिनट से लगातार स्क्रॉल कर रहे हैं। थोड़ा रुकिए, दूर देखिए।",
            "दस मिनट से आप लगातार रील्स देख रहे हैं। आँखें थकने लगी हैं। थोड़ा रुकिए।",
            EyeState.TIRED
        ) else alert(
            "बहुत ज़्यादा रील्स! 😵",
            "आँखें और दिमाग़ दोनों थक चुके हैं। अब रुक जाइए, आराम करिए।",
            "आपने बहुत ज़्यादा रील्स देख ली हैं। आँखें और दिमाग़ दोनों थक चुके हैं। अब रुक जाइए।",
            EyeState.EXHAUSTED
        )
    }

    private fun alert(title: String, body: String, speak: String, st: EyeState) {
        if (overlays.canDraw()) overlays.showBanner(title, body, st) else notifyHeadsUp(title, body)
        if (Store.voiceOn(this) && ttsReady) {
            tts?.speak(speak, TextToSpeech.QUEUE_FLUSH, null, "a${System.nanoTime()}")
        }
    }

    private fun notifyHeadsUp(title: String, body: String) {
        val n = NotificationCompat.Builder(this, "alerts")
            .setSmallIcon(R.drawable.ic_eye)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setTimeoutAfter(8000)
            .setContentIntent(openAppIntent())
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(2, n)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun createChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("monitor", "Monitoring", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel("alerts", "Eye reminders", NotificationManager.IMPORTANCE_HIGH)
        )
    }
}
