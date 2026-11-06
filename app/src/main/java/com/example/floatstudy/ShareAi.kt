package com.example.floatstudy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.net.URLEncoder

/**
 * Mengirim gambar atau teks ke aplikasi/website AI pilihan (Gemini, ChatGPT, Claude).
 * Tidak memakai API key. Bila aplikasinya terpasang, dibuka lewat fitur Bagikan.
 * Bila tidak, website dibuka di browser dan gambar disalin ke clipboard untuk ditempel.
 */
object ShareAi {
    private val PKG = listOf("com.google.android.apps.bard", "com.openai.chatgpt", "com.anthropic.claude")
    private val WEB = listOf("https://gemini.google.com/app", "https://chatgpt.com/", "https://claude.ai/new")
    private val WEB_Q = listOf(null, "https://chatgpt.com/?q=", "https://claude.ai/new?q=")

    private fun clip(c: Context): ClipboardManager = c.getSystemService(ClipboardManager::class.java)

    private fun installed(c: Context, pkg: String) = c.packageManager.getLaunchIntentForPackage(pkg) != null

    private fun openWeb(c: Context, target: Int, prompt: String) {
        val base = WEB_Q[target]
        val url = if (base != null) base + URLEncoder.encode(prompt.take(1500), "UTF-8") else WEB[target]
        c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Mengembalikan pesan singkat untuk ditampilkan ke pengguna. */
    fun sendImage(c: Context, bmp: Bitmap, prompt: String): String {
        val target = Prefs.aiTarget(c)
        val name = Prefs.AI_NAMES[target]
        return try {
            val dir = File(c.cacheDir, "shared").apply { mkdirs() }
            val f = File(dir, "ask.jpg")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            val uri = FileProvider.getUriForFile(c, "${c.packageName}.files", f)

            if (installed(c, PKG[target])) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, prompt)
                    clipData = ClipData.newRawUri("", uri)
                    setPackage(PKG[target])
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    c.startActivity(send)
                    // sebagian aplikasi mengabaikan teks yang menyertai gambar, jadi simpan juga di clipboard
                    clip(c).setPrimaryClip(ClipData.newPlainText("Prompt", prompt))
                    return "Dikirim ke $name. Bila pertanyaan kosong, tempel dari clipboard."
                } catch (e: Exception) {
                    // lanjut ke website
                }
            }
            clip(c).setPrimaryClip(ClipData.newUri(c.contentResolver, "Gambar soal", uri))
            openWeb(c, target, prompt)
            "Gambar disalin. Tempel di kolom chat $name."
        } catch (e: Exception) {
            "Gagal mengirim: ${e.message}"
        }
    }

    fun sendText(c: Context, text: String, prompt: String): String {
        val target = Prefs.aiTarget(c)
        val name = Prefs.AI_NAMES[target]
        val full = prompt + "\n\n" + text
        return try {
            if (installed(c, PKG[target])) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, full)
                    setPackage(PKG[target])
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    c.startActivity(send)
                    return "Dikirim ke $name"
                } catch (e: Exception) {
                    // lanjut ke website
                }
            }
            clip(c).setPrimaryClip(ClipData.newPlainText("Pertanyaan", full))
            openWeb(c, target, full)
            "Teks disalin. Tempel di kolom chat $name bila belum terisi."
        } catch (e: Exception) {
            "Gagal mengirim: ${e.message}"
        }
    }
}
