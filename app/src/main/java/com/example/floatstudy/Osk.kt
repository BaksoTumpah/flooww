package com.example.floatstudy

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Keyboard layar buatan flooww. Berupa jendela overlay sendiri di dasar layar yang tidak pernah menerima fokus,
 * jadi aplikasi di bawahnya tetap fokus. Mengetik langsung mengubah teks di EditText target.
 */
class Osk(private val c: Context, private val wm: WindowManager) {
    private val ui = Handler(Looper.getMainLooper())
    private val pal get() = Ui.pal(c)
    private var view: LinearLayout? = null
    private var target: EditText? = null
    private var shift = false
    private var symbols = false
    private var attached = false
    private var hideJob: Runnable? = null

    /** Dipanggil dengan tinggi keyboard (px) saat tampil, dan 0 saat disembunyikan. */
    var onHeight: ((Int) -> Unit)? = null

    private fun dp(v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun show(t: EditText) {
        hideJob?.let { ui.removeCallbacks(it) }
        hideJob = null
        target = t
        if (attached) return
        symbols = false
        shift = false
        rebuild()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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
        attached = false
        target = null
        onHeight?.invoke(0)
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
        val scale = Prefs.get(c, Setting.OSK_H) / 100f
        val keyH = (dp(46) * scale).toInt()
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.clay(c, pal.surface, dp(18), 4f)
            setPadding(dp(6), dp(4), dp(6), dp(6))
            alpha = Prefs.get(c, Setting.OSK_ALPHA) / 100f
            setOnTouchListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_OUTSIDE) hideSoon()
                false
            }
        }
        // baris atas: tombol tutup
        root.addView(LinearLayout(c).apply {
            gravity = Gravity.END
            addView(key("▼", 1.4f, keyH * 2 / 3, special = true) { hide() })
        })
        fun row(vararg keys: View) = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            keys.forEach { addView(it) }
        }
        if (!symbols) {
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
        rebuild()
        try {
            wm.removeView(old)
            wm.addView(
                view,
                WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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
        return key(s, 1f, h) {
            type(s)
            if (shift) {
                shift = false
                refresh()
            }
        }
    }

    private fun plain(s: String, h: Int) = key(s, 1f, h) { type(s) }

    private fun delKey(w: Float, h: Int): View = key("⌫", w, h, special = true).also { k ->
        val rep = object : Runnable {
            override fun run() {
                backspace()
                ui.postDelayed(this, 55)
            }
        }
        k.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    backspace()
                    ui.postDelayed(rep, 380)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    ui.removeCallbacks(rep)
                }
            }
            true
        }
    }

    private fun key(
        label: String, w: Float, h: Int,
        special: Boolean = false, on: Boolean = false, primary: Boolean = false,
        click: (() -> Unit)? = null
    ): TextView = TextView(c).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = if (label.length > 1 && label != "▼") 13f else 18f
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
            setOnTouchListener { v, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> v.isPressed = true
                    MotionEvent.ACTION_UP -> {
                        v.isPressed = false
                        click()
                    }
                    MotionEvent.ACTION_CANCEL -> v.isPressed = false
                }
                true
            }
        }
    }

    // ---------- mengetik ke target ----------

    private fun type(s: String) {
        val e = target ?: return
        val ed = e.text
        ed.append(s)
        e.setSelection(ed.length)
    }

    private fun backspace() {
        val e = target ?: return
        val ed = e.text
        if (ed.isNotEmpty()) ed.delete(ed.length - 1, ed.length)
        e.setSelection(ed.length)
    }

    private fun enter() {
        val e = target ?: return
        val multi = (e.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        if (multi) type("\n") else {
            e.onEditorAction(EditorInfo.IME_ACTION_GO)
            hide()
        }
    }
}
