package com.raqamsaree.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View

class DragView(context: Context, private val onDone: (Rect) -> Unit) : View(context) {

    companion object {
        private const val FADE_MS = 700L
    }

    private val density = resources.displayMetrics.density
    private val xs = ArrayList<Float>()
    private val ys = ArrayList<Float>()
    private val ts = ArrayList<Long>()
    private var pathLength = 0f
    private var minX = 0f
    private var maxX = 0f
    private var minY = 0f
    private var maxY = 0f
    private var done = false
    private var touching = false

    private val outline = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val line = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val dotFill = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val dotRing = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.WHITE
        isAntiAlias = true
    }

    init {
        setBackgroundColor(Color.parseColor("#55000000"))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (done) return true
        val x = event.rawX
        val y = event.rawY
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                reset()
                touching = true
                addPoint(x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                addPoint(x, y)
                if (isClosed(x, y)) finish()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                addPoint(x, y)
                val w = maxX - minX
                val h = maxY - minY
                if (maxOf(w, h) >= 40f * density) finish() else reset()
            }
        }
        invalidate()
        return true
    }

    private fun reset() {
        xs.clear()
        ys.clear()
        ts.clear()
        pathLength = 0f
        minX = 0f
        maxX = 0f
        minY = 0f
        maxY = 0f
    }

    private fun addPoint(x: Float, y: Float) {
        if (xs.isEmpty()) {
            minX = x
            maxX = x
            minY = y
            maxY = y
        } else {
            val dx = x - xs[xs.size - 1]
            val dy = y - ys[ys.size - 1]
            pathLength += Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        xs.add(x)
        ys.add(y)
        ts.add(System.currentTimeMillis())
    }

    private fun isClosed(x: Float, y: Float): Boolean {
        if (xs.size < 12) return false
        val dx = x - xs[0]
        val dy = y - ys[0]
        val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        return pathLength >= 160f * density &&
            dist <= 40f * density &&
            (maxX - minX) >= 50f * density &&
            (maxY - minY) >= 20f * density
    }

    private fun finish() {
        if (done) return
        done = true
        val pad = (10f * density).toInt()
        val r = Rect(
            minX.toInt() - pad,
            minY.toInt() - pad,
            maxX.toInt() + pad,
            maxY.toInt() + pad
        )
        val minH = (70f * density).toInt()
        if (r.height() < minH) {
            val extra = (minH - r.height()) / 2
            r.top -= extra
            r.bottom += extra
        }
        onDone(r)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (xs.isEmpty()) return
        val now = System.currentTimeMillis()
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val hsv = floatArrayOf(0f, 0.9f, 1f)

        for (i in 1 until xs.size) {
            val age = now - ts[i]
            if (age > FADE_MS) continue
            val f = (1f - age.toFloat() / FADE_MS).coerceIn(0f, 1f)
            hsv[0] = ((i * 6) % 360).toFloat()
            outline.strokeWidth = (2.5f + 4f * f) * density
            outline.color = Color.argb((150 * f).toInt(), 0, 0, 0)
            line.strokeWidth = (1.2f + 2.8f * f) * density
            line.color = Color.HSVToColor((255 * f).toInt(), hsv)
            val x0 = xs[i - 1] - loc[0]
            val y0 = ys[i - 1] - loc[1]
            val x1 = xs[i] - loc[0]
            val y1 = ys[i] - loc[1]
            canvas.drawLine(x0, y0, x1, y1, outline)
            canvas.drawLine(x0, y0, x1, y1, line)
        }

        if (touching) {
            val last = xs.size - 1
            hsv[0] = ((last * 6) % 360).toFloat()
            dotFill.color = Color.HSVToColor(hsv)
            val cx = xs[last] - loc[0]
            val cy = ys[last] - loc[1]
            canvas.drawCircle(cx, cy, 7f * density, dotFill)
            canvas.drawCircle(cx, cy, 7f * density, dotRing)
        }

        if (touching || now - ts[ts.size - 1] <= FADE_MS) {
            postInvalidateOnAnimation()
        }
    }
}
