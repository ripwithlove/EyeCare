package com.eyecare.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

/**
 * Animated eye.
 * COMFORT   : wide open, slight "staring" drift, normal blink every few seconds.
 * TIRED     : half-closed, droopy sad brow, slow repeated blinks, bag under the eye.
 * EXHAUSTED : almost shut, reddish eye with veins, heavy droop/close cycles.
 */
class EyeView(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var state: EyeState = EyeState.COMFORT
        set(v) { field = v; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val oval = RectF()
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onMeasure(w: Int, h: Int) {
        val d = (110 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(resolveSize(d, w), resolveSize(d, h))
    }

    /** 0..1 how closed the lid is at time t (quick close, slower open). */
    private fun closeness(t: Long, period: Long, dur: Long): Float {
        val x = t % period
        if (x >= dur) return 0f
        val f = x.toFloat() / dur
        return if (f < 0.4f) f / 0.4f else 1f - (f - 0.4f) / 0.6f
    }

    override fun onDraw(c: Canvas) {
        val t = SystemClock.uptimeMillis()
        // base openness, min openness, blink period, blink duration
        val cfg = when (state) {
            EyeState.COMFORT -> floatArrayOf(1f, 0.05f, 3600f, 240f)
            EyeState.TIRED -> floatArrayOf(0.62f, 0.10f, 1700f, 700f)
            EyeState.EXHAUSTED -> floatArrayOf(0.40f, 0.03f, 1300f, 950f)
        }
        val open = cfg[0] + (cfg[1] - cfg[0]) * closeness(t, cfg[2].toLong(), cfg[3].toLong())
        val dx = when (state) {
            EyeState.COMFORT -> sin(t / 700.0).toFloat() * 0.08f
            EyeState.TIRED -> sin(t / 1100.0).toFloat() * 0.05f
            EyeState.EXHAUSTED -> sin(t / 1500.0).toFloat() * 0.03f
        }
        val dy = when (state) {
            EyeState.COMFORT -> 0f
            EyeState.TIRED -> 0.12f
            EyeState.EXHAUSTED -> 0.22f
        }

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2
        val cy = h * 0.55f
        val rx = w * 0.42f
        val maxRy = rx * 0.62f
        val ry = maxRy * open
        val lineW = rx * 0.07f

        // eyebrow
        paint.reset(); paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = lineW * 1.3f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = 0xFFCBD5E1.toInt()
        path.reset()
        when (state) {
            EyeState.COMFORT -> {
                path.moveTo(cx - rx * 0.8f, cy - maxRy * 1.85f)
                path.quadTo(cx, cy - maxRy * 2.45f, cx + rx * 0.8f, cy - maxRy * 1.85f)
            }
            EyeState.TIRED -> {
                path.moveTo(cx - rx * 0.8f, cy - maxRy * 2.15f)
                path.lineTo(cx + rx * 0.8f, cy - maxRy * 1.65f)
            }
            EyeState.EXHAUSTED -> {
                path.moveTo(cx - rx * 0.8f, cy - maxRy * 2.3f)
                path.lineTo(cx + rx * 0.8f, cy - maxRy * 1.45f)
            }
        }
        c.drawPath(path, paint)

        // bag under the eye
        if (state != EyeState.COMFORT) {
            paint.color = if (state == EyeState.TIRED) 0x558B5CF6 else 0x99A855F7.toInt()
            paint.strokeWidth = lineW * 1.2f
            oval.set(cx - rx * 0.75f, cy + maxRy * 0.55f, cx + rx * 0.75f, cy + maxRy * 1.7f)
            c.drawArc(oval, 25f, 130f, false, paint)
        }

        if (ry > 1.5f) {
            path.reset()
            path.moveTo(cx - rx, cy)
            path.quadTo(cx, cy - ry * 2f, cx + rx, cy)
            path.quadTo(cx, cy + ry * 1.8f, cx - rx, cy)
            path.close()

            c.save()
            c.clipPath(path)
            paint.reset(); paint.isAntiAlias = true
            paint.style = Paint.Style.FILL
            paint.color = when (state) {
                EyeState.COMFORT -> Color.WHITE
                EyeState.TIRED -> 0xFFFFF1F1.toInt()
                EyeState.EXHAUSTED -> 0xFFFFDADA.toInt()
            }
            c.drawRect(0f, 0f, w, h, paint)

            val ix = cx + dx * rx
            val iy = cy + dy * maxRy
            val irisR = maxRy * 0.95f
            paint.color = if (state == EyeState.EXHAUSTED) 0xFF0C7BB3.toInt() else 0xFF0EA5E9.toInt()
            c.drawCircle(ix, iy, irisR, paint)
            paint.color = 0xFF0F172A.toInt()
            c.drawCircle(ix, iy, irisR * 0.46f, paint)
            paint.color = Color.WHITE
            c.drawCircle(ix - irisR * 0.28f, iy - irisR * 0.3f, irisR * 0.16f, paint)

            if (state != EyeState.COMFORT) {   // red veins
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = lineW * 0.45f
                paint.color = if (state == EyeState.TIRED) 0x66EF4444 else 0xCCEF4444.toInt()
                val n = if (state == EyeState.TIRED) 2 else 4
                for (i in 0 until n) {
                    val k = i * 0.12f
                    c.drawLine(cx - rx, cy + k * maxRy, cx - rx * (0.55f - k * 0.3f), cy - maxRy * (0.1f + k), paint)
                    c.drawLine(cx + rx, cy - k * maxRy, cx + rx * (0.55f - k * 0.3f), cy + maxRy * (0.1f + k), paint)
                }
            }
            c.restore()

            paint.reset(); paint.isAntiAlias = true
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = lineW
            paint.color = 0xFFE2E8F0.toInt()
            c.drawPath(path, paint)
        } else {
            // eye fully shut: a curved lid line
            paint.reset(); paint.isAntiAlias = true
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = lineW * 1.3f
            paint.color = 0xFFE2E8F0.toInt()
            path.reset()
            path.moveTo(cx - rx, cy)
            path.quadTo(cx, cy + rx * 0.22f, cx + rx, cy)
            c.drawPath(path, paint)
        }
    }
}
