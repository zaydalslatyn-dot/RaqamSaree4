package com.raqamsaree.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View

class DragView(context: Context, private val onDone: (Rect) -> Unit) : View(context) {

    private val density = resources.displayMetrics.density
    private val xs = ArrayList<Float>()
    private val ys = ArrayList<Float>()
    private var pathLength = 0f
    private var minX = 0f
    private var maxX = 0f
    private var minY = 0f
    private var maxY = 0f
    private var done = false

    private val trail = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f * density
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val glow = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 13f * density
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    init {
        setBackgroundColor(Color.parseColor("#B3000000"))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (done) return true
        val x = event.rawX
        val y = event.rawY
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                reset()
                addPoint(x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                addPoint(x, y)
                if (isClosed(x, y)) finish()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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
        if (xs.size < 2) return
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val hsv = floatArrayOf(0f, 0.9f, 1f)
        for (i in 1 until xs.size) {
            hsv[0] = ((i * 6) % 360).toFloat()
            glow.color = Color.HSVToColor(70, hsv)
            trail.color = Color.HSVToColor(hsv)
            val x0 = xs[i - 1] - loc[0]
            val y0 = ys[i - 1] - loc[1]
            val x1 = xs[i] - loc[0]
            val y1 = ys[i] - loc[1]
            canvas.drawLine(x0, y0, x1, y1, glow)
            canvas.drawLine(x0, y0, x1, y1, trail)
        }
    }
}
