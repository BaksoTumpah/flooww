package com.example.floatstudy

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView

/** Palet warna yang mengikuti mode gelap sistem. */
class Pal(
    val bg: Int,
    val surface: Int,
    val on: Int,
    val muted: Int,
    val outline: Int,
    val field: Int,
    val night: Boolean
)

object Ui {
    fun pal(c: Context): Pal {
        val night = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return if (night) {
            Pal(
                0xFF12161F.toInt(), 0xFF1E2330.toInt(), 0xFFE8ECF4.toInt(),
                0xFF9AA3B2.toInt(), 0xFF323A4A.toInt(), 0xFF2A3140.toInt(), true
            )
        } else {
            Pal(
                0xFFF5F6F8.toInt(), 0xFFFFFFFF.toInt(), 0xFF111827.toInt(),
                0xFF6B7280.toInt(), 0xFFE5E7EB.toInt(), 0xFFF3F4F6.toInt(), false
            )
        }
    }

    fun dp(c: Context, v: Float): Int = (v * c.resources.displayMetrics.density).toInt()

    fun withAlpha(color: Int, a: Float): Int =
        (color and 0x00FFFFFF) or ((a * 255).toInt() shl 24)

    fun round(color: Int, radiusPx: Int, strokeColor: Int = 0, strokePx: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx.toFloat()
            if (strokePx > 0) setStroke(strokePx, strokeColor)
        }

    fun ripple(bg: Drawable, night: Boolean): Drawable =
        RippleDrawable(ColorStateList.valueOf(if (night) 0x33FFFFFF else 0x22000000), bg, null)

    /** Tombol bulat sudut: utama (warna aksen) atau tonal (abu lembut). */
    fun button(c: Context, label: String, primary: Boolean, onClick: () -> Unit): TextView {
        val p = pal(c)
        val accent = Prefs.bubbleColor(c)
        return TextView(c).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (primary) 0xFFFFFFFF.toInt() else p.on)
            minHeight = dp(c, 40f)
            setPadding(dp(c, 14f), dp(c, 8f), dp(c, 14f), dp(c, 8f))
            background = ripple(round(if (primary) accent else p.field, dp(c, 12f)), p.night)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    /** Kolom isian tanpa garis bawah, latar lembut bersudut bulat. */
    fun field(c: Context, hint: String, multi: Boolean = false): EditText {
        val p = pal(c)
        return EditText(c).apply {
            this.hint = hint
            textSize = 14f
            setTextColor(p.on)
            setHintTextColor(p.muted)
            background = round(p.field, dp(c, 12f))
            setPadding(dp(c, 12f), dp(c, 10f), dp(c, 12f), dp(c, 10f))
            if (multi) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 2
                maxLines = 4
                gravity = Gravity.TOP
            } else {
                setSingleLine()
            }
        }
    }

    fun icon(c: Context, res: Int, tint: Int, sizeDp: Float): ImageView =
        ImageView(c).apply {
            setImageResource(res)
            setColorFilter(tint)
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp))
        }
}
