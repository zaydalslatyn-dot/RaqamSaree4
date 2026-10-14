package com.raqamsaree.app

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class RaqamAccessibilityService : AccessibilityService() {

    companion object {
        var instance: RaqamAccessibilityService? = null
        const val LONG_PRESS_MS = 300L
        const val AUTO_EXIT_MS = 10000L
        const val DRAG_EXIT_MS = 20000L
        const val RESULT_EXIT_MS = 90000L
        const val DOUBLE_PRESS_MS = 700L
        private const val MAX_TRANSLATE_CHARS = 2000
        private const val SOURCE_ONLINE = "ترجمة قوقل (إنترنت)"
        private const val SOURCE_OFFLINE = "ترجمة محلية بدون إنترنت (دقتها أقل)"
        private val NUMBER_REGEX = Regex("[0-9][0-9/\\-:.,،]*")
        private val LATIN_REGEX = Regex("[A-Za-z]")
    }

    private class Found(val bounds: Rect, val text: String)

    private lateinit var windowManager: WindowManager
    private var overlay: View? = null
    private var lastPress = 0L
    private var translator: Translator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoExit = Runnable { exitSelectionMode() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching {
            getTranslator().downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build())
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        exitSelectionMode()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        exitSelectionMode()
        runCatching { translator?.close() }
        translator = null
        instance = null
        super.onDestroy()
    }

    fun toggleSelectionMode() {
        val now = System.currentTimeMillis()
        val quick = now - lastPress < DOUBLE_PRESS_MS
        lastPress = now
        if (overlay != null) {
            if (quick) enterDragMode() else exitSelectionMode()
            return
        }
        enterSelectionMode()
    }

    private fun overlayParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
    }

    private fun showOverlay(v: View, timeoutMs: Long): Boolean {
        exitSelectionMode()
        val ok = runCatching { windowManager.addView(v, overlayParams()) }.isSuccess
        if (ok) {
            overlay = v
            handler.postDelayed(autoExit, timeoutMs)
        }
        return ok
    }

    private fun exitSelectionMode() {
        handler.removeCallbacks(autoExit)
        overlay?.let { runCatching { windowManager.removeView(it) } }
        overlay = null
    }

    private fun enterSelectionMode() {
        val v = View(this).apply {
            setBackgroundColor(Color.parseColor("#B3000000"))
        }
        var downTime = 0L
        v.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val full = System.currentTimeMillis() - downTime >= LONG_PRESS_MS
                    val x = event.rawX.toInt()
                    val y = event.rawY.toInt()
                    handler.post { handleTap(x, y, full) }
                    true
                }
                else -> true
            }
        }
        showOverlay(v, AUTO_EXIT_MS)
    }

    private fun enterDragMode() {
        val v = DragView(this) { rect -> handler.post { handleSelection(rect) } }
        if (showOverlay(v, DRAG_EXIT_MS)) {
            toast("ارسم دائرة حول النص الإنجليزي")
        }
    }

    private fun handleSelection(rect: Rect) {
        val result = collectEnglishText(rect)
        if (result == null) {
            exitSelectionMode()
            toast("ما لقيت نص إنجليزي داخل الدائرة")
            return
        }
        val text = result.first.take(MAX_TRANSLATE_CHARS)
        val frame = result.second
        val view = ResultView(
            this, frame, text,
            { copyToClipboard(it) },
            { openInGoogleTranslate(text) },
            { exitSelectionMode() }
        )
        showOverlay(view, RESULT_EXIT_MS)
        translate(text) { arabic, source, error ->
            if (overlay === view) {
                if (arabic != null) {
                    view.setTranslation(arabic, source)
                } else {
                    view.setError(error ?: "تعذّرت الترجمة")
                }
            }
        }
    }

    private fun collectEnglishText(rect: Rect): Pair<String, Rect>? {
        val root = rootInActiveWindow ?: return null
        val all = mutableListOf<Found>()

        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val b = Rect()
            node.getBoundsInScreen(b)
            if (node.isVisibleToUser && Rect.intersects(rect, b)) {
                val t = node.text?.toString()
                if (!t.isNullOrBlank() && LATIN_REGEX.containsMatchIn(t)) {
                    all.add(Found(b, t.trim()))
                }
            }
            for (i in 0 until node.childCount) {
                visit(node.getChild(i))
            }
        }

        visit(root)
        if (all.isEmpty()) return null

        val strict = all.filter { f ->
            val area = f.bounds.width().toLong() * f.bounds.height().toLong()
            val inter = Rect(f.bounds)
            val hit = inter.intersect(rect)
            val interArea = if (hit) inter.width().toLong() * inter.height().toLong() else 0L
            rect.contains(f.bounds.centerX(), f.bounds.centerY()) ||
                (area > 0 && interArea * 2 >= area)
        }
        val chosen = if (strict.isNotEmpty()) {
            strict
        } else {
            listOf(all.minByOrNull { it.bounds.width().toLong() * it.bounds.height().toLong() }!!)
        }

        val sorted = chosen.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        val text = sorted.map { it.text }.distinct().joinToString("\n")
        val union = Rect(sorted[0].bounds)
        for (f in sorted) union.union(f.bounds)
        val dm = resources.displayMetrics
        union.intersect(0, 0, dm.widthPixels, dm.heightPixels)
        return Pair(text, union)
    }

    private fun getTranslator(): Translator {
        return translator ?: Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.ARABIC)
                .build()
        ).also { translator = it }
    }

    private fun translate(text: String, onDone: (String?, String, String?) -> Unit) {
        Thread {
            val online = translateOnline(text)
            handler.post {
                if (online != null) {
                    onDone(online, SOURCE_ONLINE, null)
                } else {
                    translateOffline(text) { out, err -> onDone(out, SOURCE_OFFLINE, err) }
                }
            }
        }.start()
    }

    private fun translateOnline(text: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val c = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=ar&dt=t")
                .openConnection() as HttpURLConnection
            conn = c
            c.requestMethod = "POST"
            c.connectTimeout = 4000
            c.readTimeout = 8000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            val body = "q=" + URLEncoder.encode(text, "UTF-8")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (c.responseCode != 200) {
                null
            } else {
                val json = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val arr = JSONArray(json).getJSONArray(0)
                val sb = StringBuilder()
                for (i in 0 until arr.length()) {
                    val seg = arr.optJSONArray(i) ?: continue
                    sb.append(seg.optString(0, ""))
                }
                val out = sb.toString().trim()
                if (out.isEmpty()) null else out
            }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun translateOffline(text: String, onDone: (String?, String?) -> Unit) {
        val t = getTranslator()
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                t.translate(text)
                    .addOnSuccessListener { out -> onDone(out, null) }
                    .addOnFailureListener { onDone(null, "تعذّرت الترجمة") }
            }
            .addOnFailureListener {
                onDone(null, "تعذّرت الترجمة. تأكد من الإنترنت وجرّب مرة ثانية")
            }
    }

    private fun openInGoogleTranslate(text: String) {
        exitSelectionMode()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            setPackage("com.google.android.apps.translate")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(send)
        } catch (e: Exception) {
            try {
                val url = "https://translate.google.com/?sl=en&tl=ar&op=translate&text=" +
                    URLEncoder.encode(text.take(1500), "UTF-8")
                val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(view)
            } catch (e2: Exception) {
                toast("تعذّر فتح مترجم قوقل")
            }
        }
    }

    private fun handleTap(x: Int, y: Int, fullText: Boolean) {
        val hit = findNodeAt(x, y)
        exitSelectionMode()
        if (hit == null) {
            toast("ما لقيت نص بهالمكان")
            return
        }
        val rawText = hit.first
        val bounds = hit.second

        if (fullText) {
            copyToClipboard(rawText.trim())
            return
        }

        val lines = rawText.split("\n").filter { it.isNotBlank() }
        val line = if (lines.size <= 1) {
            rawText
        } else {
            val lineHeight = bounds.height().toFloat() / lines.size
            val idx = (((y - bounds.top) / lineHeight).toInt()).coerceIn(0, lines.size - 1)
            lines[idx]
        }

        val numbers = NUMBER_REGEX.findAll(toEnglishDigits(line))
            .map { it.value.trimEnd('/', '-', ':', '.', ',', '،') }
            .filter { it.isNotEmpty() }
            .toList()
        val best = numbers.maxByOrNull { it.length }
        if (best.isNullOrEmpty()) {
            toast("ما لقيت أرقام بهالسطر")
        } else {
            copyToClipboard(best)
        }
    }

    private fun findNodeAt(x: Int, y: Int): Pair<String, Rect>? {
        val root = rootInActiveWindow ?: return null
        var bestText: String? = null
        var bestBounds: Rect? = null
        var bestArea = Long.MAX_VALUE
        val rect = Rect()

        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            node.getBoundsInScreen(rect)
            if (rect.contains(x, y) && node.isVisibleToUser) {
                val t = node.text?.toString() ?: node.contentDescription?.toString()
                if (!t.isNullOrBlank()) {
                    val area = rect.width().toLong() * rect.height().toLong()
                    if (area < bestArea) {
                        bestArea = area
                        bestText = t
                        bestBounds = Rect(rect)
                    }
                }
            }
            for (i in 0 until node.childCount) {
                visit(node.getChild(i))
            }
        }

        visit(root)
        val t = bestText ?: return null
        val b = bestBounds ?: return null
        return Pair(t, b)
    }

    private fun toEnglishDigits(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when (c) {
                in '\u0660'..'\u0669' -> sb.append('0' + (c - '\u0660'))
                in '\u06F0'..'\u06F9' -> sb.append('0' + (c - '\u06F0'))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("raqam", text))
        toast(if (text.length > 60) "تم النسخ" else "تم النسخ: $text")
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
