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
        private const val M_CALC = 5
        private const val M_CLICK = 6
        private const val M_APPS = 7
        private const val M_NOTE = 8
        private const val M_GAL = 9
        private const val M_OFF = 10

        private val NAMES = listOf(
            "Tanya AI", "Salin", "Terjemah", "Screenshot", "Browser",
            "Hitung", "Klik", "Apps", "Catatan", "Galeri", "Matikan"
        )
        private val ICONS = intArrayOf(
            R.drawable.ic_ask, R.drawable.ic_copy, R.drawable.ic_translate, R.drawable.ic_shot,
            R.drawable.ic_browser, R.drawable.ic_calc, R.drawable.ic_touch,
            R.drawable.ic_apps, R.drawable.ic_note, R.drawable.ic_image, R.drawable.ic_power
        )

        // alat yang mengambil soal dari layar: lingkaran pilih area muncul dulu, jendela sesudahnya
        private val CIRCLE_FIRST = setOf(M_ASK, M_COPY, M_TR)

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

    private lateinit var copyOut: TextView

    // auto clicker
    private var target: TextView? = null
    private var targetLp: WindowManager.LayoutParams? = null
    private var clicking = false
    private var clicksDone = 0
    private var maxClicks = 0
    private var intervalMs = 1000L

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
        if (!License.ok(this)) {
            Toast.makeText(this, "flooww belum diaktifkan. Buka aplikasi untuk memasukkan lisensi.", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        // wm harus siap sebelum dipakai (realSize, bubble)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
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
        try {
            nm.deleteNotificationChannel("float")
        } catch (e: Exception) {
        }
        // paling kecil: tanpa suara, tanpa ikon di bar status, hanya baris kecil di daftar notifikasi
        nm.createNotificationChannel(
            NotificationChannel("float_min", "flooww", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, FloatService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "float_min")
            .setContentTitle("flooww")
            .setOngoing(false)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_delete, "Hentikan", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            val withProj = projection || grab?.active == true
            // Android 14+: bisa specialUse saja sebelum izin tangkap layar ada.
            // Android 10-13: tipe specialUse belum dikenal, pakai mediaProjection seperti biasa.
            val type = if (Build.VERSION.SDK_INT >= 34) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    (if (withProj) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            try {
                startForeground(1, n, type)
            } catch (e: Exception) {
                try {
                    startForeground(1, n)
                } catch (e2: Exception) {
                    toast("Gagal memulai layanan: ${e2.javaClass.simpleName}")
                }
            }
        } else {
            startForeground(1, n)
        }
    }

    override fun onDestroy() {
        Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        ui.removeCallbacksAndMessages(null)
        clicking = false
        if (::wm.isInitialized) {
            hideMenu()
            hidePanel()
            osk.hide()
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

    private fun lay(w: Int = MATCH, h: Int = WRAP, wt: Float = 0f, m: Int = 0) =
        LinearLayout.LayoutParams(w, h, wt).apply { setMargins(dp(m), dp(m), dp(m), dp(m)) }

    private fun btn(label: String, primary: Boolean = false, onClick: () -> Unit): TextView =
        Ui.button(c, label, primary, onClick)

    private fun field(hint: String, multi: Boolean = false) = Ui.field(c, hint, multi).apply {
        inputs.add(this)
        applyKb(this)
        // keyboard flooww: ketuk kolom -> keyboard muncul di dasar layar tanpa mengambil fokus
        selectable(this)
    }

    // ---------- blok teks dan salin tanpa fokus ----------

    private var selTv: TextView? = null
    private var selA = 0
    private var selB = 0
    private var selSpan: android.text.style.BackgroundColorSpan? = null

    private fun clearSel() {
        val tv = selTv
        val sp = selSpan
        if (tv != null && sp != null) (tv.text as? android.text.Spannable)?.removeSpan(sp)
        selTv = null
        selSpan = null
        selA = 0
        selB = 0
    }

    private fun setSel(tv: TextView, a: Int, b: Int) {
        if (selTv !== tv) clearSel()
        if (tv.text !is android.text.Spannable) tv.setText(tv.text, TextView.BufferType.SPANNABLE)
        val sp = tv.text as android.text.Spannable
        selSpan?.let { sp.removeSpan(it) }
        val lo = minOf(a, b).coerceIn(0, sp.length)
        val hi = maxOf(a, b).coerceIn(0, sp.length)
        val span = android.text.style.BackgroundColorSpan((accent and 0x00FFFFFF) or 0x66000000)
        if (hi > lo) sp.setSpan(span, lo, hi, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        selTv = tv
        selA = lo
        selB = hi
        selSpan = span
    }

    private fun selRange(tv: TextView): IntRange? {
        if (selTv !== tv) return null
        val n = tv.text.length
        val a = selA.coerceIn(0, n)
        val b = selB.coerceIn(0, n)
        return if (b > a) a until b else null
    }

    private fun wordAt(t: CharSequence, o: Int): Pair<Int, Int> {
        if (t.isEmpty()) return 0 to 0
        var a = o.coerceIn(0, t.length - 1)
        var b = a
        fun w(ch: Char) = ch.isLetterOrDigit() || ch == '_'
        if (!w(t[a]) && a > 0 && w(t[a - 1])) {
            a--
            b = a
        }
        if (w(t[a])) {
            while (a > 0 && w(t[a - 1])) a--
            b = a
            while (b < t.length && w(t[b])) b++
        } else b = a + 1
        return a to b
    }

    /** Tahan teks untuk memblok, geser untuk memperluas, lalu salin lewat tombol di keyboard flooww. Tidak butuh fokus jendela. */
    private fun selectable(tv: TextView) {
        val slop = android.view.ViewConfiguration.get(c).scaledTouchSlop
        var sx = 0f
        var sy = 0f
        var active = false
        var aS = 0
        var aE = 0
        val start = Runnable {
            active = true
            val o = tv.getOffsetForPosition(sx, sy)
            val (a, b) = wordAt(tv.text, o)
            aS = a
            aE = b
            setSel(tv, a, b)
            tv.parent?.requestDisallowInterceptTouchEvent(true)
            tv.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            osk.show(TvTarget(tv))
        }
        tv.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = ev.x
                    sy = ev.y
                    active = false
                    if (kbBlocked() || tv !is EditText) tv.postDelayed(start, 450)
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (active) {
                        val o = tv.getOffsetForPosition(ev.x, ev.y)
                        if (o < aS) setSel(tv, o, aE) else setSel(tv, aS, maxOf(o, aE))
                    } else if (Math.abs(ev.x - sx) > slop || Math.abs(ev.y - sy) > slop) tv.removeCallbacks(start)
                    active
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tv.removeCallbacks(start)
                    val was = active
                    active = false
                    if (!was && ev.action == MotionEvent.ACTION_UP) {
                        clearSel()
                        if (tv is EditText && kbBlocked()) osk.show(TvTarget(tv))
                    }
                    was
                }
                else -> active
            }
        }
    }

    private inner class TvTarget(val tv: TextView) : OskTarget {
        private val ed get() = tv as? EditText

        override fun type(s: String) {
            val e = ed ?: return
            val r = selRange(e)
            if (r != null) {
                e.text.replace(r.first, r.last + 1, s)
                e.setSelection(r.first + s.length)
                clearSel()
            } else {
                e.text.append(s)
                e.setSelection(e.text.length)
            }
        }

        override fun backspace() {
            val e = ed ?: return
            val r = selRange(e)
            if (r != null) {
                e.text.delete(r.first, r.last + 1)
                e.setSelection(r.first)
                clearSel()
            } else if (e.text.isNotEmpty()) {
                e.text.delete(e.text.length - 1, e.text.length)
                e.setSelection(e.text.length)
            }
        }

        override fun enter() {
            val e = ed ?: return
            if ((e.inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0) type("\n")
            else {
                e.onEditorAction(EditorInfo.IME_ACTION_GO)
                osk.hide()
            }
        }

        override fun copy() {
            val r = selRange(tv)
            val t = if (r != null) tv.text.substring(r.first, r.last + 1) else tv.text.toString()
            if (t.isBlank()) toast("Tidak ada teks") else copyText(t)
        }

        override fun paste() {
            if (ed == null) {
                toast("Kolom ini tidak bisa ditempel")
                return
            }
            val t = clipText()
            if (t.isBlank()) toast("Belum ada teks") else type(t)
        }
    }

    /** Halaman web: ketikan dan tempel masuk ke kolom yang aktif di halaman lewat skrip. */
    private inner class WebTarget(val w: WebView) : OskTarget {
        private fun js(code: String, cb: ((String?) -> Unit)? = null) = w.evaluateJavascript(code, cb)

        override fun type(s: String) {
            js("document.execCommand('insertText',false," + org.json.JSONObject.quote(s) + ")")
        }

        override fun backspace() {
            js("document.execCommand('delete')")
        }

        override fun enter() {
            js(
                "(function(){var e=document.activeElement;if(!e)return;var o={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true};" +
                    "var d=!e.dispatchEvent(new KeyboardEvent('keydown',o));e.dispatchEvent(new KeyboardEvent('keypress',o));e.dispatchEvent(new KeyboardEvent('keyup',o));" +
                    "if(d)return;if(e.tagName=='TEXTAREA'||e.isContentEditable)document.execCommand('insertLineBreak');" +
                    "else if(e.form){if(e.form.requestSubmit)e.form.requestSubmit();else e.form.submit();}})()"
            )
        }

        override fun copy() {
            js(
                "(function(){var t=String(window.getSelection());var a=document.activeElement;" +
                    "if(!t&&a&&a.value!==undefined){t=a.value.substring(a.selectionStart,a.selectionEnd)||a.value;}return t;})()"
            ) { r ->
                val t = try {
                    org.json.JSONTokener(r ?: "\"\"").nextValue() as? String ?: ""
                } catch (e: Exception) {
                    ""
                }
                ui.post { if (t.isBlank()) toast("Tidak ada teks dipilih") else copyText(t) }
            }
        }

        override fun paste() {
            val t = clipText()
            if (t.isBlank()) toast("Belum ada teks") else type(t)
        }
    }

    /** Jembatan dari halaman web: kolom ketik fokus atau teks diblok, maka keyboard flooww muncul. */
    inner class OskBridge {
        @android.webkit.JavascriptInterface
        fun show() {
            ui.post { web?.let { if (kbBlocked()) osk.show(WebTarget(it)) } }
        }
    }

    private val WEB_SEL_JS = (
        "(function(){if(window.__fsel)return;window.__fsel=1;var t=null,sx=0,sy=0,act=false;" +
            "function ed(el){return el&&(/^(INPUT|TEXTAREA)\$/.test(el.tagName)||el.isContentEditable);}" +
            "function rangeAt(x,y){if(document.caretRangeFromPoint)return document.caretRangeFromPoint(x,y);" +
            "var p=document.caretPositionFromPoint&&document.caretPositionFromPoint(x,y);if(!p)return null;" +
            "var r=document.createRange();r.setStart(p.offsetNode,p.offset);return r;}" +
            "document.addEventListener('touchstart',function(e){var q=e.touches[0];sx=q.clientX;sy=q.clientY;act=false;clearTimeout(t);" +
            "t=setTimeout(function(){var el=document.elementFromPoint(sx,sy);if(ed(el))return;var r=rangeAt(sx,sy);if(!r)return;" +
            "var s=window.getSelection();s.removeAllRanges();s.addRange(r);s.modify('move','backward','word');s.modify('extend','forward','word');" +
            "act=true;if(window.floowwOsk)floowwOsk.show();},450);},{passive:true});" +
            "document.addEventListener('touchmove',function(e){var q=e.touches[0];" +
            "if(!act){if(Math.abs(q.clientX-sx)>10||Math.abs(q.clientY-sy)>10)clearTimeout(t);return;}" +
            "e.preventDefault();var r=rangeAt(q.clientX,q.clientY);if(r)window.getSelection().extend(r.startContainer,r.startOffset);},{passive:false});" +
            "document.addEventListener('touchend',function(){clearTimeout(t);act=false;},{passive:true});" +
            "document.addEventListener('focusin',function(e){if(ed(e.target)&&window.floowwOsk)floowwOsk.show();});})();"
        )

    private fun resultText() = TextView(c).apply {
        textSize = 14f
        setTextColor(P.on)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = Ui.dent(c, P.field, dp(14))
        selectable(this)
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

    private val HOLD_MS = 300L

    private fun toast(t: String) = Toast.makeText(c, t, Toast.LENGTH_SHORT).show()

    private fun makeDraggable(
        handle: View,
        moving: View,
        lp: WindowManager.LayoutParams,
        onTap: (() -> Unit)? = null,
        onDouble: (() -> Unit)? = null,
        onLong: (() -> Unit)? = null,
        holdDrag: Boolean = false,
        onSwipe: ((Int) -> Unit)? = null,
        onDrop: (() -> Unit)? = null
    ) {
        handle.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0
            var sy = 0
            var tx = 0f
            var ty = 0f
            var moved = false      // sudah menggeser bubble
            var armed = !holdDrag  // siap digeser (setelah ditahan sebentar)
            var swiping = false    // jari bergerak sebelum siap: dihitung sapuan
            var longDone = false
            var pendingTap: Runnable? = null
            // gestur hanya berlaku bila dipilih di pengaturan dan tidak sedang ada mode aktif
            fun busy() = clicking || menu != null
            fun longOn() = onLong != null && !busy() && Prefs.gesture(c, 2) != 0
            fun dblOn() = onDouble != null && !busy() && Prefs.gesture(c, 1) != 0
            val longRun = Runnable {
                if (!moved) {
                    longDone = true
                    handle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onLong?.invoke()
                }
            }
            // ditahan sebentar: bubble "terangkat" dan sesudah itu bisa digeser
            val armRun = Runnable {
                if (!swiping) {
                    armed = true
                    handle.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    moving.animate().scaleX(1.12f).scaleY(1.12f).setDuration(90).start()
                }
            }

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY
                        moved = false; longDone = false; swiping = false
                        armed = !holdDrag
                        if (holdDrag) ui.postDelayed(armRun, HOLD_MS)
                        if (longOn()) ui.postDelayed(longRun, pref(Setting.LONG_MS).toLong())
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - tx).toInt()
                        val dy = (e.rawY - ty).toInt()
                        val far = abs(dx) > dp(6) || abs(dy) > dp(6)
                        if (far && !armed) {
                            // bergerak sebelum ditahan: bukan geser bubble, mungkin sapuan
                            swiping = true
                            ui.removeCallbacks(armRun)
                            ui.removeCallbacks(longRun)
                        }
                        if (far && armed) {
                            moved = true
                            ui.removeCallbacks(longRun)
                        }
                        if (moved) {
                            lp.x = sx + dx
                            lp.y = sy + dy
                            wm.updateViewLayout(moving, lp)
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        ui.removeCallbacks(longRun)
                        ui.removeCallbacks(armRun)
                        moving.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                    }
                    MotionEvent.ACTION_UP -> {
                        ui.removeCallbacks(longRun)
                        ui.removeCallbacks(armRun)
                        if (holdDrag) moving.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                        if (moved) {
                            onDrop?.invoke()
                        } else if (swiping && !longDone) {
                            val dx = e.rawX - tx
                            val dy = e.rawY - ty
                            val th = dp(36)
                            // 3 atas, 4 bawah, 5 menuju tengah layar
                            val dir = when {
                                abs(dy) > abs(dx) && dy < -th -> 3
                                abs(dy) > abs(dx) && dy > th -> 4
                                abs(dx) >= abs(dy) && abs(dx) > th -> {
                                    val (sw, _) = realSize()
                                    val onLeft = lp.x + lp.width / 2 < sw / 2
                                    if ((dx > 0) == onLeft) 5 else -1
                                }
                                else -> -1
                            }
                            if (dir > 0) onSwipe?.invoke(dir)
                        } else if (!longDone) {
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
            onLong = { onBubbleGesture(2) },
            holdDrag = true,
            onSwipe = { onBubbleGesture(it) },
            onDrop = { snapBubble() }
        )
        wm.addView(b, lp)
        bubble = b
        bubbleBg = bg
        bubbleLp = lp
        applyBubble()
    }

    private fun onBubbleTap() {
        when {
            clicking -> stopClicking()
            menu != null -> hideMenu()
            else -> runGesture(Prefs.gesture(c, 0))
        }
    }

    /** Ketuk dua kali (1) atau tekan lama (2). Saat auto clicker aktif, gestur ini mematikannya. */
    private fun onBubbleGesture(g: Int) {
        when {
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

    /** Bentuk yang tampil: saat Auto Jawab atau auto clicker aktif selalu bulat supaya tombol berhenti jelas. */
    private fun shapeNow() = if (clicking) 0 else Prefs.shape(c)

    /** Bentuk tepi (panah dan garis) menempel di sisi layar terdekat. */
    private fun snapBubble() {
        val b = bubble ?: return
        val lp = bubbleLp ?: return
        val (sw, sh) = realSize()
        lp.y = lp.y.coerceIn(0, sh - lp.height)
        if (shapeNow() != 0) {
            lp.x = if (lp.x + lp.width / 2 < sw / 2) 0 else sw - lp.width
        } else {
            lp.x = lp.x.coerceIn(0, sw - lp.width)
        }
        try {
            wm.updateViewLayout(b, lp)
        } catch (e: Exception) {
        }
        applyBubble(false)
    }

    /** Bubble polos. Hanya saat Auto Jawab menyala tampil ✕, dan saat auto clicker berjalan tampil ■. */
    private fun applyBubble(snap: Boolean = true) {
        val b = bubble ?: return
        val lp = bubbleLp ?: return
        val sizeDp = pref(Setting.BUBBLE_SIZE)
        val shape = shapeNow()
        val (sw, _) = realSize()
        val onLeft = lp.x + lp.width / 2 < sw / 2
        val col = if (clicking) 0xFFDC2626.toInt() else accent
        // 0 bulat, 1 panah kecil di tepi, 2 garis tepi
        when (shape) {
            1 -> { lp.width = dpf(sizeDp * 0.55f); lp.height = dp(sizeDp) }
            2 -> { lp.width = dp(18); lp.height = dpf(sizeDp * 1.3f) }
            else -> { lp.width = dp(sizeDp); lp.height = dp(sizeDp) }
        }
        val d = GradientDrawable().apply {
            setColor(col)
            when (shape) {
                0 -> { this.shape = GradientDrawable.OVAL; setStroke(dp(2), 0x99FFFFFF.toInt()) }
                1 -> {
                    val r = dpf(sizeDp * 0.3f).toFloat()
                    // sisi yang menghadap tengah layar membulat penuh, sisi tepi rata
                    cornerRadii = if (onLeft) floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                    else floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
                }
                else -> cornerRadius = dp(3).toFloat()
            }
        }
        b.background = if (shape == 2) {
            // batang tipis di tepi, area sentuh tetap lebih lebar
            val gap = dp(12)
            android.graphics.drawable.InsetDrawable(
                d, if (onLeft) 0 else gap, 0, if (onLeft) gap else 0, 0
            )
        } else d
        b.alpha = pref(Setting.BUBBLE_ALPHA) / 100f
        b.text = when {
            clicking -> "■"
            shape == 1 -> if (onLeft) "›" else "‹"
            else -> ""
        }
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (shape == 1) sizeDp * 0.5f else sizeDp * 0.4f)
        if (snap && shape != 0) lp.x = if (onLeft) 0 else sw - lp.width
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
            background = Ui.clay(c, P.surface, dpf(24f * sc), 5f)
            setPadding(pad, pad, pad, pad - gap / 2 + dp(5))
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
                    background = Ui.ripple(Ui.clay(c, Ui.mix(P.surface, accent, 0.22f), dpf(16f * sc), 3f), P.night)
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
            i == M_IMG -> screenshotNow()
            i == M_OFF -> {
                toast("Bubble dimatikan")
                stopSelf()
            }
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
            }
        }
    }

    // ---------- screenshot ----------

    /** Ambil layar penuh (bubble disembunyikan) lalu simpan ke galeri, folder Pictures/flooww. */
    private fun screenshotNow() {
        val g = grab
        if (g == null || !g.active) {
            needProjection()
            return
        }
        pickRegion(true) { bmp ->
            if (bmp == null) {
                toast("Gagal mengambil layar")
                return@pickRegion
            }
            Thread {
                val ok = try {
                    val name = "flooww_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".png"
                    if (Build.VERSION.SDK_INT >= 29) {
                        val cv = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/flooww")
                        }
                        val u = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!
                        contentResolver.openOutputStream(u)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    } else {
                        val d = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "flooww").apply { mkdirs() }
                        val f = File(d, name)
                        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        android.media.MediaScannerConnection.scanFile(this, arrayOf(f.absolutePath), null, null)
                    }
                    true
                } catch (e: Exception) {
                    false
                }
                ui.post { toast(if (ok) "Screenshot disimpan di galeri (Pictures/flooww)" else "Gagal menyimpan screenshot") }
            }.start()
        }
    }

    // ---------- Tanya AI lewat browser mengambang ----------

    private val AI_WEB = listOf("https://gemini.google.com/app", "https://chatgpt.com/", "https://claude.ai/new")
    private val AI_WEB_Q = listOf(null, "https://chatgpt.com/?q=", "https://claude.ai/new?q=")

    // gambar yang akan dilampirkan otomatis saat situs AI membuka pemilih file
    private var pendingUpload: Uri? = null
    private var pendingB64: String? = null
    private var attachDone = false
    private var pendingPrompt: String? = null
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
        attachDone = false
        if (bmp != null) {
            try {
                val dir = File(cacheDir, "shared").apply { mkdirs() }
                val f = File(dir, "ask.jpg")
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                pendingUpload = Uri.fromFile(f)
                pendingB64 = toJpegB64(bmp)
                // cadangan: gambar juga disalin ke clipboard, bisa ditempel lewat tekan lama di kolom pesan
                try {
                    val cu = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
                    (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newUri(contentResolver, "soal", cu))
                } catch (e: Exception) {
                }
            } catch (e: Exception) {
                toast("Gagal menyiapkan gambar: ${e.message}")
            }
        }
        lastCopied = prompt
        val base = AI_WEB_Q[target]
        // ada gambar: buka halaman polos, lampirkan gambar dulu, baru isi perintah lalu kirim
        pendingPrompt = if (bmp != null) prompt else null
        val url = if (base != null && bmp == null) base + URLEncoder.encode(prompt.take(1500), "UTF-8") else AI_WEB[target]
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
    private var attachRun: Runnable? = null

    private val COUNT_JS = "(function(){return document.querySelectorAll('img[src^=\"blob:\"],img[src^=\"data:\"],canvas').length;})()"

    /**
     * Lampirkan gambar secepat mungkin tapi tetap aman: tunggu kolom pesan ada dan halaman tenang sebentar (0,7 detik),
     * tempel gambar, cek bahwa gambar benar muncul (miniatur atau nama berkas), lalu langsung isi perintah dan kirim.
     */
    private fun autoAttach(tries: Int = 50) {
        attachRun?.let { ui.removeCallbacks(it) }
        val w = web ?: return
        if (pendingB64 == null || tries <= 0 || attachDone) return
        if (tries == 50) {
            w.evaluateJavascript(
                "(function(){if(!window.__fq){window.__fq=Date.now();" +
                    "try{new MutationObserver(function(){window.__fq=Date.now();}).observe(document,{subtree:true,childList:true,attributes:true});}catch(e){}}})()",
                null
            )
        }
        val r = Runnable {
            if (pendingB64 == null || attachDone) return@Runnable
            w.evaluateJavascript(
                "(function(){var e=document.querySelector('textarea,[contenteditable],[role=textbox],input[type=file]');" +
                    "if(!e||document.readyState=='loading')return -1;return window.__fq?(Date.now()-window.__fq):9999;})()"
            ) { v ->
                val quiet = v?.trim()?.toIntOrNull() ?: -1
                if (quiet >= 700 || (tries <= 2 && quiet >= 0)) {
                    attachDone = true
                    w.evaluateJavascript(COUNT_JS) { base ->
                        val b0 = base?.trim()?.toIntOrNull() ?: 0
                        attachAndVerify(b0, 0)
                    }
                } else {
                    autoAttach(tries - 1)
                }
            }
        }
        attachRun = r
        ui.postDelayed(r, 400)
    }

    /** Tempel gambar, lalu cek tiap 0,3 detik sampai muncul. Bila tidak muncul dalam 3 detik, ulangi sekali. */
    private fun attachAndVerify(base: Int, round: Int) {
        val w = web ?: return
        injectImage { code ->
            if (code == 0 && round < 3) {
                ui.postDelayed({ attachAndVerify(base, round + 1) }, 500)
                return@injectImage
            }
            toast("Melampirkan gambar…")
            var waited = 0
            val check = object : Runnable {
                override fun run() {
                    w.evaluateJavascript(
                        "(function(b){var n=document.querySelectorAll('img[src^=\"blob:\"],img[src^=\"data:\"],canvas').length;" +
                            "var t=document.body&&document.body.innerText.indexOf('soal.jpg')>=0;return(n>b||t)?1:0;})($base)"
                    ) { v ->
                        val ok = v?.trim() == "1"
                        waited += 300
                        when {
                            ok -> fillAndSend()
                            waited >= 3000 && round < 1 -> attachAndVerify(base, round + 1)
                            waited >= 6000 -> fillAndSend()
                            else -> ui.postDelayed(this, 300)
                        }
                    }
                }
            }
            ui.postDelayed(check, 300)
        }
    }

    /** Isi perintah ke kolom pesan lalu kirim begitu tombol kirim aktif (dicek tiap 0,25 detik). */
    private fun fillAndSend() {
        val p = pendingPrompt ?: return
        pendingPrompt = null
        val q = org.json.JSONObject.quote(p.take(3000))
        web?.evaluateJavascript(
            "(function(){var e=document.querySelector('rich-textarea [contenteditable],div.ql-editor,#prompt-textarea,textarea,[contenteditable=true],[role=textbox]');" +
                "if(!e)return 0;e.focus();var t=$q;" +
                "if(e.tagName=='TEXTAREA'){var d=Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value');d.set.call(e,t);e.dispatchEvent(new Event('input',{bubbles:true}));}" +
                "else{document.execCommand('selectAll',false,null);if(!document.execCommand('insertText',false,t)){e.textContent=t;e.dispatchEvent(new Event('input',{bubbles:true}));}}" +
                "var n=0;var iv=setInterval(function(){n++;var b=document.querySelector('button[data-testid=send-button],button[aria-label*=Send],button[aria-label*=Kirim],button[aria-label*=send],button.send-button');" +
                "if(b&&!b.disabled&&b.getAttribute('aria-disabled')!='true'){clearInterval(iv);b.click();}else if(n>60)clearInterval(iv);},250);return 1;})()",
            null
        )
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
        M_CALC -> calcView()
        M_CLICK -> clickView()
        M_APPS -> appsView()
        M_NOTE -> noteView()
        M_GAL -> galleryView()
        else -> vbox()
    }

    /** Tombol ✕: sembunyikan saja. Isi jendela tidak dihapus. */
    private fun hidePanel() {
        if (osk.isShown) osk.hide()
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

    // keyboard layar flooww (jendela sendiri, tidak pernah fokus)
    private val osk by lazy {
        Osk(c, wm).also { k ->
            k.onHeight = { h -> liftPanel(h) }
            k.onHidden = {
                clearSel()
                web?.evaluateJavascript("(function(){var s=window.getSelection();if(s)s.removeAllRanges();})()", null)
            }
        }
    }
    private var liftedFrom = -1
    private var liftedTo = -1

    /** Naikkan jendela alat bila tertutup keyboard; kembalikan saat keyboard hilang. */
    private fun liftPanel(h: Int) {
        val p = panel ?: return
        val lp = panelLp ?: return
        if (!panelAttached) return
        if (h > 0) {
            val maxY = (realSize().second - h - lp.height).coerceAtLeast(0)
            if (lp.y > maxY) {
                liftedFrom = lp.y
                liftedTo = maxY
                lp.y = maxY
                try { wm.updateViewLayout(p, lp) } catch (e: Exception) {}
            }
        } else if (liftedFrom >= 0) {
            if (lp.y == liftedTo) {
                lp.y = liftedFrom
                try { wm.updateViewLayout(p, lp) } catch (e: Exception) {}
            }
            liftedFrom = -1
        }
    }

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
        if (b) e.clearFocus() else if (osk.isShown) osk.hide()
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
            background = Ui.clay(c, P.surface, dp(24), 6f)
            setPadding(dp(12), dp(6), dp(12), dp(10))
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
            background = Ui.clay(c, Ui.mix(P.surface, accent, 0.22f), dp(11), 2f)
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
                        if (r != 3) pendingUpload = null
                        refreshAttach()
                        toast("Gambar soal terlampir")
                        fillAndSend()
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
    private var trRev = false  // false: bahasa asing ke Indonesia, true: Indonesia ke bahasa asing
    private var trJa = false   // bahasa asing: false Inggris, true Jepang

    private fun trForeignName() = if (trJa) "Jepang" else "Inggris"
    private fun trForeign(): String = if (trJa) com.google.mlkit.nl.translate.TranslateLanguage.JAPANESE else com.google.mlkit.nl.translate.TranslateLanguage.ENGLISH
    private fun trDirName() = if (trRev) "Indonesia → ${trForeignName()}" else "${trForeignName()} → Indonesia"
    private fun trFrom(): String = if (trRev) com.google.mlkit.nl.translate.TranslateLanguage.INDONESIAN else trForeign()
    private fun trTo(): String = if (trRev) trForeign() else com.google.mlkit.nl.translate.TranslateLanguage.INDONESIAN

    private fun trView(): View {
        val col = vbox()
        val out = resultText().apply {
            text = "Lingkari teks di layar."
        }
        trOut = out
        val dir = btn("", false) {}
        dir.text = "⇄  ${trDirName()}"
        dir.setOnClickListener {
            trRev = !trRev
            dir.text = "⇄  ${trDirName()}"
            // membalik hasil yang sudah ada: hasil jadi teks yang diterjemahkan ke arah sebaliknya
            if (trText.isNotBlank()) translateText(trText, trFrom(), trTo(), out)
        }
        val lang = btn("", false) {}
        lang.text = "Bahasa asing: ${trForeignName()}"
        lang.setOnClickListener {
            trJa = !trJa
            lang.text = "Bahasa asing: ${trForeignName()}"
            dir.text = "⇄  ${trDirName()}"
            trText = ""
            out.text = "Lingkari teks di layar."
        }
        col.addView(row(dir))
        col.addView(row(lang))
        col.addView(
            row(
                btn("Lingkari area", true) { withRegion(false, out) { runTranslate(it) } },
                btn("Layar penuh") { withRegion(true, out) { runTranslate(it) } }
            )
        )
        addOut(col, out)
        col.addView(
            row(
                btn("Salin hasil") { if (trText.isNotBlank()) copyText(trText) else toast("Belum ada hasil") },
                btn("Simpan ke catatan") { if (trText.isNotBlank()) addNote(trText) else toast("Belum ada hasil") }
            )
        )
        return col
    }

    /** Terjemah di HP: Inggris dan Indonesia saja (model kecil, ringan), arah bisa dibalik. */
    private fun runTranslate(bmp: Bitmap) {
        val out = trOut ?: return
        out.text = "Membaca teks…"
        Ocr.read(bmp, japanese = trJa && !trRev) { r ->
            ui.post {
                r.onFailure { out.text = "Error: ${it.message}" }
                r.onSuccess { src ->
                    if (src.isBlank()) out.text = "Tidak ada teks terbaca."
                    else translateText(src, trFrom(), trTo(), out)
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
        col.addView(row(btn("Simpan ke catatan") { if (ocrText.isNotBlank()) addNote(ocrText) else toast("Belum ada teks") }))
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
            addJavascriptInterface(OskBridge(), "floowwOsk")
            // blok teks dibuat sendiri (lihat WEB_SEL_JS); menu bawaan WebView butuh fokus jendela
            isLongClickable = false
            setOnLongClickListener { true }
            isHapticFeedbackEnabled = false
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
                    view?.evaluateJavascript(WEB_SEL_JS, null)
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

    // ---------- alat: Catatan cepat ----------

    private var noteRefresh: (() -> Unit)? = null

    private fun addNote(t: String) {
        val x = t.trim()
        if (x.isEmpty()) {
            toast("Teks kosong")
            return
        }
        Prefs.setNotes(c, listOf(System.currentTimeMillis() to x) + Prefs.notes(c))
        noteRefresh?.invoke()
        toast("Disimpan ke catatan")
    }

    private fun noteView(): View {
        val col = vbox()
        val input = field("Tulis catatan", true)
        // keyboard diblokir: teks terakhir hasil Salin/Terjemah bisa ditempel lewat tombol tempel di header
        pasteFns[M_NOTE] = { t ->
            val cur = input.text.toString()
            input.setText(if (cur.isBlank()) t else cur + "\n" + t)
            input.setSelection(input.text.length)
        }
        val list = vbox().apply { setPadding(dp(2), dp(2), dp(2), dp(2)) }
        val fmt = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale("id"))

        fun render() {
            list.removeAllViews()
            val items = Prefs.notes(c)
            if (items.isEmpty()) {
                list.addView(note("Belum ada catatan. Tulis di atas lalu Simpan, atau pakai tombol Simpan ke catatan di menu Salin dan Terjemah."))
                return
            }
            items.forEach { n ->
                val card = vbox().apply {
                    background = Ui.clay(c, P.surface, dp(14), 3f)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                }
                card.addView(TextView(c).apply {
                    text = fmt.format(java.util.Date(n.first))
                    textSize = 11f
                    setTextColor(P.muted)
                })
                card.addView(TextView(c).apply {
                    text = n.second
                    textSize = 14f
                    setTextColor(P.on)
                    selectable(this)
                })
                card.addView(row(
                    btn("Salin") { copyText(n.second) },
                    btn("Hapus") {
                        Prefs.setNotes(c, Prefs.notes(c).filter { it.first != n.first })
                        render()
                    }
                ))
                list.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) })
            }
        }
        noteRefresh = { render() }
        render()

        col.addView(input, lay(MATCH, WRAP, 0f, 2))
        col.addView(
            row(
                btn("Simpan", true) {
                    val t = input.text.toString()
                    if (t.isNotBlank()) {
                        input.setText("")
                        addNote(t)
                    }
                },
                btn("Kosongkan") { input.setText("") }
            )
        )
        col.addView(ScrollView(c).apply { addView(list) }, lay(MATCH, 0, 1f, 2))
        return col
    }

    // ---------- alat: Galeri mengambang ----------

    private val galPool by lazy { java.util.concurrent.Executors.newFixedThreadPool(3) }
    private val galCache = android.util.LruCache<Long, Bitmap>(80)

    private fun galGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                (Build.VERSION.SDK_INT >= 34 &&
                    checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == android.content.pm.PackageManager.PERMISSION_GRANTED)
        } else {
            checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    private fun galIds(offset: Int, limit: Int): List<Long> {
        val q = android.os.Bundle().apply {
            putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit)
            putInt(android.content.ContentResolver.QUERY_ARG_OFFSET, offset)
            putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(android.provider.MediaStore.Images.Media.DATE_ADDED))
            putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
        }
        val out = mutableListOf<Long>()
        try {
            contentResolver.query(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(android.provider.MediaStore.Images.Media._ID), q, null
            )?.use { cur -> while (cur.moveToNext()) out.add(cur.getLong(0)) }
        } catch (e: Exception) {
        }
        return out
    }

    private fun galUri(id: Long) =
        android.content.ContentUris.withAppendedId(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

    @Suppress("DEPRECATION")
    private fun galThumb(id: Long): Bitmap? {
        galCache.get(id)?.let { return it }
        val b = try {
            if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(galUri(id), android.util.Size(240, 240), null)
            else android.provider.MediaStore.Images.Thumbnails.getThumbnail(
                contentResolver, id, android.provider.MediaStore.Images.Thumbnails.MINI_KIND, null
            )
        } catch (e: Exception) {
            null
        }
        if (b != null) galCache.put(id, b)
        return b
    }

    /** Gambar penuh, diperkecil agar muat memori (sisi terpanjang sekitar 1600 px). */
    private fun galFull(id: Long): Bitmap? = try {
        val u = galUri(id)
        if (Build.VERSION.SDK_INT >= 28) {
            android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(contentResolver, u)) { dec, info, _ ->
                dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                val big = maxOf(info.size.width, info.size.height)
                if (big > 1600) dec.setTargetSampleSize(big / 1600 + 1)
            }
        } else {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(u)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
            val big = maxOf(o.outWidth, o.outHeight)
            val o2 = android.graphics.BitmapFactory.Options().apply { inSampleSize = if (big > 1600) big / 1600 + 1 else 1 }
            contentResolver.openInputStream(u)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o2) }
        }
    } catch (e: Throwable) {
        null
    }

    private fun galleryView(): View {
        val root = FrameLayout(c)
        val listPane = vbox()
        val viewPane = vbox().apply { visibility = View.GONE }
        root.addView(listPane, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(viewPane, FrameLayout.LayoutParams(MATCH, MATCH))

        val ids = mutableListOf<Long>()
        val grid = vbox().apply { setPadding(dp(2), dp(2), dp(2), dp(2)) }
        var loadFn: () -> Unit = {}
        val more = btn("Muat lebih banyak") { loadFn() }
        val msg = note("")

        // ---- penampil gambar ----
        val img = ImageView(c).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        var cur = 0
        fun showImg(i: Int) {
            if (ids.isEmpty()) return
            cur = ((i % ids.size) + ids.size) % ids.size
            val id = ids[cur]
            img.setImageBitmap(galThumb(id))
            galPool.execute {
                val b = galFull(id)
                ui.post { if (b != null && ids.getOrNull(cur) == id) img.setImageBitmap(b) }
            }
        }
        viewPane.addView(img, lay(MATCH, 0, 1f, 2))
        viewPane.addView(
            row(
                btn("‹") { showImg(cur - 1) },
                btn("›") { showImg(cur + 1) },
                btn("Salin teks") {
                    val d = img.drawable as? android.graphics.drawable.BitmapDrawable
                    val b = d?.bitmap
                    if (b == null) toast("Gambar belum siap")
                    else Ocr.read(b) { r ->
                        ui.post {
                            r.onSuccess { t -> if (t.isBlank()) toast("Tidak ada teks terbaca") else copyText(t) }
                            r.onFailure { toast("Gagal membaca teks") }
                        }
                    }
                },
                btn("Tutup", true) {
                    viewPane.visibility = View.GONE
                    listPane.visibility = View.VISIBLE
                }
            )
        )

        fun addRows(from: Int) {
            val n = ids.size
            var i = from
            while (i < n) {
                val line = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
                for (k in 0 until 3) {
                    val idx = i + k
                    if (idx < n) {
                        val id = ids[idx]
                        val iv = ImageView(c).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            setBackgroundColor(P.field)
                            setOnClickListener {
                                listPane.visibility = View.GONE
                                viewPane.visibility = View.VISIBLE
                                showImg(idx)
                            }
                        }
                        line.addView(iv, LinearLayout.LayoutParams(0, dp(96), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
                        galPool.execute {
                            val b = galThumb(id)
                            ui.post { if (b != null) iv.setImageBitmap(b) }
                        }
                    } else {
                        line.addView(View(c), LinearLayout.LayoutParams(0, dp(96), 1f))
                    }
                }
                grid.addView(line)
                i += 3
            }
        }

        fun loadPage() {
            if (!galGranted()) {
                msg.text = "Izin akses foto belum diberikan. Tekan Beri izin, setujui, lalu Muat ulang."
                return
            }
            val from = ids.size
            val got = galIds(from, 60)
            if (got.isEmpty() && from == 0) msg.text = "Tidak ada foto."
            else msg.text = ""
            ids.addAll(got)
            addRows(from - from % 3)
            more.visibility = if (got.size < 60) View.GONE else View.VISIBLE
        }
        loadFn = { loadPage() }

        listPane.addView(
            row(
                btn("Beri izin") { startFloating(Intent(c, MainActivity::class.java)) },
                btn("Muat ulang") {
                    ids.clear()
                    grid.removeAllViews()
                    loadPage()
                }
            )
        )
        listPane.addView(msg)
        listPane.addView(ScrollView(c).apply {
            addView(vbox().apply {
                addView(grid)
                addView(more)
            })
        }, lay(MATCH, 0, 1f, 2))
        loadPage()
        return root
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
                        Ui.clay(c, if (equals) accent else P.surface, dp(14), 3f), P.night
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
                    "Aksesibilitas belum aktif."
                target == null ->
                    "Pasang target dulu."
                else ->
                    "Target siap."
            }
        }

        col.addView(status)
        col.addView(btn("Buka pengaturan aksesibilitas") {
            startFloating(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, lay(MATCH, WRAP, 0f, 3))
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
        val devBtn = btn("Mode jendela") {
            try {
                startFloating(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (e: Exception) {
                toast("Buka Pengaturan > Opsi pengembang secara manual")
            }
        }
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

                col.addView(row(devBtn))
        col.addView(status)
        col.addView(title)
        col.addView(grid)
        render()
        return scrolled(col)
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
