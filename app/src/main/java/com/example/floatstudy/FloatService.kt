package com.example.floatstudy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import kotlin.math.abs

class FloatService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.floatstudy.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"

        private const val TAB_BROWSER = 4
        private val LANGS = listOf(
            "Indonesia", "Inggris", "Arab", "Jepang", "Mandarin",
            "Korea", "Jerman", "Prancis", "Spanyol"
        )
        private const val TRANSLATE_SYS =
            "Kamu penerjemah profesional. Keluarkan hanya hasil terjemahan, tanpa penjelasan tambahan."
    }

    private val c: Context get() = this
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var wm: WindowManager
    private var grab: ScreenGrabber? = null

    private var bubble: TextView? = null
    private var bubbleBg: GradientDrawable? = null
    private var panel: LinearLayout? = null
    private var selector: SelectView? = null
    private var web: WebView? = null
    private var selectTab: ((Int) -> Unit)? = null
    private var langIdx = 0

    // auto clicker
    private var target: TextView? = null
    private var targetLp: WindowManager.LayoutParams? = null
    private var clicking = false
    private var clicksDone = 0
    private var maxClicks = 0
    private var intervalMs = 1000L

    private val clickRunner = object : Runnable {
        override fun run() {
            if (!clicking) return
            val svc = ClickService.instance
            val t = target
            if (svc == null || t == null) {
                stopClicking()
                return
            }
            val loc = IntArray(2)
            t.getLocationOnScreen(loc)
            svc.tap(loc[0] + t.width / 2f, loc[1] + t.height / 2f)
            clicksDone++
            if (maxClicks > 0 && clicksDone >= maxClicks) {
                stopClicking()
                return
            }
            ui.postDelayed(this, intervalMs)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- siklus hidup ----------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (bubble != null) return START_NOT_STICKY

        startAsForeground()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data != null) {
            val g = ScreenGrabber(this, ui)
            val (w, h) = realSize()
            g.start(code, data, w, h, resources.displayMetrics.densityDpi) { stopSelf() }
            grab = g
        }
        showBubble()
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("float", "FloatStudy", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, FloatService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "float")
            .setContentTitle("FloatStudy aktif")
            .setContentText("Bubble berjalan. Tekan Hentikan untuk menutup.")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_delete, "Hentikan", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        clicking = false
        if (::wm.isInitialized) {
            closePanel()
            listOf<View?>(selector, target, bubble).forEach { v ->
                v?.let {
                    try {
                        wm.removeView(it)
                    } catch (e: Exception) {
                    }
                }
            }
        }
        selector = null
        target = null
        bubble = null
        grab?.release()
        grab = null
        super.onDestroy()
    }

    private fun realSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

    // ---------- util tampilan ----------

    private fun rounded(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
    }

    private fun button(label: String, onClick: () -> Unit) = Button(c).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun resultText() = TextView(c).apply {
        textSize = 14f
        setTextColor(Color.BLACK)
        setPadding(dp(4), dp(8), dp(4), dp(8))
        setTextIsSelectable(true)
    }

    private fun vbox() = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

    private fun row(vararg v: View) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        v.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    private fun addOut(col: LinearLayout, out: TextView, h: Int = 200) {
        col.addView(
            ScrollView(c).apply { addView(out) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(h))
        )
    }

    private fun makeDraggable(
        handle: View,
        moving: View,
        lp: WindowManager.LayoutParams,
        onTap: (() -> Unit)? = null
    ) {
        handle.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0
            var sy = 0
            var tx = 0f
            var ty = 0f
            var moved = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - tx).toInt()
                        val dy = (e.rawY - ty).toInt()
                        if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                        if (moved) {
                            lp.x = sx + dx
                            lp.y = sy + dy
                            wm.updateViewLayout(moving, lp)
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) onTap?.invoke()
                }
                return true
            }
        })
    }

    private fun toJpegB64(src: Bitmap): String {
        val scale = minOf(1f, 1400f / maxOf(src.width, src.height))
        val bmp = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                src, (src.width * scale).toInt(), (src.height * scale).toInt(), true
            )
        } else src
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, bos)
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    private fun copyText(t: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("FloatStudy", t))
        Toast.makeText(c, "Teks disalin", Toast.LENGTH_SHORT).show()
    }

    // ---------- bubble ----------

    private fun showBubble() {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xE62563EB.toInt())
        }
        val b = TextView(c).apply {
            text = "AI"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = bg
        }
        val size = dp(52)
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(200)
        }
        makeDraggable(b, b, lp) {
            if (clicking) stopClicking() else togglePanel()
        }
        wm.addView(b, lp)
        bubble = b
        bubbleBg = bg
    }

    private fun updateBubble() {
        bubble?.text = if (clicking) "■" else "AI"
        bubbleBg?.setColor(if (clicking) 0xE6DC2626.toInt() else 0xE62563EB.toInt())
    }

    private fun setOverlayVisible(v: Boolean) {
        val vis = if (v) View.VISIBLE else View.INVISIBLE
        bubble?.visibility = vis
        panel?.visibility = vis
        target?.visibility = vis
    }

    // ---------- ambil area layar ----------

    private fun withRegion(full: Boolean, out: TextView, next: (Bitmap) -> Unit) {
        val g = grab
        if (g == null || !g.active) {
            out.text = "Izin tangkap layar belum aktif. Hentikan lalu mulai lagi dari aplikasi."
            return
        }
        out.text = if (full) "Mengambil layar…" else "Lingkari area di layar…"
        pickRegion(full) { bmp ->
            if (bmp == null) out.text = "Dibatalkan atau gagal mengambil layar."
            else next(bmp)
        }
    }

    private fun pickRegion(full: Boolean, cb: (Bitmap?) -> Unit) {
        val g = grab
        if (g == null) {
            cb(null)
            return
        }
        val (w, h) = realSize()
        g.resizeIfNeeded(w, h)
        setOverlayVisible(false)

        fun finish(r: Bitmap?) {
            setOverlayVisible(true)
            cb(r)
        }

        fun attempt(n: Int) {
            val shot = g.latest()
            if (shot == null) {
                if (n < 3) ui.postDelayed({ attempt(n + 1) }, 400) else finish(null)
                return
            }
            if (full) finish(shot) else showSelector(shot) { crop -> finish(crop) }
        }

        // beri waktu agar bubble dan panel hilang dari frame
        ui.postDelayed({ attempt(0) }, 450)
    }

    private fun showSelector(shot: Bitmap, done: (Bitmap?) -> Unit) {
        val v = SelectView(c, shot) { crop ->
            ui.post {
                selector?.let {
                    try {
                        wm.removeView(it)
                    } catch (e: Exception) {
                    }
                }
                selector = null
                done(crop)
            }
        }
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(v, lp)
        selector = v
    }

    // ---------- panel ----------

    private fun togglePanel() {
        if (panel == null) openPanel() else closePanel()
    }

    private fun closePanel() {
        panel?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        panel = null
        web?.destroy()
        web = null
        selectTab = null
    }

    private fun openPanel() {
        val dm = resources.displayMetrics
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 16)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            elevation = dp(8).toFloat()
        }

        val handle = TextView(c).apply {
            text = "≡"
            textSize = 22f
            setTextColor(Color.GRAY)
            setPadding(dp(8), dp(2), dp(10), dp(2))
        }
        val tabRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        val tabScroll = HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabRow)
        }
        val close = TextView(c).apply {
            text = "✕"
            textSize = 18f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { closePanel() }
        }
        val bar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(handle)
            addView(tabScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(close)
        }

        val names = listOf("Tanya AI", "Salin", "Terjemah", "Gambar", "Browser", "Chat", "Hitung", "Klik")
        val views = listOf(
            aiView(), copyView(), translateView(), imageView(),
            browserView(), chatView(), calcView(), clickView()
        )
        val tabs = names.map { TextView(c) }
        val content = FrameLayout(c)

        fun select(i: Int) {
            views.forEachIndexed { idx, v ->
                v.visibility = if (idx == i) View.VISIBLE else View.GONE
            }
            tabs.forEachIndexed { idx, t ->
                t.setTextColor(if (idx == i) Color.parseColor("#2563EB") else Color.GRAY)
                t.typeface = if (idx == i) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
        }
        tabs.forEachIndexed { i, t ->
            t.text = names[i]
            t.textSize = 15f
            t.setPadding(dp(10), dp(8), dp(10), dp(8))
            t.setOnClickListener { select(i) }
            tabRow.addView(t)
        }
        views.forEach { content.addView(it) }
        root.addView(bar)
        root.addView(content)
        select(0)
        selectTab = { i -> select(i) }

        val width = minOf(dm.widthPixels - dp(16), dp(380))
        val lp = WindowManager.LayoutParams(
            width, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (dm.widthPixels - width) / 2
            y = dp(48)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        makeDraggable(handle, root, lp)
        wm.addView(root, lp)
        panel = root
    }

    // ---------- AI ----------

    private fun ask(q: String, img: String?, out: TextView, system: String? = null) {
        out.text = "Menunggu jawaban…"
        AiClient.ask(c, q, img, system ?: AiClient.SYSTEM) { r ->
            ui.post { out.text = r.getOrElse { "Error: ${it.message}" } }
        }
    }

    // Tab 0: lingkari area / layar penuh lalu tanya AI
    private fun aiView(): View {
        val col = vbox()
        val note = EditText(c).apply {
            hint = "Catatan (opsional), mis. jelaskan nomor 3"
            textSize = 14f
        }
        val out = resultText()
        val prompt = { note.text.toString().ifBlank { "Jawab soal pada gambar." } }
        col.addView(note)
        col.addView(
            row(
                button("Lingkari area") {
                    withRegion(false, out) { ask(prompt(), toJpegB64(it), out) }
                },
                button("Layar penuh") {
                    withRegion(true, out) { ask(prompt(), toJpegB64(it), out) }
                }
            )
        )
        addOut(col, out)
        return col
    }

    // Tab 1: lingkari area, baca teks (OCR) dan salin
    private fun copyView(): View {
        val col = vbox()
        val out = resultText()
        val aiOut = resultText()
        var text = ""

        val run: (Bitmap) -> Unit = { bmp ->
            out.text = "Membaca teks…"
            Ocr.read(bmp) { r ->
                ui.post {
                    r.onSuccess { t ->
                        text = t
                        if (t.isBlank()) {
                            out.text = "Tidak ada teks terbaca."
                        } else {
                            out.text = t
                            copyText(t)
                        }
                    }.onFailure { out.text = "Error: ${it.message}" }
                }
            }
        }
        col.addView(
            row(
                button("Lingkari & salin") { withRegion(false, out, run) },
                button("Layar penuh") { withRegion(true, out, run) }
            )
        )
        addOut(col, out, 130)
        col.addView(button("Jawab teks ini dengan AI") {
            if (text.isNotBlank()) ask(text, null, aiOut) else aiOut.text = "Belum ada teks."
        })
        addOut(col, aiOut, 120)
        return col
    }

    // Tab 2: terjemah teks atau area layar
    private fun translateView(): View {
        val col = vbox()
        val input = EditText(c).apply {
            hint = "Teks yang ingin diterjemahkan"
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 4
        }
        val out = resultText()
        val lang = button("Ke: ${LANGS[langIdx]}") {}
        lang.setOnClickListener {
            langIdx = (langIdx + 1) % LANGS.size
            lang.text = "Ke: ${LANGS[langIdx]}"
        }
        col.addView(input)
        col.addView(lang)
        col.addView(
            row(
                button("Terjemahkan teks") {
                    val t = input.text.toString().trim()
                    if (t.isEmpty()) out.text = "Tulis teks dulu."
                    else ask("Terjemahkan ke bahasa ${LANGS[langIdx]}:\n\n$t", null, out, TRANSLATE_SYS)
                },
                button("Lingkari & terjemah") {
                    withRegion(false, out) {
                        ask(
                            "Baca semua teks pada gambar, lalu terjemahkan ke bahasa ${LANGS[langIdx]}.",
                            toJpegB64(it), out, TRANSLATE_SYS
                        )
                    }
                }
            )
        )
        addOut(col, out, 170)
        return col
    }

    // Tab 3: cari gambar (Google Lens) atau cari teks hasil OCR
    private fun imageView(): View {
        val col = vbox()
        val out = resultText()
        out.text = "Lingkari bagian layar, lalu cari dengan Google Lens atau cari teksnya di Google."
        col.addView(button("Lingkari & cari gambar (Lens)") {
            withRegion(false, out) { searchWithLens(it, out) }
        })
        col.addView(button("Lingkari & cari teksnya di Google") {
            withRegion(false, out) { bmp ->
                out.text = "Membaca teks…"
                Ocr.read(bmp) { r ->
                    ui.post {
                        r.onSuccess { t ->
                            val q = t.replace('\n', ' ').trim().take(200)
                            if (q.isBlank()) {
                                out.text = "Tidak ada teks terbaca."
                            } else {
                                out.text = "Mencari: $q"
                                browse("https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8"))
                            }
                        }.onFailure { out.text = "Error: ${it.message}" }
                    }
                }
            }
        })
        addOut(col, out, 110)
        return col
    }

    private fun searchWithLens(bmp: Bitmap, out: TextView) {
        try {
            val dir = File(cacheDir, "shared").apply { mkdirs() }
            val f = File(dir, "crop.png")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(c, "$packageName.files", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val lens = Intent(send).setClassName(
                "com.google.android.googlequicksearchbox",
                "com.google.android.apps.search.lens.LensShareEntryPointActivity"
            )
            try {
                startActivity(lens)
            } catch (e: Exception) {
                startActivity(
                    Intent.createChooser(send, "Cari gambar dengan")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            closePanel()
        } catch (e: Exception) {
            out.text = "Gagal: ${e.message}"
        }
    }

    // Tab 4: browser mini
    private fun toUrl(t: String): String = when {
        t.startsWith("http://") || t.startsWith("https://") -> t
        !t.contains(' ') && t.contains('.') -> "https://$t"
        else -> "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8")
    }

    private fun browse(url: String) {
        selectTab?.invoke(TAB_BROWSER)
        web?.loadUrl(url)
    }

    private fun browserView(): View {
        val col = vbox()
        val url = EditText(c).apply {
            hint = "Cari atau ketik alamat"
            textSize = 14f
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
        }
        val w = WebView(c).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, u: String?) {
                    if (!url.hasFocus() && u != null) url.setText(u)
                }
            }
            loadUrl("https://www.google.com")
        }
        fun go() {
            val t = url.text.toString().trim()
            if (t.isNotEmpty()) w.loadUrl(toUrl(t))
            w.requestFocus()
        }
        url.setOnEditorActionListener { _, _, _ ->
            go()
            true
        }
        val back = Button(c).apply {
            text = "◀"
            minWidth = 0
            minimumWidth = 0
            setOnClickListener { if (w.canGoBack()) w.goBack() }
        }
        val goBtn = Button(c).apply {
            text = "Buka"
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setOnClickListener { go() }
        }
        val bar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(back)
            addView(url, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(goBtn)
        }
        col.addView(bar)
        col.addView(w, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)))
        web = w
        return col
    }

    // Tab 5: tanya AI lewat teks
    private fun chatView(): View {
        val col = vbox()
        val input = EditText(c).apply {
            hint = "Tulis pertanyaan"
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 4
        }
        val out = resultText()
        col.addView(input)
        col.addView(button("Tanya") {
            val q = input.text.toString().trim()
            if (q.isEmpty()) out.text = "Tulis pertanyaan dulu." else ask(q, null, out)
        })
        addOut(col, out)
        return col
    }

    // Tab 6: kalkulator
    private fun calcView(): View {
        val col = vbox()
        val expr = TextView(c).apply {
            textSize = 20f
            gravity = Gravity.END
            setTextColor(Color.DKGRAY)
            minHeight = dp(32)
        }
        val res = TextView(c).apply {
            text = "0"
            textSize = 28f
            gravity = Gravity.END
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
        }
        col.addView(expr)
        col.addView(res)

        fun press(k: String) {
            when (k) {
                "C" -> {
                    expr.text = ""
                    res.text = "0"
                }
                "⌫" -> expr.text = expr.text.toString().dropLast(1)
                "=" -> res.text = try {
                    Calc.format(Calc.eval(expr.text.toString()))
                } catch (e: Exception) {
                    "Error"
                }
                "√" -> expr.text = expr.text.toString() + "√("
                else -> expr.text = expr.text.toString() + k
            }
        }

        val rows = listOf(
            listOf("C", "(", ")", "⌫"),
            listOf("7", "8", "9", "÷"),
            listOf("4", "5", "6", "×"),
            listOf("1", "2", "3", "-"),
            listOf("0", ".", "^", "+"),
            listOf("√", "%", "π", "=")
        )
        for (r in rows) {
            val line = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in r) {
                line.addView(Button(c).apply {
                    text = k
                    textSize = 16f
                    isAllCaps = false
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(0, 0, 0, 0)
                    setOnClickListener { press(k) }
                }, LinearLayout.LayoutParams(0, dp(44), 1f))
            }
            col.addView(line)
        }
        return col
    }

    // Tab 7: auto clicker
    private fun clickView(): View {
        val col = vbox()
        val status = resultText()
        val interval = EditText(c).apply {
            hint = "Jeda antar ketukan (ms)"
            setText("1000")
            textSize = 14f
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val count = EditText(c).apply {
            hint = "Jumlah ketukan (0 = tanpa batas)"
            setText("0")
            textSize = 14f
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        fun refresh() {
            status.text = when {
                ClickService.instance == null ->
                    "Layanan aksesibilitas belum aktif. Tekan tombol pengaturan, pilih FloatStudy Auto Clicker, lalu aktifkan."
                target == null ->
                    "Tekan 'Pasang / hapus target', lalu geser tanda + ke titik yang ingin diketuk."
                else ->
                    "Target siap. Tekan Mulai. Untuk berhenti, ketuk bubble merah."
            }
        }

        col.addView(status)
        col.addView(button("Buka pengaturan aksesibilitas") {
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        })
        col.addView(button("Pasang / hapus target") {
            if (target == null) showTarget() else removeTarget()
            refresh()
        })
        col.addView(interval)
        col.addView(count)
        col.addView(button("Mulai") {
            val ms = (interval.text.toString().toLongOrNull() ?: 1000L).coerceAtLeast(50L)
            val n = count.text.toString().toIntOrNull() ?: 0
            if (!startClicking(ms, n)) refresh()
        })
        refresh()
        return col
    }

    private fun showTarget() {
        if (target != null) return
        val size = dp(44)
        val t = TextView(c).apply {
            text = "+"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x66DC2626)
                setStroke(dp(2), Color.parseColor("#DC2626"))
            }
        }
        val (sw, sh) = realSize()
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = sw / 2 - size / 2
            y = sh / 2 - size / 2
        }
        makeDraggable(t, t, lp)
        wm.addView(t, lp)
        target = t
        targetLp = lp
    }

    private fun removeTarget() {
        if (clicking) stopClicking()
        target?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        target = null
        targetLp = null
    }

    private fun startClicking(ms: Long, n: Int): Boolean {
        val t = target ?: return false
        val lp = targetLp ?: return false
        if (ClickService.instance == null) return false
        clicking = true
        clicksDone = 0
        maxClicks = n
        intervalMs = ms
        // saat berjalan, tanda target tembus sentuhan agar ketukan sampai ke aplikasi di bawahnya
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        wm.updateViewLayout(t, lp)
        closePanel()
        updateBubble()
        ui.postDelayed(clickRunner, 400)
        return true
    }

    private fun stopClicking() {
        clicking = false
        ui.removeCallbacks(clickRunner)
        val t = target
        val lp = targetLp
        if (t != null && lp != null) {
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            try {
                wm.updateViewLayout(t, lp)
            } catch (e: Exception) {
            }
        }
        updateBubble()
    }
}
