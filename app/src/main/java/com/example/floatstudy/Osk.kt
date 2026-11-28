package com.example.floatstudy

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** Tujuan ketikan keyboard flooww: kolom teks, teks yang dipilih, atau halaman web. */
interface OskTarget {
    fun type(s: String)
    fun backspace()
    fun enter()
    fun copy()
    fun paste()
}

/**
 * Keyboard layar buatan flooww. Berupa jendela overlay sendiri di dasar layar yang tidak pernah menerima fokus,
 * jadi aplikasi di bawahnya tetap fokus. Mengetik langsung mengubah teks di EditText target.
 */
class Osk(private val c: Context, var wm: WindowManager, var type: Int = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) {
    private val ui = Handler(Looper.getMainLooper())
    private val pal get() = Ui.pal(c)
    private var view: LinearLayout? = null
    private var target: OskTarget? = null
    private var shift = false
    private var symbols = false
    private var attached = false
    private var hideJob: Runnable? = null

    /** Satu tuts yang bisa ditekan: tampilan, tulisan pratinjau, apakah pratinjau muncul, dan aksinya. */
    private class KeyDef(val view: TextView, val label: String, val preview: Boolean, val click: () -> Unit)

    private val keyDefs = mutableListOf<KeyDef>()
    private var active: KeyDef? = null
    private var pv: TextView? = null
    private var pvAttached = false

    /** Dipanggil dengan tinggi keyboard (px) saat tampil, dan 0 saat disembunyikan. */
    var onHeight: ((Int) -> Unit)? = null

    /** Dipanggil saat keyboard ditutup, mis. untuk menghapus blok teks. */
    var onHidden: (() -> Unit)? = null

    private fun dp(v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun show(t: OskTarget) {
        hideJob?.let { ui.removeCallbacks(it) }
        hideJob = null
        target = t
        if (attached) return
        symbols = false
        shift = false
        rebuild()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.START }
        try {
            wm.addView(view, lp)
            attached = true
            view?.post { onHeight?.invoke(view?.height ?: 0) }
        } catch (e: Exception) {
        }
    }

    /** Sembunyikan sedikit tertunda: ketukan di kolom teks yang sama sebelumnya memicu "di luar keyboard". */
    fun hideSoon() {
        if (!attached || hideJob != null) return
        val j = Runnable { hideJob = null; hide() }
        hideJob = j
        ui.postDelayed(j, 150)
    }

    fun hide() {
        hideJob?.let { ui.removeCallbacks(it) }
        hideJob = null
        val v = view
        if (attached && v != null) {
            try {
                wm.removeView(v)
            } catch (e: Exception) {
            }
        }
        val was = attached
        attached = false
        active = null
        dropPreview()
        target = null
        onHeight?.invoke(0)
        if (was) onHidden?.invoke()
    }

    val isShown get() = attached

    // ---------- isi ----------

    private val L1 = "qwertyuiop"
    private val L2 = "asdfghjkl"
    private val L3 = "zxcvbnm"
    private val S1 = "1234567890"
    private val S2 = listOf("@", "#", "$", "%", "&", "-", "+", "(", ")", "/")
    private val S3 = listOf("*", "\"", "'", ":", ";", "!", "?", "=", "^")

    private fun rebuild() {
        keyDefs.clear()
        active = null
        val scale = Prefs.get(c, Setting.OSK_H) / 100f
        val keyH = (dp(56) * scale).toInt() // lebih tinggi agar setara keyboard sistem (yang punya tombol navigasi di bawahnya)
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.clay(c, pal.surface, dp(18), 4f)
            setPadding(dp(6), dp(6), dp(6), dp(10))
            alpha = Prefs.get(c, Setting.OSK_ALPHA) / 100f
            setOnTouchListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_OUTSIDE) hideSoon()
                false
            }
        }
        // baris atas: salin, tempel, dan tombol turunkan keyboard
        val topH = keyH * 2 / 3
        root.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(key("Salin", 1.6f, topH, special = true) { target?.copy() })
            addView(key("Tempel", 1.6f, topH, special = true) { target?.paste() })
            addView(spacer(4f))
            addView(key("▼", 1.4f, topH, special = true) { hide() })
        })
        fun row(vararg keys: View) = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            keys.forEach { addView(it) }
        }
        if (!symbols) {
            // baris angka di atas huruf, seperti keyboard HP
            root.addView(row(*S1.map { ch -> plain(ch.toString(), keyH) }.toTypedArray()))
            root.addView(row(*L1.map { ch -> letter(ch, keyH) }.toTypedArray()))
            root.addView(row(*(listOf(spacer(0.5f)) + L2.map { ch -> letter(ch, keyH) } + listOf(spacer(0.5f))).toTypedArray()))
            root.addView(
                row(
                    *(listOf(key(if (shift) "⇧" else "⇧", 1.5f, keyH, special = true, on = shift) { shift = !shift; refresh() }) +
                        L3.map { ch -> letter(ch, keyH) } +
                        listOf(delKey(1.5f, keyH))).toTypedArray()
                )
            )
        } else {
            root.addView(row(*S1.map { ch -> plain(ch.toString(), keyH) }.toTypedArray()))
            root.addView(row(*S2.map { s -> plain(s, keyH) }.toTypedArray()))
            root.addView(
                row(
                    *(listOf(spacer(0.5f)) + S3.map { s -> plain(s, keyH) } + listOf(delKey(1.5f, keyH))).toTypedArray()
                )
            )
        }
        root.addView(
            row(
                key(if (symbols) "ABC" else "?123", 1.5f, keyH, special = true) { symbols = !symbols; refresh() },
                plain(",", keyH),
                key("spasi", 5f, keyH) { type(" ") },
                plain(".", keyH),
                key("⏎", 1.5f, keyH, special = true, primary = true) { enter() }
            )
        )
        view = root
    }

    private fun refresh() {
        val was = attached
        if (!was) return
        val old = view
        dropPreview()
        rebuild()
        try {
            wm.removeView(old)
            wm.addView(
                view,
                WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.BOTTOM or Gravity.START }
            )
        } catch (e: Exception) {
        }
    }

    private fun spacer(w: Float) = View(c).apply { layoutParams = LinearLayout.LayoutParams(0, 1, w) }

    private fun letter(ch: Char, h: Int): View {
        val s = if (shift) ch.uppercaseChar().toString() else ch.toString()
        return key(s, 1f, h, preview = true) {
            type(s)
            if (shift) {
                shift = false
                refresh()
            }
        }
    }

    private fun plain(s: String, h: Int) = key(s, 1f, h, preview = true) { type(s) }

    /** Tahan ⌫: hapus berulang, makin lama makin cepat (setelah sekitar 1 detik menghapus 2 sampai 3 huruf per langkah). */
    private fun delKey(w: Float, h: Int): View = key("⌫", w, h, special = true).also { k ->
        var n = 0
        val rep = object : Runnable {
            override fun run() {
                n++
                val times = if (n > 25) 3 else if (n > 12) 2 else 1
                repeat(times) { backspace() }
                ui.postDelayed(this, if (n > 12) 35L else 70L)
            }
        }
        k.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    pressFx(v, true)
                    n = 0
                    backspace()
                    ui.postDelayed(rep, 320)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    pressFx(v, false)
                    ui.removeCallbacks(rep)
                }
            }
            true
        }
    }

    /** Efek ketik seperti keyboard sistem: getar singkat dan tuts sedikit mengecil saat ditekan. */
    private fun pressFx(v: View, down: Boolean, scale: Boolean = true) {
        if (down) {
            if (scale) {
                v.animate().cancel()
                v.animate().scaleX(0.86f).scaleY(0.86f).alpha(0.7f).setDuration(40).start()
            }
            try {
                v.performHapticFeedback(
                    HapticFeedbackConstants.KEYBOARD_TAP,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                )
            } catch (e: Exception) {
            }
        } else if (scale) {
            v.animate().cancel()
            v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(90).start()
        }
    }

    // ---------- sentuhan tuts: pratinjau huruf seperti keyboard HP, geser jari pindah tuts, tanpa suara ----------

    private fun keyAt(rx: Float, ry: Float): KeyDef? {
        val loc = IntArray(2)
        for (d in keyDefs) {
            d.view.getLocationOnScreen(loc)
            if (rx >= loc[0] && rx < loc[0] + d.view.width && ry >= loc[1] && ry < loc[1] + d.view.height) return d
        }
        return null
    }

    private fun pressKey(d: KeyDef) {
        releaseKey()
        active = d
        d.view.isPressed = true
        pressFx(d.view, true, !d.preview)
        if (d.preview) showPreview(d) else hidePreview()
    }

    private fun releaseKey() {
        val a = active ?: return
        a.view.isPressed = false
        pressFx(a.view, false, !a.preview)
        hidePreview()
        active = null
    }

    private fun onKeyTouch(own: KeyDef, ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> pressKey(own)
            MotionEvent.ACTION_MOVE -> {
                val hit = keyAt(ev.rawX, ev.rawY)
                if (hit !== active) {
                    if (hit == null) releaseKey() else pressKey(hit)
                }
            }
            MotionEvent.ACTION_UP -> {
                val a = active
                releaseKey()
                a?.click?.invoke()
            }
            MotionEvent.ACTION_CANCEL -> releaseKey()
        }
        return true
    }

    /** Gelembung huruf di atas tuts yang ditekan (jendela overlay sendiri, tidak bisa disentuh dan tidak mengambil fokus). */
    private fun showPreview(d: KeyDef) {
        val root = view ?: return
        try {
            val v = pv ?: TextView(c).also { pv = it }
            val kw = d.view.width
            val kh = d.view.height
            val pw = (kw * 1.8f).toInt().coerceAtLeast(dp(60))
            val ph = (kh * 2.1f).toInt().coerceAtLeast(dp(84))
            v.text = d.label
            v.gravity = Gravity.CENTER
            v.textSize = 40f
            v.typeface = Typeface.DEFAULT_BOLD
            v.setTextColor(pal.on)
            v.isSoundEffectsEnabled = false
            v.background = Ui.round(if (pal.night) 0xFF4A4568.toInt() else 0xFFFFFFFF.toInt(), dp(18), Prefs.bubbleColor(c), dp(2))
            val kl = IntArray(2)
            d.view.getLocationOnScreen(kl)
            val rl = IntArray(2)
            root.getLocationOnScreen(rl)
            val sw = c.resources.displayMetrics.widthPixels
            val x = (kl[0] + kw / 2 - pw / 2).coerceIn(0, (sw - pw).coerceAtLeast(0))
            // dasar gelembung sedikit menutupi tepi atas tuts; jendela berjangkar di dasar layar seperti keyboard
            val y = rl[1] + root.height - kl[1] - (kh * 0.2f).toInt()
            val lp = WindowManager.LayoutParams(
                pw, ph, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                this.x = x
                this.y = y
            }
            if (pvAttached) wm.updateViewLayout(v, lp) else {
                wm.addView(v, lp)
                pvAttached = true
            }
            v.visibility = View.VISIBLE
        } catch (e: Exception) {
        }
    }

    private fun hidePreview() {
        pv?.visibility = View.INVISIBLE
    }

    private fun dropPreview() {
        val v = pv
        if (pvAttached && v != null) {
            try {
                wm.removeView(v)
            } catch (e: Exception) {
            }
        }
        pvAttached = false
    }

    private fun key(
        label: String, w: Float, h: Int,
        special: Boolean = false, on: Boolean = false, primary: Boolean = false,
        preview: Boolean = false, click: (() -> Unit)? = null
    ): TextView = TextView(c).apply {
        text = label
        isSoundEffectsEnabled = false
        gravity = Gravity.CENTER
        textSize = if (label.length > 1 && label != "▼") 14f else 21f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (primary || on) 0xFFFFFFFF.toInt() else pal.on)
        val col = when {
            primary || on -> Prefs.bubbleColor(c)
            special -> pal.field
            else -> pal.surface
        }
        background = Ui.clay(c, col, dp(10), 2f)
        layoutParams = LinearLayout.LayoutParams(if (h > 0 && w > 0f) 0 else LinearLayout.LayoutParams.WRAP_CONTENT, h, w).apply {
            setMargins(dp(2), dp(2), dp(2), dp(2))
        }
        if (click != null) {
            val def = KeyDef(this, label, preview, click)
            keyDefs.add(def)
            setOnTouchListener { _, ev -> onKeyTouch(def, ev) }
        }
    }

    // ---------- mengetik ke target ----------

    private fun type(s: String) {
        target?.type(s)
    }

    private fun backspace() {
        target?.backspace()
    }

    private fun enter() {
        target?.enter()
    }
}
