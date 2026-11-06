package com.example.floatstudy

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import org.json.JSONArray
import org.json.JSONObject

/** Hasil baca layar. Semua kotak dinormalisasi 0..1 terhadap seluruh layar. */
class AnswerNote(val text: String, val anchor: RectF)

class AnswerResult(val marks: List<RectF>, val notes: List<AnswerNote>) {
    val isEmpty: Boolean get() = marks.isEmpty() && notes.isEmpty()
}

object AutoAnswer {
    const val SYSTEM =
        "Kamu pengoreksi soal yang teliti. Kamu menerima tangkapan layar yang bisa berisi beberapa soal. " +
            "Temukan semua soal yang terlihat penuh. Untuk soal pilihan ganda biasa (satu jawaban benar) " +
            "dan pilihan ganda kompleks (lebih dari satu jawaban benar, biasanya kotak centang atau " +
            "tertulis pilih lebih dari satu), tentukan opsi yang benar. Untuk soal esai atau isian, " +
            "tulis jawaban singkat, maksimal 25 kata. Lewati soal yang terpotong atau tidak terbaca penuh. " +
            "Balas hanya JSON."

    val PROMPT = """
Baca seluruh layar lalu balas JSON dengan bentuk persis ini:
{"items":[{"type":"single","marks":[{"label":"B","box":[ymin,xmin,ymax,xmax]}],"answer":"","anchor":[ymin,xmin,ymax,xmax]}]}
Aturan:
- type adalah "single", "multiple", atau "essay".
- Untuk single dan multiple: isi marks hanya dengan opsi yang benar. box adalah kotak di sekeliling baris opsi itu (penanda pilihan dan teksnya).
- Untuk essay: marks kosong, answer berisi jawaban singkat, anchor adalah kotak soal atau kolom jawabannya.
- Semua koordinat bernilai 0 sampai 1000 relatif terhadap seluruh gambar.
- Bila tidak ada soal, balas {"items":[]}.
""".trimIndent()

    private fun box(a: JSONArray?): RectF? {
        if (a == null || a.length() < 4) return null
        val ymin = a.optDouble(0).toFloat() / 1000f
        val xmin = a.optDouble(1).toFloat() / 1000f
        val ymax = a.optDouble(2).toFloat() / 1000f
        val xmax = a.optDouble(3).toFloat() / 1000f
        if (xmax <= xmin || ymax <= ymin) return null
        return RectF(
            xmin.coerceIn(0f, 1f), ymin.coerceIn(0f, 1f),
            xmax.coerceIn(0f, 1f), ymax.coerceIn(0f, 1f)
        )
    }

    fun parse(raw: String): AnswerResult {
        val s = raw.indexOf('{')
        val e = raw.lastIndexOf('}')
        if (s < 0 || e <= s) return AnswerResult(emptyList(), emptyList())
        val items = JSONObject(raw.substring(s, e + 1)).optJSONArray("items")
            ?: return AnswerResult(emptyList(), emptyList())
        val marks = mutableListOf<RectF>()
        val notes = mutableListOf<AnswerNote>()
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            if (it.optString("type") == "essay") {
                val text = it.optString("answer").trim()
                val a = box(it.optJSONArray("anchor"))
                if (text.isNotEmpty() && a != null) notes.add(AnswerNote(text, a))
            } else {
                val ms = it.optJSONArray("marks") ?: continue
                for (k in 0 until ms.length()) {
                    box(ms.optJSONObject(k)?.optJSONArray("box"))?.let { r -> marks.add(r) }
                }
            }
        }
        return AnswerResult(marks, notes)
    }
}

/** Lapisan layar penuh yang tembus sentuhan: menggambar tanda jawaban dan catatan esai. */
class MarksView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private var result: AnswerResult? = null
    private var chip: String? = null

    private val green = 0xFF16A34A.toInt()
    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = green }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = (green and 0x00FFFFFF) or 0x26000000 }
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = green }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.2f * d; color = Color.WHITE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val noteBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xF2FFFFFF.toInt() }
    private val noteLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = green }
    private val noteText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF111827.toInt(); textSize = 13f * d }
    private val chipBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xE6111827.toInt() }
    private val chipText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 12f * d }

    fun setChip(t: String?) {
        chip = t
        invalidate()
    }

    fun showResult(r: AnswerResult?, message: String?) {
        result = r
        chip = message
        invalidate()
    }

    fun clear() {
        result = null
        chip = null
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        result?.let { r ->
            for (m in r.marks) {
                val rect = RectF(m.left * w, m.top * h, m.right * w, m.bottom * h)
                rect.inset(-4 * d, -3 * d)
                c.drawRoundRect(rect, 8 * d, 8 * d, fill)
                c.drawRoundRect(rect, 8 * d, 8 * d, box)
                val cx = rect.right - 12 * d
                val cy = rect.centerY()
                c.drawCircle(cx, cy, 10 * d, badge)
                c.drawLine(cx - 4.5f * d, cy, cx - 1.2f * d, cy + 3.5f * d, tick)
                c.drawLine(cx - 1.2f * d, cy + 3.5f * d, cx + 5 * d, cy - 3.5f * d, tick)
            }
            for (n in r.notes) {
                val maxW = (w * 0.86f).toInt()
                val layout = StaticLayout.Builder
                    .obtain(n.text, 0, n.text.length, noteText, maxW - (24 * d).toInt())
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .build()
                val boxW = layout.width + 24 * d
                val boxH = layout.height + 18 * d
                val left = ((w - boxW) / 2f).coerceAtLeast(8 * d)
                var top = n.anchor.bottom * h + 6 * d
                if (top + boxH > h - 8 * d) top = (n.anchor.top * h - boxH - 6 * d).coerceAtLeast(8 * d)
                val rr = RectF(left, top, left + boxW, top + boxH)
                c.drawRoundRect(rr, 12 * d, 12 * d, noteBg)
                c.drawRoundRect(rr, 12 * d, 12 * d, noteLine)
                c.save()
                c.translate(left + 12 * d, top + 9 * d)
                layout.draw(c)
                c.restore()
            }
        }
        chip?.let { t ->
            val tw = chipText.measureText(t)
            val rr = RectF((w - tw) / 2f - 14 * d, 52 * d, (w + tw) / 2f + 14 * d, 52 * d + 30 * d)
            c.drawRoundRect(rr, 15 * d, 15 * d, chipBg)
            c.drawText(t, rr.left + 14 * d, rr.centerY() + 4.5f * d, chipText)
        }
    }
}
