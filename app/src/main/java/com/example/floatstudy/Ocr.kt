package com.example.floatstudy

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

object Ocr {
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    // OCR di perangkat (huruf Latin). Untuk aksara lain pakai fitur AI vision.
    fun read(bmp: Bitmap, cb: (Result<String>) -> Unit) {
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { cb(Result.success(it.text.trim())) }
            .addOnFailureListener { cb(Result.failure(it)) }
    }
}
