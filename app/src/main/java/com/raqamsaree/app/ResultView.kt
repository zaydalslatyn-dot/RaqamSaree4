package com.raqamsaree.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class ResultView(
    context: Context,
    private val frame: Rect,
    original: String,
    private val onCopy: (String) -> Unit,
    private val onClose: () -> Unit
) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private val translationView: TextView
    private val copyButton: Button
    private var translated: String? = null

    private val framePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = Color.parseColor("#4DD0E1")
        isAntiAlias = true
    }

    private val frameGlow = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 9f * density
        color = Color.argb(80, 77, 208, 225)
        isAntiAlias = true
    }

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.argb(110, 0, 0, 0))
        setOnClickListener { onClose() }
        layoutDirection = View.LAYOUT_DIRECTION_RTL

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(16)
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F21F2430"))
                cornerRadius = dp(18).toFloat()
            }
            isClickable = true
        }

        val originalView = TextView(context).apply {
            text = original
            setTextColor(Color.parseColor("#9AA4B2"))
            textSize = 13f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.LEFT
        }

        translationView = TextView(context).apply {
            text = "جاري الترجمة...\n(أول مرة قد تحتاج إنترنت لتنزيل ملف الترجمة)"
            setTextColor(Color.WHITE)
            textSize = 18f
            textDirection = View.TEXT_DIRECTION_RTL
            gravity = Gravity.RIGHT
            setLineSpacing(0f, 1.15f)
        }

        val maxH = (resources.displayMetrics.heightPixels * 0.38f).toInt()
        val scroll = MaxHeightScrollView(context, maxH)
        scroll.addView(translationView)

        copyButton = Button(context).apply {
            text = "نسخ الترجمة"
            isEnabled = false
            alpha = 0.5f
            setOnClickListener { translated?.let { onCopy(it) } }
        }
        styleButton(copyButton, "#4DD0E1", "#10151C")

        val closeButton = Button(context).apply {
            text = "إغلاق"
            setOnClickListener { onClose() }
        }
        styleButton(closeButton, "#3A4252", "#FFFFFF")

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(copyButton, halfParams())
        row.addView(closeButton, halfParams())

        card.addView(originalView)
        card.addView(scroll, fullParams(10))
        card.addView(row, fullParams(14))

        val screenH = resources.displayMetrics.heightPixels
        val atBottom = frame.centerY() < screenH / 2
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            if (atBottom) Gravity.BOTTOM else Gravity.TOP
        )
        lp.setMargins(dp(12), dp(48), dp(12), dp(24))
        addView(card, lp)
    }

    fun setTranslation(text: String) {
        translated = text
        translationView.text = text
        copyButton.isEnabled = true
        copyButton.alpha = 1f
    }

    fun setError(msg: String) {
        translationView.text = msg
        copyButton.isEnabled = false
        copyButton.alpha = 0.5f
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    private fun halfParams(): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        p.setMargins(dp(4), 0, dp(4), 0)
        return p
    }

    private fun fullParams(topDp: Int): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        p.topMargin = dp(topDp)
        return p
    }

    private fun styleButton(b: Button, bg: String, fg: String) {
        b.setTextColor(Color.parseColor(fg))
        b.textSize = 16f
        b.setAllCaps(false)
        b.background = GradientDrawable().apply {
            setColor(Color.parseColor(bg))
            cornerRadius = dp(12).toFloat()
        }
        b.minHeight = dp(46)
        b.minimumHeight = dp(46)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val r = RectF(
            (frame.left - loc[0]).toFloat(),
            (frame.top - loc[1]).toFloat(),
            (frame.right - loc[0]).toFloat(),
            (frame.bottom - loc[1]).toFloat()
        )
        r.inset(-6f * density, -6f * density)
        val rad = 10f * density
        canvas.drawRoundRect(r, rad, rad, frameGlow)
        canvas.drawRoundRect(r, rad, rad, framePaint)
    }
}

private class MaxHeightScrollView(context: Context, private val maxH: Int) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            widthMeasureSpec,
            View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST)
        )
    }
}
