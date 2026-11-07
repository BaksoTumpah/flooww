package com.example.floatstudy

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

object Ocr {
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    // OCR di perangkat (huruf Latin). Untuk aksara lain pakai fitur AI vision.
    private val recognizerJa by lazy {
        TextRecognition.getClient(com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions.Builder().build())
    }

    fun read(bmp: Bitmap, japanese: Boolean = false, cb: (Result<String>) -> Unit) {
        (if (japanese) recognizerJa else recognizer).process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { cb(Result.success(it.text.trim())) }
            .addOnFailureListener { cb(Result.failure(it)) }
    }

    /** Satu baris teks di layar beserta kotaknya (piksel). */
    class Line(val text: String, val box: RectF)

    /** Baca semua baris teks beserta posisinya, urut dari atas ke bawah. */
    fun lines(bmp: Bitmap, cb: (Result<List<Line>>) -> Unit) {
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { t ->
                val l = mutableListOf<Line>()
                for (b in t.textBlocks) for (ln in b.lines) {
                    val r = ln.boundingBox ?: continue
                    val s = ln.text.trim()
                    if (s.isNotEmpty()) l.add(Line(s, RectF(r)))
                }
                l.sortWith(compareBy({ (it.box.top / 12f).toInt() }, { it.box.left }))
                cb(Result.success(l))
            }
            .addOnFailureListener { cb(Result.failure(it)) }
    }
}
