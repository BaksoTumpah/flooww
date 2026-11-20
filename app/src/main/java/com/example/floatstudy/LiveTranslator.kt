package com.example.floatstudy

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * Terjemahan langsung (Inggris ke Indonesia) untuk teks yang tampil di layar.
 *
 * Teks dan posisinya dibaca dari layanan aksesibilitas (tanpa tangkap layar). Terjemahan memakai ML Kit
 * di dalam HP, jadi tidak butuh API key dan tidak ada teks yang dikirim keluar. Hasilnya digambar di jendela
 * overlay yang tidak menerima sentuhan, sehingga layar di bawahnya tetap bisa digulir dan diketuk.
 * Kolom sandi dan kolom isian tidak pernah dibaca.
 */
class LiveTranslator(private val svc: AccessibilityService) {

    private class Box(val r: Rect, val text: String)

    private val main = Handler(Looper.getMainLooper())
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 500
    }
    private var translator: Translator? = null
    private var ready = false
    private var view: OverlayView? = null
    private var gen = 0

    @Volatile
    var active = false
        private set

    private val refreshRun = Runnable { refresh() }

    fun start(say: (String) -> Unit) {
        if (active) return
        active = true
        val tr = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.INDONESIAN)
                .build()
        )
        translator = tr
        ready = false
        addOverlay()
        if (!active) {
            say("Gagal membuat jendela overlay.")
            return
        }
        say("Live translate dinyalakan…")
        var finished = false
        // pemberitahuan unduh hanya muncul bila unduhan benar-benar butuh waktu (model belum ada di HP)
        val downloadingNotice = Runnable {
            if (!finished && active) say("Mengunduh model terjemahan Inggris ke Indonesia (sekitar 30 MB). Mohon tunggu, butuh internet…")
        }
        main.postDelayed(downloadingNotice, 700)
        val t0 = android.os.SystemClock.elapsedRealtime()
        tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                finished = true
                main.removeCallbacks(downloadingNotice)
                if (!active) return@addOnSuccessListener
                ready = true
                val downloaded = android.os.SystemClock.elapsedRealtime() - t0 > 700
                say(
                    (if (downloaded) "Model selesai diunduh. " else "") +
                        "Live translate siap. Teks Inggris di layar akan diterjemahkan, layar tetap bisa digulir."
                )
                refresh()
            }
            .addOnFailureListener {
                finished = true
                main.removeCallbacks(downloadingNotice)
                say("Gagal mengunduh model terjemahan. Periksa koneksi internet lalu nyalakan Live lagi.")
                stop()
            }
    }

    fun stop() {
        active = false
        gen++
        main.removeCallbacks(refreshRun)
        removeOverlay()
        translator?.close()
        translator = null
        ready = false
    }

    fun onEvent(e: AccessibilityEvent) {
        if (!active) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == svc.packageName || pkg == "com.android.systemui") return
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // layar sedang digulir: sembunyikan terjemahan lama, tampilkan lagi setelah layar diam
                view?.setBoxes(emptyList())
                gen++
                schedule(450)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                view?.setBoxes(emptyList())
                gen++
                schedule(500)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> schedule(650)
        }
    }

    private fun schedule(ms: Long) {
        main.removeCallbacks(refreshRun)
        main.postDelayed(refreshRun, ms)
    }

    private fun refresh() {
        val tr = translator ?: return
        if (!active || !ready) return
        val root = svc.rootInActiveWindow ?: return
        val items = ArrayList<Box>()
        try {
            if (root.packageName?.toString() == svc.packageName) return
            collect(root, items)
        } catch (e: Exception) {
            return
        }
        val myGen = ++gen
        val shown = ArrayList<Box>()
        val pending = ArrayList<Box>()
        for (b in items) {
            val hit = cache[b.text]
            if (hit != null) shown.add(Box(b.r, hit)) else pending.add(b)
        }
        view?.setBoxes(shown.toList())
        for (b in pending) {
            tr.translate(b.text).addOnSuccessListener { out ->
                cache[b.text] = out
                if (gen == myGen && active) {
                    shown.add(Box(b.r, out))
                    view?.setBoxes(shown.toList())
                }
            }
        }
    }

    private val idWords = Regex("\\b(yang|dan|di|ke|dari|untuk|dengan|ini|itu|adalah|tidak|atau|pada)\\b", RegexOption.IGNORE_CASE)
    private val hasLetter = Regex("[A-Za-z]{2,}")

    private fun collect(n: AccessibilityNodeInfo, out: MutableList<Box>) {
        if (out.size >= 120) return
        if (!n.isVisibleToUser) return
        // kolom sandi dan isian pengguna tidak dibaca sama sekali
        if (n.isPassword || n.isEditable) return
        val t = n.text?.toString()?.trim()
        if (!t.isNullOrEmpty() && t.length in 2..500 && hasLetter.containsMatchIn(t) && !idWords.containsMatchIn(t)) {
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.width() > dp(24) && r.height() > dp(12)) out.add(Box(r, t))
        }
        for (i in 0 until n.childCount) {
            val ch = n.getChild(i) ?: continue
            collect(ch, out)
        }
    }

    private fun dp(v: Int) = (v * svc.resources.displayMetrics.density).toInt()

    private fun addOverlay() {
        if (view != null) return
        val v = OverlayView(svc)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try {
            wm.addView(v, lp)
            view = v
        } catch (e: Exception) {
            active = false
        }
    }

    private fun removeOverlay() {
        view?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        view = null
    }

    /** Menggambar kotak hasil terjemahan tepat di atas posisi teks aslinya. */
    private class OverlayView(c: Context) : View(c) {
        private var boxes: List<Box> = emptyList()
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFA1F1B2E.toInt() }
        private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val d = c.resources.displayMetrics.density

        fun setBoxes(b: List<Box>) {
            boxes = b
            postInvalidateOnAnimation()
        }

        override fun onDraw(canvas: Canvas) {
            val pad = (3 * d).toInt()
            for (b in boxes) {
                val r = b.r
                canvas.drawRoundRect(RectF(r), 4 * d, 4 * d, bg)
                val w = (r.width() - 2 * pad).coerceAtLeast(10)
                // cari ukuran huruf terbesar yang masih muat di dalam kotak
                var size = 16f * d
                val min = 9f * d
                var layout: StaticLayout
                while (true) {
                    tp.textSize = size
                    layout = StaticLayout.Builder.obtain(b.text, 0, b.text.length, tp, w)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
                    if (layout.height <= r.height() - 2 * pad || size <= min) break
                    size -= d
                }
                canvas.save()
                canvas.clipRect(r)
                canvas.translate((r.left + pad).toFloat(), (r.top + pad).toFloat())
                layout.draw(canvas)
                canvas.restore()
            }
        }
    }
}
