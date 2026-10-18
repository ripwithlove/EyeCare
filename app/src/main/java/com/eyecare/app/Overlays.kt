package com.eyecare.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Two overlays:
 *  - pill   : tiny always-on-top eye + counter next to the front camera (only inside reels/shorts apps)
 *  - banner : heads-up style popup that slides in from the top and disappears after a few seconds
 */
class Overlays(private val ctx: Context) {

    companion object {
        // Move the pill sideways / down to sit beside your phone's camera hole.
        const val PILL_X_DP = 64
        const val PILL_Y_DP = 2
        private const val WRAP = WindowManager.LayoutParams.WRAP_CONTENT
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var pill: LinearLayout? = null
    private var pillEye: EyeView? = null
    private var pillText: TextView? = null
    private var banner: View? = null
    private var bannerHide: Runnable? = null

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun canDraw() = Settings.canDrawOverlays(ctx)

    private fun params(w: Int, h: Int, gravity: Int, x: Int, y: Int): WindowManager.LayoutParams {
        val p = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = gravity
        p.x = x
        p.y = y
        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return p
    }

    // ---------------- pill ----------------
    fun showPill(st: EyeState, text: String) {
        if (pill == null) {
            val eye = EyeView(ctx)
            val tv = TextView(ctx).apply {
                setTextColor(Color.WHITE)
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(2), dp(10), dp(2))
                background = GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat()
                    setColor(0xE6000000.toInt())
                }
            }
            row.addView(eye, LinearLayout.LayoutParams(dp(26), dp(26)))
            row.addView(tv, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(4) })
            try {
                wm.addView(row, params(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL, dp(PILL_X_DP), dp(PILL_Y_DP)))
            } catch (e: Exception) {
                return
            }
            pill = row; pillEye = eye; pillText = tv
        }
        pillEye?.state = st
        pillText?.text = text
    }

    fun hidePill() {
        pill?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        pill = null; pillEye = null; pillText = null
    }

    // ---------------- banner ----------------
    fun showBanner(title: String, body: String, st: EyeState) {
        hideBanner()
        val eye = EyeView(ctx).apply { state = st }
        val t1 = TextView(ctx).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        }
        val t2 = TextView(ctx).apply {
            text = body
            setTextColor(0xFFCBD5E1.toInt())
            textSize = 13f
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(t1)
            addView(t2)
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(0xF2111827.toInt())
                setStroke(dp(1), 0x33FFFFFF)
            }
        }
        row.addView(eye, LinearLayout.LayoutParams(dp(54), dp(54)))
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(12) })

        val width = ctx.resources.displayMetrics.widthPixels - dp(24)
        try {
            wm.addView(row, params(width, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, dp(40)))
        } catch (e: Exception) {
            return
        }
        banner = row
        val off = dp(160).toFloat()
        row.translationY = -off
        row.alpha = 0f
        row.animate().translationY(0f).alpha(1f).setDuration(350).start()
        val r = Runnable {
            row.animate().translationY(-off).alpha(0f).setDuration(350)
                .withEndAction { if (banner === row) hideBanner() }
                .start()
        }
        bannerHide = r
        handler.postDelayed(r, 6000)
    }

    fun hideBanner() {
        bannerHide?.let { handler.removeCallbacks(it) }
        bannerHide = null
        banner?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        banner = null
    }

    fun hideAll() { hidePill(); hideBanner() }
}
