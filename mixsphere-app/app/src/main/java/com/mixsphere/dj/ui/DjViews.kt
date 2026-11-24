package com.mixsphere.dj.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

class WaveformView(context: Context, private val color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var peaks = FloatArray(0)
    private var progress = 0f
    private var active = false

    fun setPeaks(next: FloatArray?) {
        peaks = next ?: FloatArray(0)
        invalidate()
    }

    fun setProgress(value: Float) {
        if (value != progress) {
            progress = value.coerceIn(0f, 1f)
            invalidate()
        }
    }

    fun setActive(value: Boolean) {
        if (value != active) {
            active = value
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = Theme.SCOPE
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.color = Theme.GRID
        paint.strokeWidth = 1f
        val mid = height / 2f
        canvas.drawLine(0f, mid, width.toFloat(), mid, paint)
        for (x in 0..width step 36) {
            canvas.drawLine(x.toFloat(), 0f, x.toFloat(), height.toFloat(), paint)
        }
        if (peaks.isNotEmpty()) {
            val step = width.toFloat() / peaks.size
            val passed = progress * peaks.size
            for (i in peaks.indices) {
                val amplitude = peaks[i] * mid * 0.92f
                if (amplitude < 1f) continue
                paint.color = if (i <= passed) color else Theme.BG3
                paint.strokeWidth = max(2f, step * 0.7f)
                val x = i * step + step * 0.5f
                canvas.drawLine(x, mid - amplitude, x, mid + amplitude, paint)
            }
        }
        if (active) {
            paint.color = color
            paint.strokeWidth = 1f
            val playhead = progress * width
            canvas.drawLine(playhead, 0f, playhead, height.toFloat(), paint)
        }
        paint.color = Color.WHITE
        paint.strokeWidth = 2f
        val playhead = progress * width
        canvas.drawLine(playhead, 0f, playhead, height.toFloat(), paint)
    }
}

class JogView(context: Context, private val color: Int, private val letter: String) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var angle = 0f
    private var lastAngle = 0f
    private var dragging = false
    private var spinning = false
    var onScratch: ((Float) -> Unit)? = null

    fun setSpinning(value: Boolean) {
        if (spinning != value) {
            spinning = value
            invalidate()
        }
    }

    fun setPosition(fraction: Float) {
        val next = fraction.coerceIn(0f, 1f) * (2f * PI.toFloat())
        if (kotlin.math.abs(next - angle) > 0.01f) {
            angle = next
            invalidate()
        }
    }

    init {
        isClickable = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val a = atan2(event.y - height / 2f, event.x - width / 2f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                lastAngle = a
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                var delta = a - lastAngle
                if (delta > PI) delta -= (2 * PI).toFloat()
                if (delta < -PI) delta += (2 * PI).toFloat()
                angle += delta
                onScratch?.invoke(delta)
                lastAngle = a
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) * 0.88f
        paint.color = Theme.GROOVE
        canvas.drawCircle(cx, cy, radius, paint)
        paint.color = Theme.BG2
        canvas.drawCircle(cx, cy, radius * 0.93f, paint)
        paint.style = Paint.Style.STROKE
        paint.color = Theme.GRID
        paint.strokeWidth = 2f
        canvas.drawCircle(cx, cy, radius * 0.72f, paint)
        canvas.drawCircle(cx, cy, radius * 0.44f, paint)
        paint.color = Theme.BG3
        paint.strokeWidth = 1f
        for (tick in 0 until 48) {
            val a = angle + tick * (2f * PI.toFloat() / 48f)
            val inner = radius * if (tick % 4 == 0) 0.78f else 0.84f
            val outer = radius * 0.9f
            val cosA = cos(a.toDouble()).toFloat()
            val sinA = kotlin.math.sin(a.toDouble()).toFloat()
            canvas.drawLine(
                cx + cosA * inner,
                cy + sinA * inner,
                cx + cosA * outer,
                cy + sinA * outer,
                paint,
            )
        }
        paint.color = if (dragging) Theme.ACCENT else color
        paint.strokeWidth = 5f
        canvas.drawArc(
            cx - radius * 0.93f, cy - radius * 0.93f,
            cx + radius * 0.93f, cy + radius * 0.93f,
            angle, 48f, false, paint,
        )
        paint.style = Paint.Style.FILL
        paint.color = if (spinning || dragging) color else Theme.DIM
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = radius * 0.48f
        paint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        canvas.drawText(letter, cx, cy + radius * 0.18f, paint)
        paint.style = Paint.Style.STROKE
        paint.color = if (spinning) color else Theme.BG3
        paint.strokeWidth = 2f
        canvas.drawCircle(cx, cy, radius * 0.2f, paint)
        paint.style = Paint.Style.FILL
    }
}

class LevelView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var level = 0f
    var color = Theme.GREEN

    fun setLevel(value: Float) {
        val next = value.coerceIn(0f, 1f)
        if (next != level) {
            level = next
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = Theme.GROOVE
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        val top = (1f - level) * height
        paint.color = if (level > 0.85f) Theme.RED else color
        canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), paint)
    }
}

class CrossfaderView(context: Context) : View(context) {
    var onValue: ((Float) -> Unit)? = null
    private var value = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setValue(next: Float) {
        value = next.coerceIn(-1f, 1f)
        onValue?.invoke(value)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP ->
                setValue((event.x / width * 2f - 1f).coerceIn(-1f, 1f))
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val mid = height / 2f
        paint.color = Theme.GROOVE
        canvas.drawRoundRect(0f, mid - 7f, width.toFloat(), mid + 7f, 6f, 6f, paint)
        val centre = width / 2f
        val thumbX = centre + (width / 2f) * value
        paint.color = Theme.DECK_A
        canvas.drawRoundRect(0f, mid - 7f, max(centre, thumbX), mid + 7f, 6f, 6f, paint)
        paint.color = Theme.DECK_B
        if (thumbX < width.toFloat()) {
            canvas.drawRoundRect(thumbX, mid - 7f, width.toFloat(), mid + 7f, 6f, 6f, paint)
        }
        paint.color = Theme.TEXT
        paint.strokeWidth = 2f
        canvas.drawLine(centre, 0f, centre, height.toFloat(), paint)
        paint.color = Color.WHITE
        canvas.drawRoundRect(thumbX - 9f, 0f, thumbX + 9f, height.toFloat(), 5f, 5f, paint)
    }
}

class XyPadView(context: Context) : View(context) {
    var onValue: ((Float, Float) -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var x = 0.5f
    private var y = 0.5f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                x = (event.x / width).coerceIn(0f, 1f)
                y = (event.y / height).coerceIn(0f, 1f)
                onValue?.invoke(x, y)
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = Theme.GROOVE
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.color = Theme.GRID
        paint.strokeWidth = 1f
        canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), paint)
        canvas.drawLine(0f, height / 2f, width.toFloat(), height / 2f, paint)
        paint.color = Theme.ACCENT
        canvas.drawCircle(width * x, height * y, 9f, paint)
    }
}

class VerticalFader(context: Context, private val color: Int) : View(context) {
    var onValue: ((Float) -> Unit)? = null
    private var value = 0.5f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var dragging = false

    fun setValue(next: Float) {
        value = next.coerceIn(0f, 1f)
        onValue?.invoke(value)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> dragging = true
            MotionEvent.ACTION_MOVE -> if (dragging) setValue(1f - event.y / height)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        paint.color = Theme.GROOVE
        canvas.drawRoundRect(cx - 6f, 0f, cx + 6f, height.toFloat(), 6f, 6f, paint)
        paint.color = color
        val top = (1f - value) * height
        canvas.drawRoundRect(cx - 6f, top, cx + 6f, height.toFloat(), 6f, 6f, paint)
        paint.color = Color.WHITE
        canvas.drawRoundRect(cx - 11f, top - 5f, cx + 11f, top + 5f, 4f, 4f, paint)
    }
}
