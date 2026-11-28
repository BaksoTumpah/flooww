package com.example.floatstudy

import android.graphics.Bitmap
import android.graphics.Rect
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

    /** OCR per paragraf beserta kotaknya (koordinat piksel bitmap), untuk Terjemah Langsung bila layar tidak punya teks yang bisa dibaca. */
    fun blocks(bmp: Bitmap, cb: (Result<List<Pair<Rect, String>>>) -> Unit) {
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { t ->
                cb(Result.success(t.textBlocks.mapNotNull { b ->
                    val r = b.boundingBox ?: return@mapNotNull null
                    r to b.text.replace('\n', ' ').trim()
                }))
            }
            .addOnFailureListener { cb(Result.failure(it)) }
    }

    /** OCR per baris beserta kotaknya (koordinat piksel bitmap), urut atas ke bawah. Dipakai fitur Answer untuk mencari pilihan A, B, C... di layar. */
    fun lines(bmp: Bitmap, cb: (Result<List<Pair<Rect, String>>>) -> Unit) {
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { t ->
                val out = ArrayList<Pair<Rect, String>>()
                for (b in t.textBlocks) for (l in b.lines) {
                    val r = l.boundingBox ?: continue
                    out.add(r to l.text.trim())
                }
                out.sortBy { it.first.top }
                cb(Result.success(out))
            }
            .addOnFailureListener { cb(Result.failure(it)) }
    }
}
