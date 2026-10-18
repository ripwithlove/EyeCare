package com.eyecare.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private val BG = 0xFF0F172A.toInt()
    private val CARD = 0xFF1E293B.toInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val h = Handler(Looper.getMainLooper())
    private lateinit var eye: EyeView
    private lateinit var stateTv: TextView
    private lateinit var scoreTv: TextView
    private lateinit var bar: ProgressBar
    private lateinit var statsTv: TextView
    private lateinit var appsTv: TextView
    private val permBtns = mutableListOf<Triple<Button, String, () -> Boolean>>()

    private val refresher = object : Runnable {
        override fun run() { refresh(); h.postDelayed(this, 2000) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        setContentView(buildUi())
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.startForegroundService(this, Intent(this, EyeMonitorService::class.java))
        h.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacks(refresher)
    }

    // ------------------------------------------------------------ UI
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(text: String, size: Float, color: Int = 0xFFE2E8F0.toInt(), bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun card(parent: LinearLayout): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(CARD)
            }
        }
        parent.addView(c, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        return c
    }

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(32))
        }

        root.addView(tv("👁️ आई केयर", 24f, 0xFFFFFFFF.toInt(), true))
        root.addView(tv("आपकी आँखों का साथी", 13f, 0xFF94A3B8.toInt()))

        // Eye + fatigue
        val top = card(root)
        top.gravity = Gravity.CENTER_HORIZONTAL
        eye = EyeView(this)
        top.addView(eye, LinearLayout.LayoutParams(dp(150), dp(150)))
        stateTv = tv("", 18f, 0xFFFFFFFF.toInt(), true).apply { gravity = Gravity.CENTER }
        top.addView(stateTv, LinearLayout.LayoutParams(MATCH, WRAP))
        scoreTv = tv("", 14f, 0xFF94A3B8.toInt()).apply { gravity = Gravity.CENTER }
        top.addView(scoreTv, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        top.addView(bar, LinearLayout.LayoutParams(MATCH, dp(12)).apply { topMargin = dp(10) })

        // Stats
        val stats = card(root)
        stats.addView(tv("📊 आज का डेटा", 16f, 0xFFFFFFFF.toInt(), true))
        statsTv = tv("", 14f)
        stats.addView(statsTv, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        appsTv = tv("", 13f, 0xFF94A3B8.toInt())
        stats.addView(appsTv, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })

        // Permissions
        val perms = card(root)
        perms.addView(tv("🔐 ज़रूरी अनुमतियाँ (एक बार)", 16f, 0xFFFFFFFF.toInt(), true))
        addPerm(perms, "Usage Access (स्क्रीन टाइम)",
            { Usage.hasAccess(this) },
            { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) })
        addPerm(perms, "Display over apps (पॉपअप + आँख)",
            { Settings.canDrawOverlays(this) },
            {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            })
        addPerm(perms, "Accessibility (रील्स की गिनती)",
            { a11yEnabled() },
            { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        addPerm(perms, "Notifications",
            {
                Build.VERSION.SDK_INT < 33 ||
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            },
            {
                if (Build.VERSION.SDK_INT >= 33)
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            })
        perms.addView(
            tv(
                "अगर Accessibility का स्विच ग्रे हो: फ़ोन Settings → Apps → आई केयर → ⋮ (तीन बिंदु) → " +
                        "\"Allow restricted settings\" चालू करें, फिर दोबारा कोशिश करें।",
                12f, 0xFF94A3B8.toInt()
            ),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
        )

        // Voice + test
        val ctl = card(root)
        val sw = Switch(this).apply {
            text = "🔊 आवाज़ में बोलकर बताएँ"
            setTextColor(0xFFE2E8F0.toInt())
            isChecked = Store.voiceOn(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Store.setVoice(this@MainActivity, v) }
        }
        ctl.addView(sw, LinearLayout.LayoutParams(MATCH, WRAP))
        val test = Button(this).apply {
            text = "🔔 पॉपअप टेस्ट करें"
            isAllCaps = false
            setOnClickListener {
                ContextCompat.startForegroundService(
                    this@MainActivity,
                    Intent(this@MainActivity, EyeMonitorService::class.java).setAction("TEST")
                )
            }
        }
        ctl.addView(test, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })

        // Advice
        val tips = card(root)
        tips.addView(tv("📌 सुझाव", 16f, 0xFFFFFFFF.toInt(), true))
        tips.addView(
            tv(
                "• 20-20-20 नियम (AAO/AOA): हर 20 मिनट बाद 20 सेकंड तक 20 फ़ीट दूर देखें।\n" +
                        "• स्क्रीन आँखों से लगभग 25 इंच (हाथ भर) दूर और थोड़ा नीचे की ओर रखें।\n" +
                        "• ब्राइटनेस आसपास की रोशनी के हिसाब से रखें और पलकें ज़्यादा झपकाएँ।\n" +
                        "• लगातार 1 घंटे से ज़्यादा न चलाएँ, फिर 10 मिनट का ब्रेक लें। (ऐप का अपना नियम)\n" +
                        "• रील्स/शॉर्ट्स: 10 मिनट लगातार पर आँख 'थकी', 25 मिनट पर 'बहुत थकी' दिखेगी। (ऐप का अपना नियम)\n\n" +
                        "ℹ️ थकान % एक अनुमान है, डॉक्टरी सलाह नहीं। AAO के अनुसार ज़्यादा स्क्रीन टाइम से आँखों को " +
                        "स्थायी नुकसान नहीं होता, लेकिन थकान, सूखापन और सिरदर्द हो सकते हैं।",
                13f, 0xFFCBD5E1.toInt()
            ),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
        )

        return ScrollView(this).apply {
            setBackgroundColor(BG)
            addView(root)
        }
    }

    private fun addPerm(parent: LinearLayout, label: String, check: () -> Boolean, action: () -> Unit) {
        val b = Button(this).apply {
            isAllCaps = false
            setOnClickListener { action() }
        }
        parent.addView(b, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        permBtns.add(Triple(b, label, check))
    }

    private fun a11yEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return s.contains("ReelAccessibilityService")
    }

    // ------------------------------------------------------------ data
    private fun refresh() {
        permBtns.forEach { (b, label, check) ->
            val ok = check()
            b.text = if (ok) "✅ $label — चालू" else "⚠️ $label — अनुमति दें"
            b.isEnabled = !ok
        }

        val hasUsage = Usage.hasAccess(this)
        val stats = if (hasUsage) Usage.todayStats(this) else emptyMap()
        val dayScreen = stats.values.sum() / 1000
        val score = Fatigue.score(Live.sessionSec, Live.streakSec, dayScreen)
        val st = Fatigue.state(score, Live.streakSec)

        eye.state = st
        stateTv.text = when (st) {
            EyeState.COMFORT -> "आँखें आरामदायक 😊"
            EyeState.TIRED -> "आँखें थक रही हैं 😟"
            EyeState.EXHAUSTED -> "आँखें बहुत थक चुकी हैं 😵"
        }
        scoreTv.text = "थकान का अनुमान: $score%"
        bar.progress = score
        bar.progressTintList = ColorStateList.valueOf(
            when (st) {
                EyeState.COMFORT -> 0xFF22C55E.toInt()
                EyeState.TIRED -> 0xFFF59E0B.toInt()
                EyeState.EXHAUSTED -> 0xFFEF4444.toInt()
            }
        )

        statsTv.text =
            "📱 आज का स्क्रीन टाइम: " + (if (hasUsage) Fatigue.fmt(dayScreen) else "— (Usage Access दें)") + "\n" +
                    "⏱️ लगातार फ़ोन चल रहा: ${Fatigue.fmt(Live.sessionSec)}\n" +
                    "🎬 पिछले 24 घंटे में रील्स/शॉर्ट्स: ${Store.reels24h(this)}" +
                    (if (!a11yEnabled()) "  (Accessibility दें)" else "") + "\n" +
                    "🕒 आज रील्स/शॉर्ट्स का समय: ${Fatigue.fmt(Store.shortSecToday(this))}\n" +
                    "🔁 अभी लगातार स्क्रॉल: ${Fatigue.fmt(Live.streakSec)}"

        val pm = packageManager
        appsTv.text = if (stats.isEmpty()) "" else
            "सबसे ज़्यादा इस्तेमाल हुए ऐप्स:\n" +
                    stats.entries.sortedByDescending { it.value }.take(5).joinToString("\n") {
                        val name = try {
                            pm.getApplicationLabel(pm.getApplicationInfo(it.key, 0)).toString()
                        } catch (e: Exception) { it.key }
                        "• $name — ${Fatigue.fmt(it.value / 1000)}"
                    }
    }
}
