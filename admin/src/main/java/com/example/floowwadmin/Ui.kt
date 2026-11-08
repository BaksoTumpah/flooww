package com.example.floowwadmin

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.TextView

/** Palet flow admin: gaya clay yang sama dengan flooww, tapi bernuansa merah. */
class Pal(
    val bg: Int, val surface: Int, val on: Int, val muted: Int,
    val outline: Int, val field: Int, val night: Boolean
)

object Ui {
    const val ACCENT = 0xFFE5484D.toInt()

    fun pal(c: Context): Pal {
        val night = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return if (night) {
            Pal(
                0xFF2A1618.toInt(), 0xFF3B2124.toInt(), 0xFFFFEDEE.toInt(),
                0xFFC9A3A6.toInt(), 0xFF5A3236.toInt(), 0xFF241214.toInt(), true
            )
        } else {
            Pal(
                0xFFF5E4E5.toInt(), 0xFFFFF7F7.toInt(), 0xFF3A1C1F.toInt(),
                0xFF96707A.toInt(), 0xFFEBD0D2.toInt(), 0xFFEFD8DA.toInt(), false
            )
        }
    }

    fun dp(c: Context, v: Float): Int = (v * c.resources.displayMetrics.density).toInt()

    fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = ((((a shr s) and 255) * (1 - t)) + (((b shr s) and 255) * t)).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun clay(c: Context, color: Int, radiusPx: Int, depthDp: Float = 4f): Drawable {
        val depth = dp(c, depthDp)
        val slab = GradientDrawable().apply {
            setColor(mix(color, 0xFF000000.toInt(), 0.22f))
            cornerRadius = radiusPx.toFloat()
        }
        val face = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(mix(color, 0xFFFFFFFF.toInt(), 0.28f), color, mix(color, 0xFF000000.toInt(), 0.04f))
        ).apply {
            cornerRadius = radiusPx.toFloat()
            setStroke(dp(c, 1f).coerceAtLeast(1), 0x66FFFFFF)
        }
        return LayerDrawable(arrayOf(slab, face)).apply { setLayerInset(1, 0, 0, 0, depth) }
    }

    fun dent(c: Context, color: Int, radiusPx: Int): Drawable {
        val base = GradientDrawable().apply {
            setColor(0x88FFFFFF.toInt())
            cornerRadius = radiusPx.toFloat()
        }
        val well = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(mix(color, 0xFF000000.toInt(), 0.12f), color)
        ).apply { cornerRadius = radiusPx.toFloat() }
        return LayerDrawable(arrayOf(base, well)).apply { setLayerInset(1, 0, 0, 0, dp(c, 1.5f)) }
    }

    fun ripple(bg: Drawable, night: Boolean): Drawable =
        RippleDrawable(ColorStateList.valueOf(if (night) 0x33FFFFFF else 0x22000000), bg, null)

    fun button(c: Context, label: String, primary: Boolean, onClick: () -> Unit): TextView {
        val p = pal(c)
        return TextView(c).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (primary) 0xFFFFFFFF.toInt() else p.on)
            minHeight = dp(c, 46f)
            setPadding(dp(c, 16f), dp(c, 8f), dp(c, 16f), dp(c, 12f))
            background = ripple(clay(c, if (primary) ACCENT else p.surface, dp(c, 16f)), p.night)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    fun field(c: Context, hint: String, multi: Boolean = false, number: Boolean = false): EditText {
        val p = pal(c)
        return EditText(c).apply {
            this.hint = hint
            textSize = 14f
            setTextColor(p.on)
            setHintTextColor(p.muted)
            background = dent(c, p.field, dp(c, 14f))
            setPadding(dp(c, 14f), dp(c, 11f), dp(c, 14f), dp(c, 11f))
            if (multi) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 2
                maxLines = 5
                gravity = Gravity.TOP
            } else {
                setSingleLine()
                if (number) inputType = InputType.TYPE_CLASS_NUMBER
            }
        }
    }
}
