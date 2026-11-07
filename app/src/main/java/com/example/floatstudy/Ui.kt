package com.example.floatstudy

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
        // palet clay: ungu pastel lembut, permukaan krem keunguan, teks tinta
        return if (night) {
            Pal(
                0xFF1B1830.toInt(), 0xFF2A2646.toInt(), 0xFFF0EDFF.toInt(),
                0xFFA9A3C6.toInt(), 0xFF3B3660.toInt(), 0xFF1A172D.toInt(), true
            )
        } else {
            Pal(
                0xFFE9E4F5.toInt(), 0xFFF8F6FF.toInt(), 0xFF2A2442.toInt(),
                0xFF7A7396.toInt(), 0xFFD8D1EC.toInt(), 0xFFDDD6EE.toInt(), false
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

    /** Campur dua warna: t = 0 hasil a, t = 1 hasil b. */
    fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = ((((a shr s) and 255) * (1 - t)) + (((b shr s) and 255) * t)).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Permukaan clay timbul: alas tebal lebih gelap di bawah, wajah berpendar dari atas, tepi atas berkilau. */
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
        return LayerDrawable(arrayOf(slab, face)).apply {
            setLayerInset(1, 0, 0, 0, depth)
        }
    }

    /** Cekungan clay (kolom isian, jalur): seperti ditekan ke dalam permukaan. */
    fun dent(c: Context, color: Int, radiusPx: Int): Drawable {
        val base = GradientDrawable().apply {
            setColor(0x88FFFFFF.toInt())
            cornerRadius = radiusPx.toFloat()
        }
        val well = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(mix(color, 0xFF000000.toInt(), 0.12f), color)
        ).apply { cornerRadius = radiusPx.toFloat() }
        return LayerDrawable(arrayOf(base, well)).apply {
            setLayerInset(1, 0, 0, 0, dp(c, 1.5f))
        }
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
            minHeight = dp(c, 46f)
            setPadding(dp(c, 16f), dp(c, 8f), dp(c, 16f), dp(c, 12f))
            background = ripple(clay(c, if (primary) accent else p.surface, dp(c, 16f)), p.night)
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
            background = dent(c, p.field, dp(c, 14f))
            setPadding(dp(c, 14f), dp(c, 11f), dp(c, 14f), dp(c, 11f))
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
