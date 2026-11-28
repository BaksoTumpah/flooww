package com.example.floatstudy

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.Bitmap
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
import android.view.Display
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
 * Terjemahan langsung (bahasa pilihan, bawaan Inggris, ke Indonesia) untuk teks yang tampil di layar.
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

    private var nodeBoxes: List<Box> = emptyList() // terjemahan dari teks yang dibaca lewat node aksesibilitas
    private var ocrBoxes: List<Box> = emptyList() // terjemahan dari OCR tangkapan layar (layar tanpa teks node)
    private var ocrGen = 0
    private var ocrBusy = false
    private var ocrAgain = false
    private var lastShot = 0L
    private var lastOcrHash = 0
    private var bigImage = false // layar punya gambar besar: kemungkinan ada teks di dalam gambar (layar campuran)
    private var nodeRects: List<Rect> = emptyList()
    private var screenArea = 1L

    private val refreshRun = Runnable { refresh() }
    private var firstPending = 0L // kapan permintaan refresh pertama yang belum jalan masuk
    private var lastSig = 0 // sidik isi layar saat semua terjemahan sudah tampil

    private var srcCode = TranslateLanguage.ENGLISH
    private var srcName = "Inggris"
    private var dstCode = TranslateLanguage.INDONESIAN
    private var dstName = "Indonesia"
    private var hasLetter = Regex("[\\p{L}]{2,}")
    private var ocrOk = true // OCR bawaan hanya untuk huruf Latin

    fun start(say: (String) -> Unit) {
        if (active) return
        active = true
        val si = Prefs.liveSrc(svc)
        srcCode = Prefs.LANG_CODES[si]
        srcName = Prefs.LANGS[si]
        val di = Prefs.liveDst(svc)
        dstCode = Prefs.LANG_CODES[di]
        dstName = Prefs.LANGS[di]
        val cjkAr = srcCode in listOf("ar", "ja", "zh", "ko")
        hasLetter = if (cjkAr) Regex("\\p{L}") else Regex("[\\p{L}]{2,}")
        ocrOk = !cjkAr
        Models.missing(listOf(srcCode, dstCode)) { miss ->
            main.post {
                if (!active) return@post
                if (miss.isNotEmpty()) {
                    active = false
                    say(Models.hint(miss))
                } else begin(say)
            }
        }
    }

    private fun begin(say: (String) -> Unit) {
        val tr = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(srcCode)
                .setTargetLanguage(dstCode)
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
            if (!finished && active) {
                say("Mengunduh model terjemahan $srcName ke $dstName (sekitar 30 MB per bahasa). Mohon tunggu, butuh internet…")
                modelNotice(true) // status tetap di notifikasi sampai unduhan selesai
            }
        }
        main.postDelayed(downloadingNotice, 700)
        val t0 = android.os.SystemClock.elapsedRealtime()
        tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                finished = true
                main.removeCallbacks(downloadingNotice)
                modelNotice(false)
                if (!active) return@addOnSuccessListener
                ready = true
                val downloaded = android.os.SystemClock.elapsedRealtime() - t0 > 700
                say(
                    (if (downloaded) "Model selesai diunduh. " else "") +
                        "Live translate siap. Teks $srcName di layar akan diterjemahkan, layar tetap bisa digulir."
                )
                refresh()
            }
            .addOnFailureListener {
                finished = true
                main.removeCallbacks(downloadingNotice)
                modelNotice(false)
                say("Gagal mengunduh model terjemahan. Periksa koneksi internet lalu nyalakan Live lagi.")
                stop()
            }
    }

    /** Notifikasi tetap selama model diunduh, supaya status terlihat walau toast sudah hilang. */
    private fun modelNotice(on: Boolean) {
        try {
            val nm = svc.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!on) {
                nm.cancel(7301)
                return
            }
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel("live", "Live translate", NotificationManager.IMPORTANCE_LOW))
            }
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(svc, "live") else Notification.Builder(svc)
            b.setSmallIcon(R.drawable.ic_live)
                .setContentTitle("Mengunduh model terjemahan")
                .setContentText("$srcName ke $dstName, sekitar 30 MB per bahasa. Butuh internet.")
                .setOngoing(true)
                .setProgress(0, 0, true)
            nm.notify(7301, b.build())
        } catch (e: Exception) {
        }
    }

    fun stop() {
        modelNotice(false)
        active = false
        gen++
        ocrGen++
        nodeBoxes = emptyList()
        ocrBoxes = emptyList()
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
                // geser vertikal: terjemahan ikut bergeser bersama isi layar (tidak berkedip kosong); selisih dikoreksi setelah layar diam
                val dy = if (Build.VERSION.SDK_INT >= 28) e.scrollDeltaY else 0
                val dx = if (Build.VERSION.SDK_INT >= 28) e.scrollDeltaX else 0
                val cls = e.className?.toString() ?: ""
                val vertical = cls.contains("ScrollView") || cls.contains("RecyclerView") || cls.contains("ListView") || cls.contains("WebView")
                if (vertical && dx == 0 && dy != 0 && (nodeBoxes.isNotEmpty() || ocrBoxes.isNotEmpty())) {
                    shift(-dy)
                } else {
                    clearAll()
                }
                schedule(80)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                clearAll()
                schedule(100)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> schedule(110)
        }
    }

    /**
     * Jalankan refresh setelah layar diam sebentar. Bila layar terus berubah (animasi, video), refresh tetap dipaksa
     * jalan setelah 600 ms supaya terjemahan tidak tertunda terus.
     */
    private fun shift(dy: Int) {
        val h = svc.resources.displayMetrics.heightPixels
        fun mv(l: List<Box>) = l.mapNotNull { b ->
            val r = Rect(b.r)
            r.offset(0, dy)
            if (r.bottom < 0 || r.top > h) null else Box(r, b.text)
        }
        nodeBoxes = mv(nodeBoxes)
        ocrBoxes = mv(ocrBoxes)
        lastSig = 0
        lastOcrHash = 0
        gen++
        ocrGen++
        view?.setBoxes(nodeBoxes + ocrBoxes)
    }

    private fun clearAll() {
        nodeBoxes = emptyList()
        ocrBoxes = emptyList()
        lastSig = 0
        lastOcrHash = 0
        gen++
        ocrGen++
        view?.setBoxes(emptyList())
    }

    private fun push(n: List<Box>? = null, o: List<Box>? = null) {
        if (n != null) nodeBoxes = n
        if (o != null) ocrBoxes = o
        view?.setBoxes(nodeBoxes + ocrBoxes)
    }

    private fun schedule(ms: Long) {
        val now = android.os.SystemClock.uptimeMillis()
        if (firstPending == 0L) firstPending = now
        main.removeCallbacks(refreshRun)
        main.postDelayed(refreshRun, if (now - firstPending >= 350L) 0L else ms)
    }

    private fun refresh() {
        firstPending = 0L
        val tr = translator ?: return
        if (!active || !ready) return
        val root = svc.rootInActiveWindow ?: return
        val items = ArrayList<Box>()
        bigImage = false
        val dm = svc.resources.displayMetrics
        screenArea = dm.widthPixels.toLong() * dm.heightPixels
        try {
            if (root.packageName?.toString() == svc.packageName) return
            collect(root, items)
        } catch (e: Exception) {
            return
        }
        // isi layar sama dengan yang sudah tampil semua: tidak perlu dikerjakan lagi (mencegah kedip)
        var sig = items.size
        for (b in items) sig = 31 * sig + b.text.hashCode() + 17 * b.r.top + b.r.left
        if (sig == lastSig && sig != 0) return
        val myGen = ++gen
        // layar hampir tanpa teks node (gambar, game, WebView tertentu): baca lewat tangkapan layar + OCR (Android 11+)
        dedupe(items)
        nodeRects = items.map { it.r }
        if (ocrOk && (items.size < 4 || bigImage)) maybeOcr() else if (ocrBoxes.isNotEmpty()) push(o = emptyList())
        val shown = ArrayList<Box>()
        val pending = ArrayList<Box>()
        for (b in items) {
            val hit = cache[b.text]
            if (hit != null) shown.add(Box(b.r, hit)) else pending.add(b)
        }
        push(n = shown.toList())
        if (pending.isEmpty()) {
            lastSig = sig
            return
        }
        // teks yang sama di beberapa tempat hanya diterjemahkan sekali; yang paling atas di layar dikerjakan dulu
        val byText = LinkedHashMap<String, MutableList<Box>>()
        for (b in pending.sortedBy { it.r.top }) byText.getOrPut(b.text) { ArrayList() }.add(b)
        // HP lambat: paling banyak 30 teks baru per putaran (dari atas layar), sisanya menyusul di putaran berikutnya
        val batch = byText.entries.take(30)
        if (byText.size > 30) schedule(80)
        var left = batch.size
        for ((text, boxes) in batch) {
            tr.translate(text).addOnSuccessListener { out ->
                cache[text] = out
                if (gen == myGen && active) {
                    for (b in boxes) shown.add(Box(b.r, out))
                    push(n = shown.toList())
                }
            }.addOnCompleteListener {
                left--
                if (left == 0 && gen == myGen && active && shown.size == items.size) lastSig = sig
            }
        }
    }

    /** Tangkap layar lewat layanan aksesibilitas lalu OCR. Dibatasi 1 kali per detik (batas sistem). */
    private fun maybeOcr() {
        if (Build.VERSION.SDK_INT < 30) return
        val now = android.os.SystemClock.uptimeMillis()
        if (ocrBusy) {
            ocrAgain = true
            return
        }
        if (now - lastShot < 450L) {
            main.removeCallbacks(refreshRun)
            main.postDelayed(refreshRun, 450L - (now - lastShot) + 20L)
            return
        }
        ocrBusy = true
        lastShot = now
        val g = ocrGen
        // sembunyikan overlay sebentar supaya terjemahan sendiri tidak ikut terbaca OCR
        val hadBoxes = (nodeBoxes.isNotEmpty() || ocrBoxes.isNotEmpty())
        if (hadBoxes) view?.visibility = View.INVISIBLE
        main.postDelayed({
            try {
                svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                        view?.visibility = View.VISIBLE
                        val bmp = try {
                            val hb = r.hardwareBuffer
                            val b = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                            hb.close()
                            b
                        } catch (e: Exception) {
                            null
                        }
                        if (bmp == null || g != ocrGen || !active) return ocrDone()
                        // layar sama dengan OCR sebelumnya: pakai hasil lama
                        val small = Bitmap.createScaledBitmap(bmp, 24, 40, false)
                        var h = 7
                        for (y in 0 until 40) for (x in 0 until 24) h = 31 * h + small.getPixel(x, y)
                        small.recycle()
                        if (h == lastOcrHash) {
                            bmp.recycle()
                            return ocrDone()
                        }
                        lastOcrHash = h
                        Ocr.blocks(bmp) { res ->
                            bmp.recycle()
                            val list = res.getOrNull()
                            if (list == null || g != ocrGen || !active) return@blocks ocrDone()
                            translateOcr(list, g)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        view?.visibility = View.VISIBLE
                        ocrDone()
                    }
                })
            } catch (e: Exception) {
                view?.visibility = View.VISIBLE
                ocrDone()
            }
        }, if (hadBoxes) 50L else 0L)
    }

    private fun ocrDone() {
        ocrBusy = false
        if (ocrAgain) {
            ocrAgain = false
            schedule(120)
        }
    }

    private fun translateOcr(list: List<Pair<Rect, String>>, g: Int) {
        val tr = translator
        val items = list.filter { (r, t) ->
            t.length in 2..500 && hasLetter.containsMatchIn(t) && !isTargetText(t) &&
                r.width() > dp(24) && r.height() > dp(12)
        }.sortedBy { it.first.top }.take(40)
        // kotak yang sudah ada dari node tidak diulang
        val fresh = items.filter { (r, _) -> nodeRects.none { Rect.intersects(it, r) } }
        if (tr == null || fresh.isEmpty()) {
            push(o = emptyList())
            return ocrDone()
        }
        val out = ArrayList<Box>()
        var left = fresh.size
        for ((r, t) in fresh) {
            val hit = cache[t]
            val finish = {
                left--
                if (left == 0) ocrDone()
            }
            if (hit != null) {
                out.add(Box(r, hit))
                push(o = out.toList())
                finish()
            } else {
                tr.translate(t).addOnSuccessListener { o ->
                    cache[t] = o
                    if (g == ocrGen && active) {
                        out.add(Box(r, o))
                        push(o = out.toList())
                    }
                }.addOnCompleteListener { finish() }
            }
        }
    }

    /** Teks yang sudah berbahasa Indonesia tidak diterjemahkan lagi (hanya bila tujuan Indonesia dan sumber bukan Indonesia). */
    private fun isTargetText(t: String) = dstCode == "id" && srcCode != "id" && idWords.containsMatchIn(t)

    private val idWords = Regex("\\b(yang|dan|di|ke|dari|untuk|dengan|ini|itu|adalah|tidak|atau|pada)\\b", RegexOption.IGNORE_CASE)

    /** Buang kotak yang menumpuk di atas kotak lain (induk dan anak sama-sama bertuliskan teks); yang teksnya lebih panjang dipertahankan. */
    private fun dedupe(items: MutableList<Box>) {
        val keep = ArrayList<Box>()
        for (b in items.sortedByDescending { it.text.length }) {
            val area = b.r.width().toLong() * b.r.height()
            val overlap = keep.any { k ->
                val i = Rect()
                i.setIntersect(b.r, k.r) && i.width().toLong() * i.height() * 2 > area
            }
            if (!overlap) keep.add(b)
        }
        items.clear()
        items.addAll(keep)
    }

    private fun collect(n: AccessibilityNodeInfo, out: MutableList<Box>) {
        if (out.size >= 120) return
        if (!n.isVisibleToUser) return
        // kolom sandi dan isian pengguna tidak dibaca sama sekali
        if (n.isPassword || n.isEditable) return
        if (!bigImage && n.className?.toString() == "android.widget.ImageView") {
            val ir = Rect()
            n.getBoundsInScreen(ir)
            if (ir.width().toLong() * ir.height() * 100 >= screenArea * 12) bigImage = true
        }
        val t = n.text?.toString()?.trim()
        if (!t.isNullOrEmpty() && t.length in 2..400 && hasLetter.containsMatchIn(t) && !isTargetText(t)) {
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
