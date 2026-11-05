package com.example.floatstudy

import android.app.ActivityOptions
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
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.util.Base64
import android.util.DisplayMetrics
import android.util.TypedValue
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class FloatService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.floatstudy.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"

        private const val T_AI = 0
        private const val T_COPY = 1
        private const val T_TR = 2
        private const val T_IMG = 3
        private const val T_BROWSER = 4
        private const val T_CHAT = 5
        private const val T_CALC = 6
        private const val T_CLICK = 7
        private const val T_APPS = 8
        private const val T_SET = 9

        private val TAB_NAMES = listOf(
            "Tanya AI", "Salin", "Terjemah", "Gambar", "Browser",
            "Chat", "Hitung", "Klik", "Apps", "Atur"
        )
        private val TAB_ICONS = listOf("✦", "⎘", "文", "◎", "🌐", "💬", "🧮", "👆", "▦", "⚙")

        // alat yang langsung membuka lingkaran pilih area dulu, jendela baru muncul sesudahnya
        private val CIRCLE_FIRST = setOf(T_AI, T_COPY, T_TR, T_IMG)

        private val LANGS = listOf(
            "Indonesia", "Inggris", "Arab", "Jepang", "Mandarin",
            "Korea", "Jerman", "Prancis", "Spanyol"
        )
        private const val TRANSLATE_SYS =
            "Kamu penerjemah profesional. Keluarkan hanya hasil terjemahan, tanpa penjelasan tambahan."

        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private class AppItem(val pkg: String, val label: String, val icon: Drawable)

    private val c: Context get() = this
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var wm: WindowManager
    private var grab: ScreenGrabber? = null

    // bubble dan menu lingkar
    private var bubble: TextView? = null
    private var bubbleBg: GradientDrawable? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var menu: FrameLayout? = null

    // jendela alat. Dibuat sekali dan disimpan: menutup (✕) hanya menyembunyikan,
    // jadi riwayat browser, hasil AI, dan isian tidak hilang.
    private var panel: LinearLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var panelAttached = false
    private var selectTab: ((Int) -> Unit)? = null
    private var selector: SelectView? = null
    private var web: WebView? = null
    private var langIdx = 0
    private var ocrText = ""
    private var allApps: List<AppItem>? = null

    private lateinit var aiNote: EditText
    private lateinit var aiOut: TextView
    private lateinit var copyOut: TextView
    private lateinit var copyAiOut: TextView
    private lateinit var trOut: TextView
    private lateinit var imgOut: TextView

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
    private fun dpf(v: Float) = (v * resources.displayMetrics.density).toInt()
    private fun pref(s: Setting) = Prefs.get(c, s)

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
            hideMenu()
            hidePanel()
            listOf<View?>(selector, target, bubble).forEach { v ->
                v?.let {
                    try {
                        wm.removeView(it)
                    } catch (e: Exception) {
                    }
                }
            }
        }
        web?.destroy()
        web = null
        panel = null
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
        v.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP, 1f)) }
    }

    /** Kotak hasil yang mengisi sisa tinggi jendela, jadi ikut berubah saat jendela diubah ukurannya. */
    private fun addOut(col: LinearLayout, out: TextView, weight: Float = 1f) {
        col.addView(
            ScrollView(c).apply { addView(out) },
            LinearLayout.LayoutParams(MATCH, 0, weight)
        )
    }

    /** Isi tab yang panjang dibungkus ScrollView supaya tetap terpakai di jendela kecil. */
    private fun scrolled(col: LinearLayout): View =
        ScrollView(c).apply { addView(col, MATCH, WRAP) }

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

    /** Pegangan ◢ di pojok kanan bawah untuk mengubah ukuran jendela dengan jari. */
    private fun makeResizable(grip: View, root: View, lp: WindowManager.LayoutParams) {
        grip.setOnTouchListener(object : View.OnTouchListener {
            var sw = 0
            var sh = 0
            var tx = 0f
            var ty = 0f

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sw = lp.width; sh = lp.height; tx = e.rawX; ty = e.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dm = resources.displayMetrics
                        lp.width = (sw + (e.rawX - tx)).toInt()
                            .coerceIn(dp(Setting.WIN_W.min), minOf(dp(Setting.WIN_W.max), dm.widthPixels))
                        lp.height = (sh + (e.rawY - ty)).toInt()
                            .coerceIn(dp(Setting.WIN_H.min), minOf(dp(Setting.WIN_H.max), dm.heightPixels))
                        wm.updateViewLayout(root, lp)
                    }
                    MotionEvent.ACTION_UP -> {
                        val d = resources.displayMetrics.density
                        Prefs.set(c, Setting.WIN_W, (lp.width / d).toInt())
                        Prefs.set(c, Setting.WIN_H, (lp.height / d).toInt())
                    }
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

    private fun toast(t: String) = Toast.makeText(c, t, Toast.LENGTH_SHORT).show()

    // ---------- bubble ----------

    private fun showBubble() {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Prefs.bubbleColor(c))
        }
        val b = TextView(c).apply {
            text = "AI"
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = bg
        }
        val size = dp(pref(Setting.BUBBLE_SIZE))
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
            when {
                clicking -> stopClicking()
                menu != null -> hideMenu()
                else -> showMenu()
            }
        }
        wm.addView(b, lp)
        bubble = b
        bubbleBg = bg
        bubbleLp = lp
        applyBubble()
    }

    /** Terapkan ukuran, warna, dan opasitas bubble dari pengaturan. */
    private fun applyBubble() {
        val b = bubble ?: return
        val lp = bubbleLp ?: return
        val sizeDp = pref(Setting.BUBBLE_SIZE)
        lp.width = dp(sizeDp)
        lp.height = dp(sizeDp)
        b.alpha = pref(Setting.BUBBLE_ALPHA) / 100f
        b.text = if (clicking) "■" else "AI"
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp * 0.34f)
        bubbleBg?.setColor(if (clicking) 0xFFDC2626.toInt() else Prefs.bubbleColor(c))
        try {
            wm.updateViewLayout(b, lp)
        } catch (e: Exception) {
        }
    }

    private fun updateBubble() = applyBubble()

    private fun setOverlayVisible(v: Boolean) {
        val vis = if (v) View.VISIBLE else View.INVISIBLE
        bubble?.visibility = vis
        panel?.visibility = vis
        target?.visibility = vis
    }

    // ---------- menu lingkar (muncul saat bubble diketuk) ----------

    private fun showMenu() {
        if (menu != null) return
        val b = bubble ?: return
        val root = FrameLayout(c).apply {
            setBackgroundColor(0x33000000)
            setOnClickListener { hideMenu() }
        }
        val lp = WindowManager.LayoutParams(
            MATCH, MATCH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(root, lp)
        menu = root
        // posisi bubble di layar baru bisa dibaca setelah jendela menu terukur
        root.post { if (menu === root) layoutMenu(root, b) }
    }

    private fun layoutMenu(root: FrameLayout, b: View) {
        val bl = IntArray(2)
        val rl = IntArray(2)
        b.getLocationOnScreen(bl)
        root.getLocationOnScreen(rl)
        val bx = bl[0] - rl[0] + b.width / 2f
        val by = bl[1] - rl[1] + b.height / 2f

        val scale = pref(Setting.MENU_SIZE) / 100f
        val item = dpf(52f * scale)
        val radius = dpf(100f * scale)
        val edge = radius + item / 2 + dp(4)
        // cincin digeser masuk supaya semua tombol tetap terlihat di tepi layar
        val cx = bx.coerceIn(edge.toFloat(), maxOf(edge, root.width - edge).toFloat())
        val cy = by.coerceIn(edge.toFloat(), maxOf(edge, root.height - edge).toFloat())
        val alpha = pref(Setting.MENU_ALPHA) / 100f
        val color = Prefs.bubbleColor(c)

        fun place(v: View, x: Float, y: Float, size: Int) {
            root.addView(v, FrameLayout.LayoutParams(size, size).apply {
                leftMargin = (x - size / 2f).toInt()
                topMargin = (y - size / 2f).toInt()
            })
        }

        val n = TAB_NAMES.size
        for (i in 0 until n) {
            val ang = Math.toRadians(-90.0 + i * 360.0 / n)
            val x = cx + (radius * cos(ang)).toFloat()
            val y = cy + (radius * sin(ang)).toFloat()
            val cell = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(dp(1), Color.WHITE)
                }
                this.alpha = alpha
                addView(TextView(c).apply {
                    text = TAB_ICONS[i]
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f * scale)
                })
                addView(TextView(c).apply {
                    text = TAB_NAMES[i]
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 9f * scale)
                })
                setOnClickListener { onMenuPick(i) }
                scaleX = 0f
                scaleY = 0f
                animate().scaleX(1f).scaleY(1f).setStartDelay(i * 12L).setDuration(150).start()
            }
            place(cell, x, y, item)
        }

        // tombol tengah menutup menu
        val closeSize = dp(pref(Setting.BUBBLE_SIZE))
        val closeBtn = TextView(c).apply {
            text = "✕"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            this.alpha = alpha
            setOnClickListener { hideMenu() }
        }
        place(closeBtn, cx, cy, closeSize)
    }

    private fun hideMenu() {
        menu?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        menu = null
    }

    private fun onMenuPick(tab: Int) {
        hideMenu()
        if (tab in CIRCLE_FIRST) circleFirst(tab) else openPanel(tab)
    }

    /**
     * Alat yang mengambil soal dari layar: tampilkan dulu lingkaran pilih area
     * (seperti circle to search). Jendela alat baru muncul setelah area dipilih.
     */
    private fun circleFirst(tab: Int) {
        val g = grab
        if (g == null || !g.active) {
            openPanel(tab)
            outFor(tab)?.text =
                "Izin tangkap layar belum aktif. Hentikan lalu mulai lagi dari aplikasi."
            return
        }
        pickRegion(false) { bmp ->
            if (bmp == null) {
                toast("Dibatalkan")
                return@pickRegion
            }
            when (tab) {
                T_AI -> {
                    openPanel(T_AI)
                    runAi(bmp)
                }
                T_COPY -> {
                    openPanel(T_COPY)
                    runCopy(bmp)
                }
                T_TR -> {
                    openPanel(T_TR)
                    runTranslate(bmp)
                }
                T_IMG -> searchWithLens(bmp) { msg ->
                    openPanel(T_IMG)
                    imgOut.text = msg
                }
            }
        }
    }

    private fun outFor(tab: Int): TextView? {
        ensurePanel()
        return when (tab) {
            T_AI -> aiOut
            T_COPY -> copyOut
            T_TR -> trOut
            T_IMG -> imgOut
            else -> null
        }
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

        // beri waktu agar bubble, menu, dan jendela hilang dari frame
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
            MATCH, MATCH,
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

    // ---------- jendela alat ----------

    private fun panelW() = minOf(dp(pref(Setting.WIN_W)), resources.displayMetrics.widthPixels - dp(8))
    private fun panelH() = minOf(dp(pref(Setting.WIN_H)), resources.displayMetrics.heightPixels - dp(48))

    /** Tampilkan jendela (dibuat sekali, sisanya hanya ditampilkan lagi) dan pilih tab. */
    private fun openPanel(tab: Int) {
        val root = ensurePanel()
        val lp = panelLp ?: return
        if (!panelAttached) {
            try {
                wm.addView(root, lp)
                panelAttached = true
                web?.onResume()
            } catch (e: Exception) {
                toast("Gagal membuka jendela: ${e.message}")
                return
            }
        }
        root.visibility = View.VISIBLE
        selectTab?.invoke(tab)
    }

    /** Tombol ✕: sembunyikan saja. Isi jendela tidak dihapus. */
    private fun hidePanel() {
        val p = panel ?: return
        if (!panelAttached) return
        try {
            wm.removeView(p)
        } catch (e: Exception) {
        }
        panelAttached = false
        web?.onPause()
    }

    private fun applyPanel() {
        val p = panel ?: return
        val lp = panelLp ?: return
        lp.width = panelW()
        lp.height = panelH()
        p.alpha = pref(Setting.WIN_ALPHA) / 100f
        if (panelAttached) {
            try {
                wm.updateViewLayout(p, lp)
            } catch (e: Exception) {
            }
        }
    }

    private fun ensurePanel(): LinearLayout {
        panel?.let { return it }

        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 16)
            setPadding(dp(8), dp(8), dp(8), dp(4))
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
            setOnClickListener { hidePanel() }
        }
        val bar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(handle)
            addView(tabScroll, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(close)
        }

        val views = listOf(
            aiView(), copyView(), translateView(), imageView(),
            browserView(), chatView(), calcView(), clickView(),
            appsView(), settingsView()
        )
        val tabs = TAB_NAMES.map { TextView(c) }
        val content = FrameLayout(c)
        val accent = Color.parseColor("#2563EB")

        fun select(i: Int) {
            views.forEachIndexed { idx, v ->
                v.visibility = if (idx == i) View.VISIBLE else View.GONE
            }
            tabs.forEachIndexed { idx, t ->
                t.setTextColor(if (idx == i) accent else Color.GRAY)
                t.typeface = if (idx == i) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
        }
        tabs.forEachIndexed { i, t ->
            t.text = TAB_NAMES[i]
            t.textSize = 15f
            t.setPadding(dp(10), dp(8), dp(10), dp(8))
            t.setOnClickListener { select(i) }
            tabRow.addView(t)
        }
        views.forEach { content.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)) }

        val grip = TextView(c).apply {
            text = "◢"
            textSize = 16f
            setTextColor(Color.GRAY)
            setPadding(dp(24), 0, dp(2), 0)
        }
        val bottom = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(grip)
        }

        root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(bottom, LinearLayout.LayoutParams(MATCH, WRAP))
        select(0)
        selectTab = { i -> select(i) }

        val dm = resources.displayMetrics
        val w = panelW()
        val lp = WindowManager.LayoutParams(
            w, panelH(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (dm.widthPixels - w) / 2
            y = dp(48)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        makeDraggable(handle, root, lp)
        makeResizable(grip, root, lp)
        root.alpha = pref(Setting.WIN_ALPHA) / 100f
        panel = root
        panelLp = lp
        return root
    }

    // ---------- AI ----------

    private fun ask(q: String, img: String?, out: TextView, system: String? = null) {
        out.text = "Menunggu jawaban…"
        AiClient.ask(c, q, img, system ?: AiClient.SYSTEM) { r ->
            ui.post { out.text = r.getOrElse { "Error: ${it.message}" } }
        }
    }

    private fun aiPrompt() = aiNote.text.toString().ifBlank { "Jawab soal pada gambar." }

    private fun runAi(bmp: Bitmap) = ask(aiPrompt(), toJpegB64(bmp), aiOut)

    private fun runCopy(bmp: Bitmap) {
        copyOut.text = "Membaca teks…"
        Ocr.read(bmp) { r ->
            ui.post {
                r.onSuccess { t ->
                    ocrText = t
                    if (t.isBlank()) {
                        copyOut.text = "Tidak ada teks terbaca."
                    } else {
                        copyOut.text = t
                        copyText(t)
                    }
                }.onFailure { copyOut.text = "Error: ${it.message}" }
            }
        }
    }

    private fun runTranslate(bmp: Bitmap) = ask(
        "Baca semua teks pada gambar, lalu terjemahkan ke bahasa ${LANGS[langIdx]}.",
        toJpegB64(bmp), trOut, TRANSLATE_SYS
    )

    // Tab 0: lingkari area / layar penuh lalu tanya AI
    private fun aiView(): View {
        val col = vbox()
        aiNote = EditText(c).apply {
            hint = "Catatan (opsional), mis. jelaskan nomor 3"
            textSize = 14f
        }
        aiOut = resultText()
        col.addView(aiNote)
        col.addView(
            row(
                button("Lingkari area") {
                    withRegion(false, aiOut) { runAi(it) }
                },
                button("Layar penuh") {
                    withRegion(true, aiOut) { runAi(it) }
                }
            )
        )
        addOut(col, aiOut)
        return col
    }

    // Tab 1: lingkari area, baca teks (OCR) dan salin
    private fun copyView(): View {
        val col = vbox()
        copyOut = resultText()
        copyAiOut = resultText()
        col.addView(
            row(
                button("Lingkari & salin") { withRegion(false, copyOut) { runCopy(it) } },
                button("Layar penuh") { withRegion(true, copyOut) { runCopy(it) } }
            )
        )
        addOut(col, copyOut)
        col.addView(button("Jawab teks ini dengan AI") {
            if (ocrText.isNotBlank()) ask(ocrText, null, copyAiOut) else copyAiOut.text = "Belum ada teks."
        })
        addOut(col, copyAiOut)
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
        trOut = resultText()
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
                    if (t.isEmpty()) trOut.text = "Tulis teks dulu."
                    else ask("Terjemahkan ke bahasa ${LANGS[langIdx]}:\n\n$t", null, trOut, TRANSLATE_SYS)
                },
                button("Lingkari & terjemah") {
                    withRegion(false, trOut) { runTranslate(it) }
                }
            )
        )
        addOut(col, trOut)
        return col
    }

    // Tab 3: cari gambar (Google Lens) atau cari teks hasil OCR
    private fun imageView(): View {
        val col = vbox()
        imgOut = resultText()
        imgOut.text = "Lingkari bagian layar, lalu cari dengan Google Lens atau cari teksnya di Google."
        col.addView(button("Lingkari & cari gambar (Lens)") {
            withRegion(false, imgOut) { bmp -> searchWithLens(bmp) { imgOut.text = it } }
        })
        col.addView(button("Lingkari & cari teksnya di Google") {
            withRegion(false, imgOut) { bmp ->
                imgOut.text = "Membaca teks…"
                Ocr.read(bmp) { r ->
                    ui.post {
                        r.onSuccess { t ->
                            val q = t.replace('\n', ' ').trim().take(200)
                            if (q.isBlank()) {
                                imgOut.text = "Tidak ada teks terbaca."
                            } else {
                                imgOut.text = "Mencari: $q"
                                browse("https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8"))
                            }
                        }.onFailure { imgOut.text = "Error: ${it.message}" }
                    }
                }
            }
        })
        addOut(col, imgOut)
        return col
    }

    private fun searchWithLens(bmp: Bitmap, onError: (String) -> Unit) {
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
            hidePanel()
        } catch (e: Exception) {
            onError("Gagal: ${e.message}")
        }
    }

    // Tab 4: browser mini
    private fun toUrl(t: String): String = when {
        t.startsWith("http://") || t.startsWith("https://") -> t
        !t.contains(' ') && t.contains('.') -> "https://$t"
        else -> "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8")
    }

    private fun browse(url: String) {
        selectTab?.invoke(T_BROWSER)
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
        val fwd = Button(c).apply {
            text = "▶"
            minWidth = 0
            minimumWidth = 0
            setOnClickListener { if (w.canGoForward()) w.goForward() }
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
            addView(fwd)
            addView(url, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(goBtn)
        }
        col.addView(bar)
        col.addView(w, LinearLayout.LayoutParams(MATCH, 0, 1f))
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
                    minHeight = 0
                    minimumHeight = 0
                    setPadding(0, 0, 0, 0)
                    setOnClickListener { press(k) }
                }, LinearLayout.LayoutParams(0, MATCH, 1f))
            }
            col.addView(line, LinearLayout.LayoutParams(MATCH, dp(44)))
        }
        return scrolled(col)
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
        return scrolled(col)
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
        hidePanel()
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

    // ---------- Tab 8: aplikasi di HP sebagai jendela mengambang ----------

    private fun loadApps(done: () -> Unit) {
        if (allApps != null) {
            done()
            return
        }
        Thread {
            val list = try {
                val pm = packageManager
                val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                pm.queryIntentActivities(q, 0)
                    .filter { it.activityInfo.packageName != packageName }
                    .map {
                        AppItem(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm))
                    }
                    .distinctBy { it.pkg }
                    .sortedBy { it.label.lowercase() }
            } catch (e: Exception) {
                emptyList()
            }
            ui.post {
                allApps = list
                done()
            }
        }.start()
    }

    /** Buka aplikasi dengan ukuran jendela sesuai pengaturan. Butuh mode jendela bebas di HP. */
    private fun launchApp(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) {
            toast("Aplikasi tidak bisa dibuka")
            return
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val (sw, sh) = realSize()
        val w = minOf(dp(pref(Setting.APP_W)), sw - dp(16))
        val h = minOf(dp(pref(Setting.APP_H)), sh - dp(96))
        val l = (sw - w) / 2
        val t = dp(72)
        hidePanel()
        try {
            val o = ActivityOptions.makeBasic().setLaunchBounds(Rect(l, t, l + w, t + h))
            startActivity(i, o.toBundle())
        } catch (e: Exception) {
            toast("Gagal membuka: ${e.message}")
        }
    }

    private fun appsView(): View {
        val col = vbox()
        var picking = false
        val title = TextView(c).apply {
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val search = EditText(c).apply {
            hint = "Cari aplikasi"
            textSize = 14f
            setSingleLine()
            visibility = View.GONE
        }
        val grid = vbox()
        val modeBtn = button("+ Tambah aplikasi") {}
        val devBtn = button("Mode jendela") {
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                toast("Buka Pengaturan > Opsi pengembang secara manual")
            }
        }
        val hint = TextView(c).apply {
            text = "Aplikasi terbuka sebagai jendela mengambang hanya bila HP mendukung mode " +
                "jendela bebas. Di Opsi pengembang, aktifkan 'Jendela bentuk bebas' dan " +
                "'Paksa aktivitas dapat diubah ukuran', lalu restart HP. Ukuran jendela aplikasi " +
                "diatur di menu Atur. Tanpa itu, aplikasi terbuka layar penuh."
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }

        fun cell(app: AppItem, marked: Boolean, tap: () -> Unit, long: (() -> Unit)?): View =
            LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(2), dp(6), dp(2), dp(6))
                addView(ImageView(c).apply { setImageDrawable(app.icon) }, LinearLayout.LayoutParams(dp(40), dp(40)))
                addView(TextView(c).apply {
                    text = if (marked) "✓ ${app.label}" else app.label
                    textSize = 10f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setTextColor(if (marked) Color.parseColor("#2563EB") else Color.BLACK)
                })
                setOnClickListener { tap() }
                if (long != null) setOnLongClickListener { long(); true }
            }

        fun render() {
            grid.removeAllViews()
            val all = allApps
            if (all == null) {
                title.text = "Memuat daftar aplikasi…"
                loadApps { render() }
                return
            }
            val pinned = Prefs.pinned(c)
            val items: List<AppItem>
            if (picking) {
                val q = search.text.toString().trim().lowercase()
                items = all.filter { q.isEmpty() || it.label.lowercase().contains(q) }
                title.text = "Ketuk aplikasi untuk menambah atau melepas dari menu Apps."
            } else {
                items = pinned.mapNotNull { p -> all.find { it.pkg == p } }
                title.text = if (items.isEmpty()) {
                    "Belum ada aplikasi. Tekan + Tambah aplikasi."
                } else {
                    "Ketuk untuk membuka sebagai jendela. Tekan lama untuk menghapus."
                }
            }
            items.chunked(4).forEach { chunk ->
                val line = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
                chunk.forEach { app ->
                    val isPinned = app.pkg in pinned
                    val v = if (picking) {
                        cell(app, isPinned, {
                            val cur = Prefs.pinned(c)
                            Prefs.setPinned(c, if (app.pkg in cur) cur - app.pkg else cur + app.pkg)
                            render()
                        }, null)
                    } else {
                        cell(app, false, { launchApp(app.pkg) }, {
                            Prefs.setPinned(c, Prefs.pinned(c) - app.pkg)
                            toast("${app.label} dihapus dari menu")
                            render()
                        })
                    }
                    line.addView(v, LinearLayout.LayoutParams(0, WRAP, 1f))
                }
                repeat(4 - chunk.size) { line.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f)) }
                grid.addView(line)
            }
        }

        modeBtn.setOnClickListener {
            picking = !picking
            modeBtn.text = if (picking) "Selesai" else "+ Tambah aplikasi"
            search.visibility = if (picking) View.VISIBLE else View.GONE
            render()
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, cnt: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, cnt: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (picking) render()
            }
        })

        col.addView(row(modeBtn, devBtn))
        col.addView(search)
        col.addView(title)
        col.addView(grid)
        col.addView(hint)
        render()
        return scrolled(col)
    }

    // ---------- Tab 9: pengaturan tampilan ----------

    private fun settingsView(): View {
        val col = vbox()
        val refreshers = mutableListOf<() -> Unit>()

        fun section(t: String) = TextView(c).apply {
            text = t
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#2563EB"))
            setPadding(dp(4), dp(12), dp(4), dp(2))
        }

        fun slider(label: String, s: Setting, suffix: String, apply: () -> Unit): View {
            val box = vbox()
            val tv = TextView(c).apply {
                textSize = 13f
                setTextColor(Color.DKGRAY)
                setPadding(dp(4), dp(4), dp(4), 0)
            }
            val sb = SeekBar(c).apply { max = s.max - s.min }
            fun show() {
                val v = Prefs.get(c, s)
                sb.progress = v - s.min
                tv.text = "$label: $v$suffix"
            }
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = p + s.min
                    Prefs.set(c, s, v)
                    tv.text = "$label: $v$suffix"
                    apply()
                }

                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
            refreshers += { show() }
            show()
            box.addView(tv)
            box.addView(sb)
            return box
        }

        col.addView(section("Bubble"))
        col.addView(slider("Ukuran", Setting.BUBBLE_SIZE, " dp") { applyBubble() })
        col.addView(slider("Opasitas", Setting.BUBBLE_ALPHA, "%") { applyBubble() })
        val colors = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(8), dp(4), dp(4))
        }
        Prefs.COLORS.forEach { col0 ->
            colors.addView(View(c).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(col0)
                    setStroke(dp(1), Color.LTGRAY)
                }
                setOnClickListener {
                    Prefs.setBubbleColor(c, col0)
                    applyBubble()
                }
            }, LinearLayout.LayoutParams(0, dp(30), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        col.addView(TextView(c).apply {
            text = "Warna (juga dipakai menu lingkar)"
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(dp(4), dp(6), dp(4), 0)
        })
        col.addView(colors)

        col.addView(section("Menu lingkar"))
        col.addView(slider("Ukuran", Setting.MENU_SIZE, "%") {})
        col.addView(slider("Opasitas", Setting.MENU_ALPHA, "%") {})
        col.addView(button("Lihat menu lingkar") { showMenu() })

        col.addView(section("Jendela alat"))
        col.addView(slider("Lebar", Setting.WIN_W, " dp") { applyPanel() })
        col.addView(slider("Tinggi", Setting.WIN_H, " dp") { applyPanel() })
        col.addView(slider("Opasitas", Setting.WIN_ALPHA, "%") { applyPanel() })
        col.addView(TextView(c).apply {
            text = "Bisa juga ditarik lewat pegangan ◢ di pojok kanan bawah jendela."
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(dp(4), dp(2), dp(4), 0)
        })

        col.addView(section("Jendela aplikasi (tab Apps)"))
        col.addView(slider("Lebar", Setting.APP_W, " dp") {})
        col.addView(slider("Tinggi", Setting.APP_H, " dp") {})

        col.addView(button("Kembalikan bawaan") {
            Prefs.resetLook(c)
            refreshers.forEach { it() }
            applyBubble()
            applyPanel()
        })
        return scrolled(col)
    }
}
