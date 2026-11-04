package com.example.floatstudy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Tampilan "lingkari untuk mencari": menampilkan tangkapan layar yang diredupkan,
 * pengguna menggambar lingkaran bebas, lalu bagian di dalamnya dipotong.
 * Ketuk sekali (tanpa menggambar) untuk batal.
 */
class SelectView(
    ctx: Context,
    private val shot: Bitmap,
    private val onDone: (Bitmap?) -> Unit
) : View(ctx) {

    private val d = resources.displayMetrics.density
    private val path = Path()
    private val dst = Rect()
    private var touched = false
    private var finished = false

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4 * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        setShadowLayer(6 * d, 0f, 0f, Color.parseColor("#2563EB"))
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16 * d
        textAlign = Paint.Align.CENTER
        setShadowLayer(4 * d, 0f, 0f, Color.BLACK)
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(c: Canvas) {
        dst.set(0, 0, width, height)
        c.drawBitmap(shot, null, dst, bmpPaint)
        c.drawColor(0x66000000)
        if (!touched) {
            c.drawText("Lingkari soal atau bagian layar", width / 2f, height * 0.12f, txt)
            c.drawText("Ketuk sekali untuk batal", width / 2f, height * 0.12f + 24 * d, txt)
        }
        c.drawPath(path, line)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (finished) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                path.reset()
                path.moveTo(e.x, e.y)
                touched = true
            }
            MotionEvent.ACTION_MOVE -> path.lineTo(e.x, e.y)
            MotionEvent.ACTION_UP -> {
                path.lineTo(e.x, e.y)
                finish()
            }
            MotionEvent.ACTION_CANCEL -> {
                finished = true
                onDone(null)
            }
        }
        invalidate()
        return true
    }

    private fun finish() {
        finished = true
        val r = RectF()
        path.computeBounds(r, true)
        if (r.width() < 24 * d || r.height() < 24 * d || width == 0 || height == 0) {
            onDone(null)
            return
        }
        val sx = shot.width / width.toFloat()
        val sy = shot.height / height.toFloat()
        val pad = 8 * d
        val l = ((r.left - pad) * sx).toInt().coerceIn(0, shot.width - 1)
        val t = ((r.top - pad) * sy).toInt().coerceIn(0, shot.height - 1)
        val rt = ((r.right + pad) * sx).toInt().coerceIn(l + 1, shot.width)
        val b = ((r.bottom + pad) * sy).toInt().coerceIn(t + 1, shot.height)
        onDone(Bitmap.createBitmap(shot, l, t, rt - l, b - t))
    }
}
