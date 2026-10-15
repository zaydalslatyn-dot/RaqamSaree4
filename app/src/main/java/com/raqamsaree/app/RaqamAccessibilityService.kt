package com.raqamsaree.app

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
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
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLException

class RaqamAccessibilityService : AccessibilityService() {

    companion object {
        var instance: RaqamAccessibilityService? = null
        const val LONG_PRESS_MS = 300L
        const val AUTO_EXIT_MS = 10000L
        const val DRAG_EXIT_MS = 20000L
        const val RESULT_EXIT_MS = 90000L
        const val DOUBLE_PRESS_MS = 700L
        private const val MAX_TRANSLATE_CHARS = 2000
        private const val CHUNK_CHARS = 1200
        private val NUMBER_REGEX = Regex("[0-9][0-9/\\-:.,،]*")
        private val LATIN_REGEX = Regex("[A-Za-z]")
    }

    private class Found(val bounds: Rect, val text: String)

    private class OnlineResult(val text: String?, val lang: String?, val error: String?)

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
            toast("ارسم دائرة حول النص اللي تبغى تترجمه")
        }
    }

    private fun handleSelection(rect: Rect) {
        val result = collectTextToTranslate(rect)
        if (result == null) {
            exitSelectionMode()
            toast("ما لقيت نص يحتاج ترجمة داخل الدائرة")
            return
        }
        val text = result.first.take(MAX_TRANSLATE_CHARS)
        val frame = result.second
        val view = ResultView(
            this, frame, text,
            { copyToClipboard(it) },
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

    private fun needsTranslation(t: String): Boolean {
        var letters = 0
        var arabic = 0
        for (ch in t) {
            if (ch.isLetter()) {
                letters++
                if (ch in '\u0600'..'\u06FF' || ch in '\u0750'..'\u077F' ||
                    ch in '\uFB50'..'\uFDFF' || ch in '\uFE70'..'\uFEFF'
                ) {
                    arabic++
                }
            }
        }
        return letters >= 2 && arabic * 2 < letters
    }

    private fun collectTextToTranslate(rect: Rect): Pair<String, Rect>? {
        val root = rootInActiveWindow ?: return null
        val all = mutableListOf<Found>()

        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val b = Rect()
            node.getBoundsInScreen(b)
            if (node.isVisibleToUser && Rect.intersects(rect, b)) {
                val t = node.text?.toString()
                if (!t.isNullOrBlank() && needsTranslation(t)) {
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
                val t = online.text
                if (t != null) {
                    onDone(t, onlineLabel(online.lang), null)
                } else {
                    translateOffline(text) { out, err ->
                        onDone(out, offlineLabel(online.error), err)
                    }
                }
            }
        }.start()
    }

    private fun onlineLabel(lang: String?): String {
        val base = "ترجمة قوقل (إنترنت)"
        if (lang.isNullOrBlank() || lang == "null") return base
        val name = Locale.forLanguageTag(lang).getDisplayLanguage(Locale("ar"))
        return if (name.isBlank() || name == lang) "$base • من: $lang" else "$base • من: $name"
    }

    private fun offlineLabel(reason: String?): String {
        val base = "ترجمة محلية بدون إنترنت (للإنجليزية فقط، دقتها أقل)"
        return if (reason.isNullOrBlank()) base else "$base\nسبب عدم استخدام الإنترنت: $reason"
    }

    private fun describe(e: Exception): String {
        return when (e) {
            is UnknownHostException -> "لا يوجد اتصال بالإنترنت"
            is SocketTimeoutException -> "انتهت مهلة الاتصال"
            is ConnectException -> "تعذّر الاتصال بالإنترنت"
            is SSLException -> "خطأ في الاتصال الآمن"
            else -> "خطأ غير متوقع (" + e.javaClass.simpleName + ")"
        }
    }

    private fun splitChunks(text: String, max: Int): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (line in text.split("\n")) {
            if (cur.isNotEmpty() && cur.length + line.length + 1 > max) {
                out.add(cur.toString())
                cur.setLength(0)
            }
            var rest = line
            while (rest.length > max) {
                if (cur.isNotEmpty()) {
                    out.add(cur.toString())
                    cur.setLength(0)
                }
                out.add(rest.substring(0, max))
                rest = rest.substring(max)
            }
            if (cur.isNotEmpty()) cur.append("\n")
            cur.append(rest)
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    private fun translateOnline(text: String): OnlineResult {
        return try {
            val sb = StringBuilder()
            var lang: String? = null
            var failure: String? = null
            for ((i, chunk) in splitChunks(text, CHUNK_CHARS).withIndex()) {
                val r = requestChunk(chunk)
                val t = r.text
                if (t == null) {
                    failure = r.error ?: "تعذّر الاتصال"
                    break
                }
                if (i > 0) sb.append("\n")
                sb.append(t)
                if (lang == null) lang = r.lang
            }
            val out = sb.toString().trim()
            if (failure != null) {
                OnlineResult(null, null, failure)
            } else if (out.isEmpty()) {
                OnlineResult(null, null, "ردّ فارغ")
            } else {
                OnlineResult(out, lang, null)
            }
        } catch (e: Exception) {
            OnlineResult(null, null, describe(e))
        }
    }

    private fun requestChunk(chunk: String): OnlineResult {
        var conn: HttpURLConnection? = null
        return try {
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=ar&dt=t&q=" +
                URLEncoder.encode(chunk, "UTF-8")
            val c = URL(url).openConnection() as HttpURLConnection
            conn = c
            c.requestMethod = "GET"
            c.connectTimeout = 4000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            val code = c.responseCode
            if (code != 200) {
                OnlineResult(null, null, "رمز الخطأ $code")
            } else {
                val json = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val root = JSONArray(json)
                val arr = root.getJSONArray(0)
                val sb = StringBuilder()
                for (i in 0 until arr.length()) {
                    val seg = arr.optJSONArray(i) ?: continue
                    sb.append(seg.optString(0, ""))
                }
                val lang = if (root.length() > 2) root.optString(2, "") else ""
                val out = sb.toString()
                if (out.isBlank()) {
                    OnlineResult(null, null, "ردّ فارغ")
                } else {
                    OnlineResult(out, lang, null)
                }
            }
        } catch (e: Exception) {
            OnlineResult(null, null, describe(e))
        } finally {
            conn?.disconnect()
        }
    }

    private fun translateOffline(text: String, onDone: (String?, String?) -> Unit) {
        if (!LATIN_REGEX.containsMatchIn(text)) {
            onDone(null, "هذا النص يحتاج إنترنت للترجمة، والترجمة بدون إنترنت تدعم الإنجليزية فقط")
            return
        }
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
