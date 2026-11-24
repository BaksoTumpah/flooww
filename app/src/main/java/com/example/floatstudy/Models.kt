package com.example.floatstudy

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel

/**
 * Model bahasa terjemah. Hanya Indonesia dan Inggris yang diunduh otomatis (bawaan). Bahasa lain harus diunduh
 * sendiri di Pengaturan > Model bahasa terjemah, supaya data internet dan ruang penyimpanan tidak terpakai tanpa disadari.
 */
object Models {
    val BASE = setOf("id", "en")

    fun name(code: String): String {
        val i = Prefs.LANG_CODES.indexOf(code)
        return if (i >= 0) Prefs.LANGS[i] else code
    }

    /** Unduh model bawaan (Indonesia, Inggris) bila belum ada. Aman dipanggil berulang. */
    fun ensureBase(c: Context) {
        try {
            val mgr = RemoteModelManager.getInstance()
            mgr.getDownloadedModels(TranslateRemoteModel::class.java).addOnSuccessListener { set ->
                val have = set.map { it.language }.toSet()
                for (code in BASE.filter { it !in have }) {
                    mgr.download(TranslateRemoteModel.Builder(code).build(), DownloadConditions.Builder().build())
                }
            }
        } catch (e: Exception) {
        }
    }

    /** Kode bahasa non-bawaan di [codes] yang modelnya belum diunduh. Kosong bila semua siap (atau daftar tidak terbaca). */
    fun missing(codes: List<String>, cb: (List<String>) -> Unit) {
        val need = codes.filter { it !in BASE }.distinct()
        if (need.isEmpty()) {
            cb(emptyList())
            return
        }
        try {
            RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java)
                .addOnSuccessListener { set ->
                    val have = set.map { it.language }.toSet()
                    cb(need.filter { it !in have })
                }
                .addOnFailureListener { cb(emptyList()) }
        } catch (e: Exception) {
            cb(emptyList())
        }
    }

    fun hint(miss: List<String>) =
        "Model bahasa ${miss.joinToString { name(it) }} belum diunduh. Unduh dulu di Pengaturan > Model bahasa terjemah."
}
