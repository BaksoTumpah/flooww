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
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Base64
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
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

        private const val M_ASK = 0
        private const val M_COPY = 1
        private const val M_TR = 2
        private const val EDGE_L = 1
        private const val EDGE_T = 2
        private const val EDGE_R = 4
        private const val EDGE_B = 8
        private const val M_IMG = 3
        private const val M_BROWSER = 4
        private const val M_CHAT = 5
        private const val M_CALC = 6
        private const val M_CLICK = 7
        private const val M_APPS = 8
        private const val M_AUTO = 9

        private val NAMES = listOf(
            "Tanya AI", "Salin", "Terjemah", "Gambar", "Browser",
            "Chat", "Hitung", "Klik", "Apps", "Auto Jawab"
        )
        private val ICONS = intArrayOf(
            R.drawable.ic_ask, R.drawable.ic_copy, R.drawable.ic_translate, R.drawable.ic_image,
            R.drawable.ic_browser, R.drawable.ic_chat, R.drawable.ic_calc, R.drawable.ic_touch,
            R.drawable.ic_apps, R.drawable.ic_auto
        )

        // alat yang mengambil soal dari layar: lingkaran pilih area muncul dulu, jendela sesudahnya
        private val CIRCLE_FIRST = setOf(M_ASK, M_COPY, M_TR, M_IMG)

        /**
         * Semua jendela mengambang: tidak pernah menerima fokus input (keyboard virtual tidak bisa muncul)
         * dan sentuhan di luar jendela diteruskan ke aplikasi di bawahnya.
         */
        private const val OVERLAY_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private class AppItem(val pkg: String, val label: String, val icon: Drawable)

    private val c: Context get() = this
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var P: Pal

    private lateinit var wm: WindowManager
    private var grab: ScreenGrabber? = null

    // bubble dan menu
    private var bubble: TextView? = null
    private var bubbleBg: GradientDrawable? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var menu: FrameLayout? = null

    // jendela alat: dibuat sekali, tombol ✕ hanya menyembunyikan sehingga isinya tidak hilang
    private var panel: LinearLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var panelAttached = false
    private var panelContent: FrameLayout? = null
    private var panelIcon: ImageView? = null
    private var panelTitle: TextView? = null
    private val toolViews = mutableMapOf<Int, View>()
    private var selector: SelectView? = null
    private var web: WebView? = null
    private var ocrText = ""
    private var allApps: List<AppItem>? = null
    private val chatTurns = mutableListOf<Pair<String, String>>()

    private lateinit var copyOut: TextView

    // auto clicker
    private var target: TextView? = null
    private var targetLp: WindowManager.LayoutParams? = null
    private var clicking = false
    private var clicksDone = 0
    private var maxClicks = 0
    private var intervalMs = 1000L

    // auto jawab
    private var autoOn = false
    private var marks: MarksView? = null
    private var autoState = 0 // 0 menunggu layar tenang, 1 membaca, 2 menampilkan hasil
    private var autoPrev: IntArray? = null
    private var autoBase: IntArray? = null
    private var autoLastEnd = 0L
    private var autoGap = 8000L
    private var autoGen = 0

    // pengaturan di aplikasi langsung berlaku pada bubble dan jendela
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        ui.post {
            applyBubble()
            applyPanel()
            applyKeyboard()
            web?.settings?.textZoom = pref(Setting.WEB_ZOOM)
        }
    }

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

    private val autoRunner = object : Runnable {
        override fun run() {
            if (!autoOn) return
            autoTick()
            if (autoOn) ui.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = (v * resources.displayMetrics.density).toInt()
    private fun pref(s: Setting) = Prefs.get(c, s)
    private val accent: Int get() = Prefs.bubbleColor(c)

    // ---------- siklus hidup ----------

    override fun onCreate() {
        super.onCreate()
        P = Ui.pal(this)
        // buka akses ke API tersembunyi (setLaunchWindowingMode) agar jendela bebas bisa dipakai
        try {
            if (Build.VERSION.SDK_INT >= 28) org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L")
        } catch (e: Throwable) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)

        // izin tangkap layar baru diberikan (bubble sudah jalan, atau baru mulai)
        startAsForeground(data != null)
        if (data != null) {
            grab?.release()
            val g = ScreenGrabber(this, ui)
            val (w, h) = realSize()
            g.start(code, data, w, h, resources.displayMetrics.densityDpi) { grab = null }
            grab = g
        }
        if (bubble == null) {
            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            Prefs.sp(this).registerOnSharedPreferenceChangeListener(prefListener)
            showBubble()
        }
        // dimulai ulang sistem bila prosesnya dimatikan; bubble tetap muncul
        return START_STICKY
    }

    /** Minta izin tangkap layar lewat layar transparan (tanpa membuka halaman penuh). */
    private fun needProjection() {
        toast("Izinkan tangkap layar")
        try {
            startActivity(
                Intent(this, ProjectionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            toast("Gagal meminta izin")
        }
    }

    @Suppress("DEPRECATION")
    private fun startAsForeground(projection: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("float", "flooww", NotificationManager.IMPORTANCE_LOW)
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
            .setContentTitle("flooww aktif")
            .setContentText("Bubble berjalan.")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_delete, "Hentikan", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (projection || grab?.active == true) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(1, n, type)
        } else {
            startForeground(1, n)
        }
    }

    override fun onDestroy() {
        Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        ui.removeCallbacksAndMessages(null)
        clicking = false
        autoOn = false
        if (::wm.isInitialized) {
            hideMenu()
            hidePanel()
            listOf<View?>(selector, target, marks, bubble).forEach { v ->
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
        marks = null
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

    private fun lay(w: Int = MATCH, h: Int = WRAP, wt: Float = 0f, m: Int = 0) =
        LinearLayout.LayoutParams(w, h, wt).apply { setMargins(dp(m), dp(m), dp(m), dp(m)) }

    private fun btn(label: String, primary: Boolean = false, onClick: () -> Unit): TextView =
        Ui.button(c, label, primary, onClick)

    private fun field(hint: String, multi: Boolean = false) = Ui.field(c, hint, multi).apply {
        inputs.add(this)
        applyKb(this)
    }

    private fun resultText() = TextView(c).apply {
        textSize = 14f
        setTextColor(P.on)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setTextIsSelectable(true)
        background = Ui.round(P.field, dp(12))
    }

    private fun note(t: String) = TextView(c).apply {
        text = t
        textSize = 12f
        setTextColor(P.muted)
        setPadding(dp(2), dp(6), dp(2), dp(4))
    }

    private fun vbox() = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

    private fun row(vararg v: View) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        v.forEach { addView(it, lay(0, WRAP, 1f, 3)) }
    }

    /** Kotak hasil yang mengisi sisa tinggi jendela, jadi ikut berubah saat jendela diubah ukurannya. */
    private fun addOut(col: LinearLayout, out: TextView, weight: Float = 1f) {
        col.addView(ScrollView(c).apply { addView(out) }, lay(MATCH, 0, weight, 3))
    }

    /** Isi alat yang panjang dibungkus ScrollView supaya tetap terpakai di jendela kecil. */
    private fun scrolled(col: LinearLayout): View =
        ScrollView(c).apply { addView(col, MATCH, WRAP) }

    private fun toast(t: String) = Toast.makeText(c, t, Toast.LENGTH_SHORT).show()

    private fun makeDraggable(
        handle: View,
        moving: View,
        lp: WindowManager.LayoutParams,
        onTap: (() -> Unit)? = null,
        onDouble: (() -> Unit)? = null,
        onLong: (() -> Unit)? = null
    ) {
        handle.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0
            var sy = 0
            var tx = 0f
            var ty = 0f
            var moved = false
            var longDone = false
            var pendingTap: Runnable? = null
            // gestur hanya berlaku bila dipilih di pengaturan dan tidak sedang ada mode aktif
            fun busy() = autoOn || clicking || menu != null
            fun longOn() = onLong != null && !busy() && Prefs.gesture(c, 2) != 0
            fun dblOn() = onDouble != null && !busy() && Prefs.gesture(c, 1) != 0
            val longRun = Runnable {
                if (!moved) {
                    longDone = true
                    handle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onLong?.invoke()
                }
            }

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; moved = false; longDone = false
                        if (longOn()) ui.postDelayed(longRun, pref(Setting.LONG_MS).toLong())
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - tx).toInt()
                        val dy = (e.rawY - ty).toInt()
                        if (abs(dx) > dp(6) || abs(dy) > dp(6)) {
                            moved = true
                            ui.removeCallbacks(longRun)
                        }
                        if (moved) {
                            lp.x = sx + dx
                            lp.y = sy + dy
                            wm.updateViewLayout(moving, lp)
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> ui.removeCallbacks(longRun)
                    MotionEvent.ACTION_UP -> {
                        ui.removeCallbacks(longRun)
                        if (!moved && !longDone) {
                            val waiting = pendingTap
                            if (waiting != null && dblOn()) {
                                // ketukan kedua dalam jeda: dihitung ketuk dua kali
                                ui.removeCallbacks(waiting)
                                pendingTap = null
                                onDouble?.invoke()
                            } else if (dblOn()) {
                                val r = Runnable { pendingTap = null; onTap?.invoke() }
                                pendingTap = r
                                ui.postDelayed(r, 280)
                            } else {
                                onTap?.invoke()
                            }
                        }
                    }
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

    private var rsx = 0
    private var rsy = 0
    private var rsw = 0
    private var rsh = 0

    /**
     * Ubah ukuran dari tepi mana pun. Tepi kanan/kiri mengubah lebar, atas/bawah mengubah tinggi,
     * pojok mengubah keduanya. Tepi kiri dan atas juga menggeser jendela supaya sisi seberangnya diam.
     */
    private fun resizeEdge(root: View, lp: WindowManager.LayoutParams, mask: Int, phase: Int, dx: Float, dy: Float) {
        val dm = resources.displayMetrics
        val minW = dp(Setting.WIN_W.min)
        val minH = dp(Setting.WIN_H.min)
        val maxW = minOf(dp(Setting.WIN_W.max), dm.widthPixels)
        val maxH = minOf(dp(Setting.WIN_H.max), dm.heightPixels)
        when (phase) {
            0 -> { rsx = lp.x; rsy = lp.y; rsw = lp.width; rsh = lp.height }
            1 -> {
                var x = rsx
                var y = rsy
                var w = rsw
                var h = rsh
                if (mask and EDGE_R != 0) w = (rsw + dx).toInt().coerceIn(minW, maxW)
                if (mask and EDGE_B != 0) h = (rsh + dy).toInt().coerceIn(minH, maxH)
                if (mask and EDGE_L != 0) {
                    w = (rsw - dx).toInt().coerceIn(minW, maxW)
                    x = rsx + rsw - w
                }
                if (mask and EDGE_T != 0) {
                    h = (rsh - dy).toInt().coerceIn(minH, maxH)
                    y = rsy + rsh - h
                }
                lp.x = x; lp.y = y; lp.width = w; lp.height = h
                try {
                    wm.updateViewLayout(root, lp)
                } catch (e: Exception) {
                }
            }
            2 -> {
                val d = dm.density
                Prefs.set(c, Setting.WIN_W, (lp.width / d).toInt())
                Prefs.set(c, Setting.WIN_H, (lp.height / d).toInt())
            }
        }
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
        cm.setPrimaryClip(ClipData.newPlainText("flooww", t))
        lastCopied = t
        toast("Teks disalin")
    }

    // ---------- bubble ----------

    private fun showBubble() {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(accent)
            setStroke(dp(2), 0x80FFFFFF.toInt())
        }
        val b = TextView(c).apply {
            text = ""
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = bg
            elevation = dp(4).toFloat()
        }
        val size = dp(pref(Setting.BUBBLE_SIZE))
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            OVERLAY_FLAGS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(200)
        }
        makeDraggable(b, b, lp,
            onTap = { onBubbleTap() },
            onDouble = { onBubbleGesture(1) },
            onLong = { onBubbleGesture(2) }
        )
        wm.addView(b, lp)
        bubble = b
        bubbleBg = bg
        bubbleLp = lp
        applyBubble()
    }

    private fun onBubbleTap() {
        when {
            autoOn -> stopAuto()
            clicking -> stopClicking()
            menu != null -> hideMenu()
            else -> runGesture(Prefs.gesture(c, 0))
        }
    }

    /** Ketuk dua kali (1) atau tekan lama (2). Saat Auto Jawab atau auto clicker aktif, gestur ini mematikannya. */
    private fun onBubbleGesture(g: Int) {
        when {
            autoOn -> stopAuto()
            clicking -> stopClicking()
            else -> {
                hideMenu()
                runGesture(Prefs.gesture(c, g))
            }
        }
    }

    /** Pilihan 0 tidak ada, 1 buka menu, 2..11 alat menu (id = pilihan - 2). */
    private fun runGesture(choice: Int) {
        when {
            choice == 0 -> {}
            choice == 1 -> if (menu != null) hideMenu() else showMenu()
            else -> onMenuPick(choice - 2)
        }
    }

    /** Bubble polos. Hanya saat Auto Jawab menyala tampil ✕, dan saat auto clicker berjalan tampil ■. */
    private fun applyBubble() {
        val b = bubble ?: return
        val lp = bubbleLp ?: return
        val sizeDp = pref(Setting.BUBBLE_SIZE)
        lp.width = dp(sizeDp)
        lp.height = dp(sizeDp)
        b.alpha = pref(Setting.BUBBLE_ALPHA) / 100f
        b.text = when {
            autoOn -> "✕"
            clicking -> "■"
            else -> ""
        }
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp * 0.4f)
        bubbleBg?.setColor(if (autoOn || clicking) 0xFFDC2626.toInt() else accent)
        try {
            wm.updateViewLayout(b, lp)
        } catch (e: Exception) {
        }
    }

    private fun setOverlayVisible(v: Boolean) {
        val vis = if (v) View.VISIBLE else View.INVISIBLE
        bubble?.visibility = vis
        panel?.visibility = vis
        target?.visibility = vis
    }

    // ---------- menu kotak (muncul di samping bubble saat diketuk) ----------

    private fun showMenu() {
        if (menu != null) return
        val b = bubble ?: return
        val root = FrameLayout(c).apply {
            setBackgroundColor(0x22000000)
            setOnClickListener { hideMenu() }
        }
        val lp = WindowManager.LayoutParams(
            MATCH, MATCH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                OVERLAY_FLAGS,
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
        val sc = pref(Setting.MENU_SIZE) / 100f
        val tile = dpf(48f * sc)
        val gap = dpf(8f * sc)
        val pad = dpf(12f * sc)
        val cell = tile + gap
        val cols = if (5 * cell + 2 * pad + b.width + dp(24) <= root.width) 5 else 4

        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.round(P.surface, dpf(20f * sc), P.outline, dp(1))
            setPadding(pad, pad, pad, pad - gap / 2)
            elevation = dp(12).toFloat()
        }
        NAMES.indices.chunked(cols).forEach { chunk ->
            val line = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { i ->
                val icon = ImageView(c).apply {
                    setImageResource(ICONS[i])
                    setColorFilter(accent)
                    val padIc = (tile * 0.27f).toInt()
                    setPadding(padIc, padIc, padIc, padIc)
                    background = Ui.ripple(Ui.round(Ui.withAlpha(accent, 0.14f), dpf(14f * sc)), P.night)
                }
                val name = TextView(c).apply {
                    text = NAMES[i]
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(P.on)
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10f * sc)
                }
                val item = LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    isClickable = true
                    setOnClickListener { onMenuPick(i) }
                    addView(icon, LinearLayout.LayoutParams(tile, tile))
                    addView(name, LinearLayout.LayoutParams(cell, WRAP).apply { topMargin = dp(3) })
                }
                line.addView(item, LinearLayout.LayoutParams(cell, WRAP).apply { bottomMargin = gap / 2 })
            }
            card.addView(line)
        }

        card.measure(
            View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.AT_MOST)
        )
        val cw = card.measuredWidth
        val ch = card.measuredHeight
        val bl = IntArray(2)
        val rl = IntArray(2)
        b.getLocationOnScreen(bl)
        root.getLocationOnScreen(rl)
        val bx = bl[0] - rl[0]
        val by = bl[1] - rl[1]
        val edge = dp(8)
        var left = bx + b.width + edge
        if (left + cw > root.width - edge) left = bx - edge - cw
        if (left < edge) left = ((root.width - cw) / 2).coerceAtLeast(edge)
        val top = (by + b.height / 2 - ch / 2)
            .coerceIn(dp(24), maxOf(dp(24), root.height - ch - dp(24)))

        val finalAlpha = pref(Setting.MENU_ALPHA) / 100f
        card.alpha = 0f
        card.translationY = dp(8).toFloat()
        root.addView(card, FrameLayout.LayoutParams(cw, ch).apply {
            leftMargin = left
            topMargin = top
        })
        card.animate().alpha(finalAlpha).translationY(0f).setDuration(140).start()
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

    private fun onMenuPick(i: Int) {
        hideMenu()
        when {
            i in CIRCLE_FIRST -> circleFirst(i)
            i == M_AUTO -> startAuto()
            else -> openTool(i)
        }
    }

    /**
     * Alat yang mengambil soal dari layar: lingkaran pilih area muncul dulu
     * (seperti circle to search). Hasilnya baru diproses sesudah area dilingkari.
     */
    private fun circleFirst(id: Int) {
        val g = grab
        if (g == null || !g.active) {
            needProjection()
            return
        }
        pickRegion(false) { bmp ->
            if (bmp == null) {
                toast("Dibatalkan")
                return@pickRegion
            }
            when (id) {
                M_ASK -> askWeb(bmp, Prefs.prompt(c))
                M_TR -> {
                    openTool(M_TR)
                    runTranslate(bmp)
                }
                M_COPY -> {
                    openTool(M_COPY)
                    runCopy(bmp)
                }
                M_IMG -> searchWithLens(bmp) { toast(it) }
            }
        }
    }

    // ---------- Tanya AI lewat browser mengambang ----------

    private val AI_WEB = listOf("https://gemini.google.com/app", "https://chatgpt.com/", "https://claude.ai/new")
    private val AI_WEB_Q = listOf(null, "https://chatgpt.com/?q=", "https://claude.ai/new?q=")

    // gambar yang akan dilampirkan otomatis saat situs AI membuka pemilih file
    private var pendingUpload: Uri? = null
    private var pendingB64: String? = null
    private var attachBtn: ImageView? = null

    /**
     * Buka situs AI pilihan di browser mengambang (bukan aplikasi AI yang terpasang).
     * Gambar disiapkan: saat Anda menekan tombol lampirkan di situs, gambar langsung terpilih.
     * Perintah: ChatGPT dan Claude menerimanya lewat alamat, Gemini lewat tombol tempel di header.
     */
    private fun askWeb(bmp: Bitmap?, prompt: String) {
        val target = Prefs.aiTarget(c)
        val name = Prefs.AI_NAMES[target]
        pendingUpload = null
        pendingB64 = null
        if (bmp != null) {
            try {
                val dir = File(cacheDir, "shared").apply { mkdirs() }
                val f = File(dir, "ask.jpg")
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                pendingUpload = Uri.fromFile(f)
                pendingB64 = toJpegB64(bmp)
            } catch (e: Exception) {
                toast("Gagal menyiapkan gambar: ${e.message}")
            }
        }
        lastCopied = prompt
        val base = AI_WEB_Q[target]
        val url = if (base != null) base + URLEncoder.encode(prompt.take(1500), "UTF-8") else AI_WEB[target]
        openTool(M_BROWSER)
        refreshAttach()
        web?.loadUrl(url)
        toast(
            if (bmp != null) "Membuka $name…"
            else "Membuka $name."
        )
    }

    private fun refreshAttach() {
        attachBtn?.visibility = if (curTool == M_BROWSER && pendingB64 != null) View.VISIBLE else View.GONE
    }

    /**
     * Tempelkan gambar soal ke halaman situs AI: acara tempel (paste) dengan berkas gambar pada kolom pesan,
     * atau bila tidak ada, isi input berkas. Mengembalikan 1 (tempel), 2 (input berkas), 0 (gagal).
     */
    private fun injectImage(onResult: (Int) -> Unit) {
        val w = web
        val b64 = pendingB64
        if (w == null || b64 == null) {
            onResult(0)
            return
        }
        // gambar diambil dari jembatan Android (bukan ditempel ke skrip) agar tidak terlalu besar
        val js = "(function(){try{var b=window.floowwImg.get();if(!b)return 0;" +
            "var s=atob(b),a=new Uint8Array(s.length);for(var i=0;i<s.length;i++)a[i]=s.charCodeAt(i);" +
            "var f=new File([a],'soal.jpg',{type:'image/jpeg'});var dt=new DataTransfer();dt.items.add(f);" +
            // 1) input berkas yang menerima gambar (ChatGPT, Claude punya sejak awal)
            "var ins=document.querySelectorAll('input[type=file]');var t=null;" +
            "for(var k=0;k<ins.length;k++){var ac=(ins[k].accept||'');if(!ac||ac.indexOf('image')>=0||ac.indexOf('*')>=0){t=ins[k];break;}}" +
            "if(t){t.files=dt.files;t.dispatchEvent(new Event('input',{bubbles:true}));t.dispatchEvent(new Event('change',{bubbles:true}));return 2;}" +
            // 2) tempel + jatuhkan berkas ke kolom pesan
            "var ok=function(x){return x&&(x.isContentEditable||x.tagName=='TEXTAREA'||(x.getAttribute&&x.getAttribute('role')=='textbox'));};" +
            "var e=document.activeElement;" +
            "if(!ok(e))e=document.querySelector('rich-textarea [contenteditable],div.ql-editor,textarea,[contenteditable],[role=textbox]');" +
            "if(e){e.focus();" +
            "e.dispatchEvent(new ClipboardEvent('paste',{clipboardData:dt,bubbles:true,cancelable:true}));" +
            "['dragenter','dragover','drop'].forEach(function(n){e.dispatchEvent(new DragEvent(n,{dataTransfer:dt,bubbles:true,cancelable:true}));});" +
            "return 1;}" +
            "return 0;}catch(x){return 0;}})()"
        w.evaluateJavascript(js) { r -> onResult(r?.trim()?.toIntOrNull() ?: 0) }
    }

    /** Coba beberapa kali setelah halaman dimuat, karena kolom pesan situs AI muncul belakangan. */
    private fun autoAttach(tries: Int = 12) {
        if (pendingB64 == null || tries <= 0) return
        ui.postDelayed({
            if (pendingB64 == null) return@postDelayed
            injectImage { r ->
                if (r != 0) {
                    pendingB64 = null
                    pendingUpload = null
                    refreshAttach()
                    toast("Gambar soal terlampir")
                } else {
                    autoAttach(tries - 1)
                }
            }
        }, 1500)
    }

    /** Jembatan agar skrip halaman bisa mengambil gambar soal. */
    inner class ImgBridge {
        @android.webkit.JavascriptInterface
        fun get(): String = pendingB64 ?: ""
    }

    // ---------- ambil area layar ----------

    private fun withRegion(full: Boolean, out: TextView, next: (Bitmap) -> Unit) {
        val g = grab
        if (g == null || !g.active) {
            out.text = "Menunggu izin tangkap layar…"
            needProjection()
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
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                OVERLAY_FLAGS,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(v, lp)
        selector = v
    }

    /** Cari gambar dengan Google Lens di browser mengambang. Gambar otomatis terpilih saat situs meminta file. */
    private fun searchWithLens(bmp: Bitmap, onMsg: (String) -> Unit) {
        try {
            val dir = File(cacheDir, "shared").apply { mkdirs() }
            val f = File(dir, "crop.png")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            pendingUpload = Uri.fromFile(f)
            pendingB64 = toJpegB64(bmp)
            openTool(M_BROWSER)
            refreshAttach()
            web?.loadUrl("https://lens.google.com/")
            onMsg("Membuka Google Lens…")
        } catch (e: Exception) {
            onMsg("Gagal: ${e.message}")
        }
    }

    // ---------- jendela alat ----------

    private fun panelW() = minOf(dp(pref(Setting.WIN_W)), resources.displayMetrics.widthPixels - dp(8))
    private fun panelH() = minOf(dp(pref(Setting.WIN_H)), resources.displayMetrics.heightPixels - dp(48))

    /** Tampilkan jendela (dibuat sekali, sisanya hanya ditampilkan lagi) dan pilih alat. */
    private fun openTool(id: Int) {
        val root = ensurePanel()
        val content = panelContent ?: return
        if (toolViews[id] == null) {
            val v = buildTool(id)
            toolViews[id] = v
            content.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        toolViews.forEach { (k, v) -> v.visibility = if (k == id) View.VISIBLE else View.GONE }
        curTool = id
        pasteBtn?.visibility = if (pasteFns.containsKey(id)) View.VISIBLE else View.GONE
        refreshAttach()
        panelIcon?.setImageResource(ICONS[id])
        panelTitle?.text = NAMES[id]

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
    }

    private fun buildTool(id: Int): View = when (id) {
        M_COPY -> copyView()
        M_TR -> trView()
        M_BROWSER -> browserView()
        M_CHAT -> chatView()
        M_CALC -> calcView()
        M_CLICK -> clickView()
        M_APPS -> appsView()
        else -> vbox()
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

    private fun kbBlocked() = Prefs.kbBlock(c)

    /** Diblokir: jendela tidak pernah fokus, keyboard tidak bisa muncul. Diizinkan: jendela bisa fokus untuk mengetik. */
    private fun panelFlags(): Int =
        (if (kbBlocked()) OVERLAY_FLAGS else WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL) or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

    private fun panelSoftInput(): Int =
        if (kbBlocked()) {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        } else {
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

    private val inputs = mutableListOf<EditText>()

    // teks terakhir hasil salin dari gambar. Android 10+ melarang layanan latar membaca clipboard, jadi disimpan sendiri
    private var lastCopied = ""
    private val pasteFns = mutableMapOf<Int, (String) -> Unit>()
    private var curTool = -1
    private var pasteBtn: ImageView? = null

    private fun clipText(): String {
        if (lastCopied.isNotBlank()) return lastCopied
        return try {
            getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(c)?.toString().orEmpty()
        } catch (e: Exception) {
            ""
        }
    }

    private fun doPaste() {
        val fn = pasteFns[curTool] ?: return
        val t = clipText()
        if (t.isBlank()) {
            toast("Belum ada teks")
            return
        }
        fn(t)
    }

    private fun applyKb(e: EditText) {
        val b = kbBlocked()
        e.showSoftInputOnFocus = !b
        e.isFocusable = !b
        e.isFocusableInTouchMode = !b
        if (b) e.clearFocus()
    }

    /** Dipanggil saat pengaturan keyboard di aplikasi berubah: berlaku langsung pada jendela yang sedang tampil. */
    private fun applyKeyboard() {
        inputs.forEach { applyKb(it) }
        val p = panel ?: return
        val lp = panelLp ?: return
        lp.flags = (lp.flags and (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).inv()) or
            (if (kbBlocked()) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE else 0)
        lp.softInputMode = panelSoftInput()
        if (panelAttached) {
            try {
                wm.updateViewLayout(p, lp)
            } catch (e: Exception) {
            }
        }
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

        val root = EdgeLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            edge = dp(16)
            background = Ui.round(P.surface, dp(20), P.outline, dp(1))
            setPadding(dp(12), dp(6), dp(12), dp(4))
            elevation = dp(10).toFloat()
        }

        // garis kecil penanda bisa digeser
        root.addView(
            View(c).apply { background = Ui.round(P.outline, dp(2)) },
            LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(6)
            }
        )

        val icon = ImageView(c).apply {
            setColorFilter(accent)
            val pd = dp(6)
            setPadding(pd, pd, pd, pd)
            background = Ui.round(Ui.withAlpha(accent, 0.14f), dp(9))
        }
        val title = TextView(c).apply {
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(P.on)
            setPadding(dp(10), 0, 0, 0)
        }
        val close = ImageView(c).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(P.muted)
            val pd = dp(9)
            setPadding(pd, pd, pd, pd)
            contentDescription = "Sembunyikan jendela"
            setOnClickListener { hidePanel() }
        }
        val paste = ImageView(c).apply {
            setImageResource(R.drawable.ic_paste)
            setColorFilter(accent)
            val pd = dp(9)
            setPadding(pd, pd, pd, pd)
            contentDescription = "Tempel teks dari gambar"
            visibility = View.GONE
            setOnClickListener { doPaste() }
        }
        pasteBtn = paste
        val attach = ImageView(c).apply {
            setImageResource(R.drawable.ic_image)
            setColorFilter(accent)
            val pd = dp(9)
            setPadding(pd, pd, pd, pd)
            contentDescription = "Lampirkan gambar soal ke halaman"
            visibility = View.GONE
            setOnClickListener {
                injectImage { r ->
                    if (r != 0) {
                        pendingB64 = null
                        pendingUpload = null
                        refreshAttach()
                        toast("Gambar soal terlampir")
                    } else {
                        toast("Kolom pesan tidak ketemu")
                    }
                }
            }
        }
        attachBtn = attach
        val header = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(icon, LinearLayout.LayoutParams(dp(30), dp(30)))
            addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(attach, LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(paste, LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(close, LinearLayout.LayoutParams(dp(36), dp(36)))
        }
        val content = FrameLayout(c)
        val grip = TextView(c).apply {
            text = "◢"
            textSize = 13f
            setTextColor(P.muted)
            setPadding(dp(28), dp(2), dp(2), 0)
        }
        val bottom = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(grip)
        }
        root.addView(header, lay(MATCH, WRAP))
        root.addView(content, lay(MATCH, 0, 1f).apply { topMargin = dp(6) })
        root.addView(bottom, lay(MATCH, WRAP))

        val dm = resources.displayMetrics
        val w = panelW()
        val lp = WindowManager.LayoutParams(
            w, panelH(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            panelFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (dm.widthPixels - w) / 2
            y = dp(56)
            softInputMode = panelSoftInput()
        }
        makeDraggable(header, root, lp)
        root.onEdge = { mask, phase, dx, dy -> resizeEdge(root, lp, mask, phase, dx, dy) }
        root.alpha = pref(Setting.WIN_ALPHA) / 100f
        panel = root
        panelLp = lp
        panelContent = content
        panelIcon = icon
        panelTitle = title
        return root
    }

    // ---------- alat: Salin ----------

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

    // ---------- alat: Terjemah (di HP, tanpa website) ----------

    private var trOut: TextView? = null
    private var trText = ""

    private fun trView(): View {
        val col = vbox()
        val out = resultText().apply {
            text = "Lingkari teks di layar."
        }
        trOut = out
        col.addView(
            row(
                btn("Lingkari area", true) { withRegion(false, out) { runTranslate(it) } },
                btn("Layar penuh") { withRegion(true, out) { runTranslate(it) } }
            )
        )
        addOut(col, out)
        col.addView(row(btn("Salin hasil") { if (trText.isNotBlank()) copyText(trText) else toast("Belum ada hasil") }))
        return col
    }

    private fun runTranslate(bmp: Bitmap) {
        val out = trOut ?: return
        val tag = Prefs.LANG_CODES[Prefs.lang(c)]
        out.text = "Membaca teks…"
        Ocr.read(bmp) { r ->
            ui.post {
                r.onFailure { out.text = "Error: ${it.message}" }
                r.onSuccess { src ->
                    if (src.isBlank()) {
                        out.text = "Tidak ada teks terbaca."
                        return@onSuccess
                    }
                    out.text = "Mengenali bahasa…"
                    com.google.mlkit.nl.languageid.LanguageIdentification.getClient()
                        .identifyLanguage(src)
                        .addOnSuccessListener { code ->
                            val from = com.google.mlkit.nl.translate.TranslateLanguage
                                .fromLanguageTag(if (code == "und") "en" else code)
                            val to = com.google.mlkit.nl.translate.TranslateLanguage.fromLanguageTag(tag)
                            when {
                                from == null || to == null -> out.text = "Bahasa teks ($code) belum didukung penerjemah di HP."
                                from == to -> out.text = "Teks sudah dalam bahasa ${Prefs.LANGS[Prefs.lang(c)]}.\n\n$src"
                                else -> translateText(src, from, to, out)
                            }
                        }
                        .addOnFailureListener { out.text = "Gagal mengenali bahasa: ${it.message}" }
                }
            }
        }
    }

    private fun translateText(src: String, from: String, to: String, out: TextView) {
        val tr = com.google.mlkit.nl.translate.Translation.getClient(
            com.google.mlkit.nl.translate.TranslatorOptions.Builder()
                .setSourceLanguage(from)
                .setTargetLanguage(to)
                .build()
        )
        out.text = "Menyiapkan model bahasa…"
        tr.downloadModelIfNeeded(com.google.mlkit.common.model.DownloadConditions.Builder().build())
            .addOnSuccessListener {
                out.text = "Menerjemahkan…"
                tr.translate(src)
                    .addOnSuccessListener { t ->
                        trText = t
                        out.text = t + "\n\n— teks asli —\n" + src
                        tr.close()
                    }
                    .addOnFailureListener { out.text = "Gagal menerjemahkan: ${it.message}"; tr.close() }
            }
            .addOnFailureListener {
                out.text = "Model bahasa gagal diunduh: ${it.message}\nPastikan internet aktif."
                tr.close()
            }
    }

    private fun copyView(): View {
        val col = vbox()
        copyOut = resultText().apply {
            text = "Lingkari teks di layar."
        }
        col.addView(
            row(
                btn("Lingkari area", true) { withRegion(false, copyOut) { runCopy(it) } },
                btn("Layar penuh") { withRegion(true, copyOut) { runCopy(it) } }
            )
        )
        addOut(col, copyOut)
        col.addView(
            row(
                btn("Salin lagi") {
                    if (ocrText.isNotBlank()) copyText(ocrText) else toast("Belum ada teks")
                },
                btn("Kirim ke AI") {
                    if (ocrText.isBlank()) toast("Belum ada teks")
                    else askWeb(null, Prefs.prompt(c) + "\n\n" + ocrText)
                }
            )
        )
        return col
    }

    // ---------- alat: Browser ----------

    private fun toUrl(t: String): String = when {
        t.startsWith("http://") || t.startsWith("https://") -> t
        !t.contains(' ') && t.contains('.') -> "https://$t"
        else -> "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8")
    }

    private fun browserView(): View {
        val col = vbox()
        val url = field("Cari atau ketik alamat").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
        }
        val w = WebView(c).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(ImgBridge(), "floowwImg")
            // beberapa situs menolak WebView. Samarkan "wv" agar tampil seperti Chrome biasa.
            settings.userAgentString = settings.userAgentString.replace("; wv", "")
            // skala lebih kecil: halaman tampak lebih luas di jendela yang tetap kecil
            settings.textZoom = pref(Setting.WEB_ZOOM)
            setInitialScale(pref(Setting.WEB_ZOOM))
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    wv: WebView?,
                    cb: ValueCallback<Array<Uri>>?,
                    params: FileChooserParams?
                ): Boolean {
                    val u = pendingUpload
                    if (u != null) {
                        cb?.onReceiveValue(arrayOf(u))
                        pendingUpload = null
                        toast("Gambar soal dilampirkan")
                    } else {
                        cb?.onReceiveValue(null)
                        toast("Belum ada gambar")
                    }
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, u: String?) {
                    if (!url.hasFocus() && u != null) url.setText(u)
                    if (pendingB64 != null) autoAttach()
                }
            }
            loadUrl("https://www.google.com")
        }
        // tempel ke kolom ketik di halaman (mis. kolom pesan situs AI). Bila tidak ada, jadi kata pencarian.
        pasteFns[M_BROWSER] = { t ->
            val js = "(function(t){var e=document.activeElement;" +
                "var ok=function(x){return x&&(x.isContentEditable||x.tagName=='TEXTAREA'||(x.tagName=='INPUT'&&/^(text|search|)$/i.test(x.type)));};" +
                "if(!ok(e)){e=document.querySelector('textarea,[contenteditable=\"true\"],input[type=text],input[type=search]');}" +
                "if(!e)return 0;e.focus();" +
                "if(e.isContentEditable){document.execCommand('insertText',false,t);}" +
                "else{var p=e.tagName=='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;" +
                "Object.getOwnPropertyDescriptor(p,'value').set.call(e,t);e.dispatchEvent(new Event('input',{bubbles:true}));}" +
                "return 1;})(" + org.json.JSONObject.quote(t) + ")"
            w.evaluateJavascript(js) { r ->
                if (r != "1") {
                    val q = t.trim().replace('\n', ' ')
                    url.setText(q)
                    w.loadUrl(toUrl(q))
                    toast("Tidak ada kolom ketik di halaman, dicari di Google")
                }
            }
        }
        url.setOnEditorActionListener { _, _, _ ->
            val t = url.text.toString().trim()
            if (t.isNotEmpty()) w.loadUrl(toUrl(t))
            w.requestFocus()
            true
        }
        fun nav(label: String, f: () -> Unit) = btn(label) { f() }.apply {
            minWidth = 0
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val bar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(nav("‹") { if (w.canGoBack()) w.goBack() }, lay(WRAP, WRAP, 0f, 2))
            addView(nav("›") { if (w.canGoForward()) w.goForward() }, lay(WRAP, WRAP, 0f, 2))
            addView(url, lay(0, WRAP, 1f, 2))
        }
        col.addView(bar)
        col.addView(w, lay(MATCH, 0, 1f, 2))
        web = w
        return col
    }

    // ---------- alat: Chat (memakai API key Gemini) ----------

    private fun chatView(): View {
        val col = vbox()
        val list = vbox().apply { setPadding(dp(2), dp(4), dp(2), dp(4)) }
        val scroll = ScrollView(c).apply { addView(list) }
        val empty = note("Tanya apa saja.")
        list.addView(empty)

        fun bubbleOf(role: String, text: String): TextView {
            val mine = role == "user"
            val tv = TextView(c).apply {
                this.text = text
                textSize = 14f
                setTextColor(if (mine) Color.WHITE else P.on)
                setTextIsSelectable(true)
                background = Ui.round(if (mine) accent else P.field, dp(14))
                setPadding(dp(12), dp(8), dp(12), dp(8))
                maxWidth = (panelW() * 0.84f).toInt()
            }
            list.addView(tv, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                gravity = if (mine) Gravity.END else Gravity.START
                setMargins(dp(2), dp(3), dp(2), dp(3))
            })
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            return tv
        }

        val input = field("Tulis pertanyaan", true)
        pasteFns[M_CHAT] = { t ->
            val cur = input.text.toString()
            input.setText(if (cur.isBlank()) t else cur + "\n" + t)
            input.setSelection(input.text.length)
        }
        val send = btn("Kirim", true) {
            val q = input.text.toString().trim()
            if (q.isEmpty()) return@btn
            input.setText("")
            list.removeView(empty)
            chatTurns.add("user" to q)
            bubbleOf("user", q)
            val pending = bubbleOf("model", "…")
            var turns = chatTurns.takeLast(11)
            if (turns.firstOrNull()?.first == "model") turns = turns.drop(1)
            AiClient.generate(c, turns, null, AiClient.CHAT_SYSTEM, false) { r ->
                ui.post {
                    r.onSuccess { t ->
                        pending.text = t
                        chatTurns.add("model" to t)
                    }.onFailure { e ->
                        pending.text = e.message ?: "Gagal"
                        chatTurns.removeLastOrNull()
                    }
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
        col.addView(scroll, lay(MATCH, 0, 1f, 2))
        col.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(input, lay(0, WRAP, 1f, 2))
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(send, lay(WRAP, WRAP, 0f, 2))
                // setText dari kode, tanpa fokus: aplikasi di bawah jendela tetap memegang fokus
                addView(btn("Hapus") { input.setText("") }, lay(WRAP, WRAP, 0f, 2))
            })
        })
        return col
    }

    // ---------- alat: Hitung ----------

    private fun calcView(): View {
        val col = vbox()
        val expr = TextView(c).apply {
            textSize = 18f
            gravity = Gravity.END
            setTextColor(P.muted)
            minHeight = dp(28)
            setPadding(dp(4), 0, dp(4), 0)
        }
        val res = TextView(c).apply {
            text = "0"
            textSize = 32f
            gravity = Gravity.END
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextColor(P.on)
            setPadding(dp(4), 0, dp(4), dp(6))
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
        val ops = setOf("÷", "×", "-", "+", "^", "%", "√", "(", ")", "π")
        for (r in rows) {
            val line = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in r) {
                val equals = k == "="
                val op = k in ops
                line.addView(TextView(c).apply {
                    text = k
                    gravity = Gravity.CENTER
                    textSize = 18f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(
                        when {
                            equals -> Color.WHITE
                            k == "C" -> 0xFFDC2626.toInt()
                            op -> accent
                            else -> P.on
                        }
                    )
                    background = Ui.ripple(
                        Ui.round(if (equals) accent else P.field, dp(12)), P.night
                    )
                    isClickable = true
                    setOnClickListener { press(k) }
                }, lay(0, dp(46), 1f, 3))
            }
            col.addView(line)
        }
        return scrolled(col)
    }

    // ---------- alat: Klik (auto clicker) ----------

    private fun clickView(): View {
        val col = vbox()
        val status = note("")
        val interval = field("Jeda antar ketukan (ms)").apply {
            setText("1000")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val count = field("Jumlah ketukan (0 = tanpa batas)").apply {
            setText("0")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        fun refresh() {
            status.text = when {
                ClickService.instance == null ->
                    "Aksesibilitas belum aktif. Berikan izin di aplikasi flooww."
                target == null ->
                    "Pasang target dulu."
                else ->
                    "Target siap."
            }
        }

        col.addView(status)
        col.addView(btn("Pasang / hapus target") {
            if (target == null) showTarget() else removeTarget()
            refresh()
        }, lay(MATCH, WRAP, 0f, 3))
        col.addView(interval, lay(MATCH, WRAP, 0f, 3))
        col.addView(count, lay(MATCH, WRAP, 0f, 3))
        col.addView(btn("Mulai", true) {
            val ms = (interval.text.toString().toLongOrNull() ?: 1000L).coerceAtLeast(50L)
            val n = count.text.toString().toIntOrNull() ?: 0
            if (!startClicking(ms, n)) refresh()
        }, lay(MATCH, WRAP, 0f, 3))
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
            OVERLAY_FLAGS,
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
        applyBubble()
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
        applyBubble()
    }

    // ---------- alat: Apps (aplikasi HP sebagai jendela mengambang) ----------

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

    private fun globalOn(key: String): Boolean = try {
        Settings.Global.getInt(contentResolver, key, 0) == 1
    } catch (e: Exception) {
        false
    }

    private fun freeformOn(): Boolean =
        packageManager.hasSystemFeature("android.software.freeform_window_management") ||
            globalOn("enable_freeform_support")

    private fun freeformStatus(): String {
        val ff = if (freeformOn()) "aktif" else "BELUM aktif (restart HP setelah mengaktifkan)"
        val rs = if (globalOn("force_resizable_activities")) "aktif" else "BELUM aktif"
        return "Jendela bentuk bebas: $ff\nPaksa aktivitas dapat diubah ukuran: $rs"
    }

    /** Buka aplikasi dengan ukuran jendela sesuai pengaturan. Butuh mode jendela bebas di HP. */
    private fun launchApp(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) {
            toast("Aplikasi tidak bisa dibuka")
            return
        }
        startFloating(i)
    }

    /** Semua layar lain yang dibuka dari flooww (aplikasi, pengaturan, dll.) lewat sini agar tidak layar penuh. */
    private fun startFloating(i: Intent) {
        // MULTIPLE_TASK: tanpa ini, aplikasi yang sudah berjalan hanya dibawa ke depan
        // di jendela lamanya dan ukuran jendela diabaikan.
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val (sw, sh) = realSize()
        val w = minOf(dp(pref(Setting.APP_W)), sw - dp(16))
        val h = minOf(dp(pref(Setting.APP_H)), sh - dp(96))
        val l = (sw - w) / 2
        val t = dp(72)
        hidePanel()
        try {
            val o = ActivityOptions.makeBasic().setLaunchBounds(Rect(l, t, l + w, t + h))
            // 5 = WINDOWING_MODE_FREEFORM. API tersembunyi, dibuka lewat HiddenApiBypass.
            var forced = false
            try {
                ActivityOptions::class.java
                    .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                    .invoke(o, 5)
                forced = true
            } catch (e: Throwable) {
            }
            startActivity(i, o.toBundle())
            when {
                !freeformOn() -> toast("Mode jendela bebas belum aktif")
                !forced -> toast("Jendela bebas diblokir HP")
            }
        } catch (e: Exception) {
            toast("Gagal membuka: ${e.message}")
        }
    }

    private fun appsView(): View {
        val col = vbox()
        val title = note("")
        val grid = vbox()
        val status = TextView(c).apply {
            text = freeformStatus()
            textSize = 12f
            setTextColor(P.on)
            setPadding(dp(2), dp(6), dp(2), dp(2))
        }

        fun cell(app: AppItem, tap: () -> Unit, long: () -> Unit): View =
            LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(2), dp(6), dp(2), dp(6))
                addView(ImageView(c).apply { setImageDrawable(app.icon) }, LinearLayout.LayoutParams(dp(40), dp(40)))
                addView(TextView(c).apply {
                    text = app.label
                    textSize = 10.5f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setTextColor(P.on)
                })
                setOnClickListener { tap() }
                setOnLongClickListener { long(); true }
            }

        fun render() {
            grid.removeAllViews()
            val all = allApps
            if (all == null) {
                title.text = "Memuat daftar aplikasi…"
                loadApps { render() }
                return
            }
            val items = Prefs.pinned(c).mapNotNull { p -> all.find { it.pkg == p } }
            status.text = freeformStatus()
            title.text = if (items.isEmpty()) {
                "Belum ada aplikasi."
            } else {
                ""
            }
            items.chunked(4).forEach { chunk ->
                val line = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
                chunk.forEach { app ->
                    line.addView(
                        cell(app, { launchApp(app.pkg) }, {
                            Prefs.setPinned(c, Prefs.pinned(c) - app.pkg)
                            toast("${app.label} dihapus dari menu")
                            render()
                        }),
                        LinearLayout.LayoutParams(0, WRAP, 1f)
                    )
                }
                repeat(4 - chunk.size) { line.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f)) }
                grid.addView(line)
            }
        }

                col.addView(status)
        col.addView(title)
        col.addView(grid)
        render()
        return scrolled(col)
    }

    // ---------- Auto Jawab ----------

    private fun startAuto() {
        if (Prefs.key(c).isBlank()) {
            toast("Isi API key Gemini di aplikasi flooww dulu")
            startFloating(Intent(this, MainActivity::class.java))
            return
        }
        val g = grab
        if (g == null || !g.active) {
            needProjection()
            return
        }
        hidePanel()
        autoOn = true
        autoGen++
        autoState = 0
        autoPrev = null
        autoBase = null
        autoLastEnd = 0L
        autoGap = 8000L

        val v = MarksView(c)
        val lp = WindowManager.LayoutParams(
            MATCH, MATCH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                OVERLAY_FLAGS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(v, lp)
        marks = v
        v.setChip("Auto Jawab aktif. Ketuk ✕ untuk mematikan.")
        applyBubble()
        // bubble dipindah ke atas lapisan tanda supaya tetap terlihat dan bisa diketuk
        bubble?.let {
            try {
                wm.removeView(it)
                wm.addView(it, bubbleLp)
            } catch (e: Exception) {
            }
        }
        ui.postDelayed(autoRunner, 800)
    }

    private fun stopAuto() {
        if (!autoOn && marks == null) return
        autoOn = false
        autoGen++
        ui.removeCallbacks(autoRunner)
        marks?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        marks = null
        applyBubble()
        toast("Auto Jawab dimatikan")
    }

    private fun sigDiff(a: IntArray, b: IntArray): Double {
        val n = minOf(a.size, b.size)
        if (n == 0) return 0.0
        var s = 0L
        for (i in 0 until n) s += abs(a[i] - b[i])
        return s.toDouble() / n
    }

    private fun autoTick() {
        val g = grab
        if (g == null || !g.active) {
            stopAuto()
            return
        }
        val (w, h) = realSize()
        g.resizeIfNeeded(w, h)
        val sig = g.signature() ?: return
        when (autoState) {
            2 -> {
                val base = autoBase
                if (base == null) {
                    autoBase = sig
                } else if (sigDiff(sig, base) > 3.0) {
                    // layar berubah (geser, pindah soal): hapus tanda lama dan tunggu layar tenang
                    marks?.clear()
                    autoState = 0
                    autoPrev = null
                }
            }
            0 -> {
                val prev = autoPrev
                autoPrev = sig
                val calm = prev != null && sigDiff(sig, prev) < 1.0
                val waited = System.currentTimeMillis() - autoLastEnd >= autoGap
                if (calm && waited) scanNow()
            }
        }
    }

    private fun scanNow() {
        val gen = autoGen
        val bmp = grab?.latest() ?: return
        autoState = 1
        marks?.setChip("Membaca layar…")
        val b64 = toJpegB64(bmp)
        AiClient.generate(
            c, listOf("user" to AutoAnswer.PROMPT), b64, AutoAnswer.SYSTEM, true
        ) { r ->
            ui.post {
                if (!autoOn || gen != autoGen) return@post
                autoLastEnd = System.currentTimeMillis()
                r.onSuccess { raw ->
                    val res = try {
                        AutoAnswer.parse(raw)
                    } catch (e: Exception) {
                        AnswerResult(emptyList(), emptyList())
                    }
                    if (res.isEmpty) {
                        autoGap = minOf(autoGap * 2, 60000L) // hemat kuota saat tidak ada soal
                        marks?.showResult(null, "Tidak ada soal di layar")
                    } else {
                        autoGap = 8000L
                        marks?.showResult(res, null)
                    }
                    autoState = 2
                    autoBase = null
                }.onFailure { e ->
                    val code = (e as? AiError)?.code ?: 0
                    if (code == 400 || code == 401 || code == 403) {
                        toast(e.message ?: "API key bermasalah")
                        stopAuto()
                    } else {
                        if (code == 429) autoLastEnd = System.currentTimeMillis() + 25000L
                        marks?.showResult(null, e.message ?: "Gagal membaca layar")
                        autoState = 0
                        autoPrev = null
                    }
                }
            }
        }
    }
}


/** Tata letak jendela alat yang menangkap sentuhan di tepinya untuk mengubah ukuran. */
class EdgeLayout(ctx: android.content.Context) : LinearLayout(ctx) {
    var edge = 40
    var onEdge: ((mask: Int, phase: Int, dx: Float, dy: Float) -> Unit)? = null
    private var mask = 0
    private var tx = 0f
    private var ty = 0f

    private fun maskAt(x: Float, y: Float): Int {
        var m = 0
        if (x < edge) m = m or 1
        if (y < edge) m = m or 2
        if (x > width - edge) m = m or 4
        if (y > height - edge) m = m or 8
        return m
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN) {
            val m = maskAt(e.x, e.y)
            // sudut atas dibiarkan untuk header (geser) dan tombol; hanya tepi atas paling tipis yang menangkap
            if (m != 0) {
                mask = m
                tx = e.rawX
                ty = e.rawY
                onEdge?.invoke(mask, 0, 0f, 0f)
                return true
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (mask == 0) return false
        when (e.action) {
            MotionEvent.ACTION_MOVE -> onEdge?.invoke(mask, 1, e.rawX - tx, e.rawY - ty)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                onEdge?.invoke(mask, 2, 0f, 0f)
                mask = 0
            }
        }
        return true
    }
}
