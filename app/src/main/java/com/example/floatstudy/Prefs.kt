package com.example.floatstudy

import android.content.Context
import android.content.SharedPreferences

/** Pengaturan angka yang bisa diubah pengguna di layar pengaturan aplikasi. */
enum class Setting(val key: String, val def: Int, val min: Int, val max: Int) {
    BUBBLE_SIZE("bubbleSize", 48, 32, 96),     // dp
    BUBBLE_ALPHA("bubbleAlpha", 90, 20, 100),  // persen
    MENU_SIZE("menuSize", 100, 70, 140),       // persen
    MENU_ALPHA("menuAlpha", 100, 40, 100),     // persen
    WIN_W("winW", 300, 220, 480),              // dp, jendela alat
    WIN_H("winH", 360, 240, 640),              // dp
    WIN_ALPHA("winAlpha", 100, 40, 100),       // persen
    APP_W("appW", 320, 240, 700),              // dp, jendela aplikasi
    APP_H("appH", 480, 300, 900),              // dp
    LONG_MS("longMs", 700, 400, 3000),         // ms, lama tekan bubble
    WEB_ZOOM("webZoom", 80, 40, 150)           // %, skala halaman di Browser mengambang
}

object Prefs {
    const val DEFAULT_MODEL = "llama-3.3-70b-versatile"
    const val DEFAULT_COLOR = 0xFF7C6CF0.toInt()
    const val DEFAULT_PROMPT = "Jawab soal pada gambar ini. Jelaskan langkah singkat, lalu tulis jawaban akhir."

    val COLORS = intArrayOf(
        0xFF7C6CF0.toInt(), 0xFF2EC4B6.toInt(), 0xFFFF6B81.toInt(), 0xFFFFA94D.toInt(),
        0xFF4CC9F0.toInt(), 0xFF56C271.toInt(), 0xFFF368E0.toInt(), 0xFF2D2A4A.toInt()
    )
    /** Kode bahasa ML Kit untuk LANGS, urutannya sama. */
    val LANG_CODES = listOf("id", "en", "ar", "ja", "zh", "ko", "de", "fr", "es")
    val AI_NAMES = listOf("Gemini", "ChatGPT", "Claude")
    val LANGS = listOf(
        "Indonesia", "Inggris", "Arab", "Jepang", "Mandarin",
        "Korea", "Jerman", "Prancis", "Spanyol"
    )

    fun sp(c: Context): SharedPreferences = c.getSharedPreferences("floatstudy", Context.MODE_PRIVATE)

    // ---------- API key Groq (hanya untuk Chat dan Auto Jawab) ----------

    /** Key isian dari layar pengaturan. */
    fun key(c: Context): String = sp(c).getString("groqKey", "") ?: ""

    fun setKey(c: Context, key: String) {
        sp(c).edit().putString("groqKey", key.trim()).apply()
    }

    fun model(c: Context): String =
        (sp(c).getString("groqModel", DEFAULT_MODEL) ?: DEFAULT_MODEL).ifBlank { DEFAULT_MODEL }

    const val DEFAULT_VISION = "qwen/qwen3.8-27b"

    /** Model yang bisa membaca gambar (dipakai mode Auto Jawab "Gambar"). */
    fun visionModel(c: Context): String =
        (sp(c).getString("groqVision", DEFAULT_VISION) ?: DEFAULT_VISION).ifBlank { DEFAULT_VISION }

    fun setVisionModel(c: Context, m: String) {
        sp(c).edit().putString("groqVision", m.trim()).apply()
    }

    /** Cara Auto Jawab membaca layar: 0 teks (OCR di HP, cepat), 1 gambar (model gambar). */
    fun autoMode(c: Context): Int = sp(c).getInt("autoMode", 0).coerceIn(0, 1)

    fun setAutoMode(c: Context, v: Int) {
        sp(c).edit().putInt("autoMode", v).apply()
    }

    fun setModel(c: Context, model: String) {
        sp(c).edit().putString("groqModel", model.trim()).apply()
    }

    // ---------- AI tujuan untuk Tanya AI dan Terjemah ----------

    fun aiTarget(c: Context): Int = sp(c).getInt("aiTarget", 0).coerceIn(0, AI_NAMES.size - 1)

    fun setAiTarget(c: Context, i: Int) {
        sp(c).edit().putInt("aiTarget", i).apply()
    }

    /** Nama pengguna untuk sapaan. Nanti diisi dari data pembeli (aplikasi admin). */
    fun userName(c: Context): String = sp(c).getString("userName", "") ?: ""

    fun setUserName(c: Context, n: String) {
        sp(c).edit().putString("userName", n.trim()).apply()
    }

    fun lang(c: Context): Int = sp(c).getInt("lang", 1).coerceIn(0, LANGS.size - 1)

    fun setLang(c: Context, i: Int) {
        sp(c).edit().putInt("lang", i).apply()
    }

    fun prompt(c: Context): String =
        (sp(c).getString("prompt", DEFAULT_PROMPT) ?: DEFAULT_PROMPT).ifBlank { DEFAULT_PROMPT }

    fun setPrompt(c: Context, p: String) {
        sp(c).edit().putString("prompt", p.trim()).apply()
    }

    // ---------- tampilan ----------

    fun get(c: Context, s: Setting): Int = sp(c).getInt(s.key, s.def).coerceIn(s.min, s.max)

    fun set(c: Context, s: Setting, v: Int) {
        sp(c).edit().putInt(s.key, v.coerceIn(s.min, s.max)).apply()
    }

    fun bubbleColor(c: Context): Int =
        sp(c).getInt("bubbleColor", DEFAULT_COLOR).let { if (it == 0xFF2563EB.toInt()) DEFAULT_COLOR else it }

    fun setBubbleColor(c: Context, color: Int) {
        sp(c).edit().putInt("bubbleColor", color).apply()
    }

    /** Bentuk bubble: 0 bulat, 1 panah kecil di tepi, 2 garis tepi. */
    val SHAPE_NAMES = listOf("Bulat", "Panah", "Garis tepi")

    fun shape(c: Context): Int = sp(c).getInt("bubbleShape", 0).coerceIn(0, 2)

    fun setShape(c: Context, v: Int) {
        sp(c).edit().putInt("bubbleShape", v).apply()
    }

    fun resetLook(c: Context) {
        val e = sp(c).edit().remove("bubbleColor").remove("bubbleShape")
        Setting.values().forEach { e.remove(it.key) }
        e.apply()
    }

    // ---------- aplikasi yang dipasang di menu Apps ----------

    /** Aksi gestur bubble. Indeks 0 = tidak ada, 1 = buka menu, 2..11 = alat menu (id alat = indeks - 2). */
    val GESTURE_NAMES = listOf(
        "Tidak ada", "Buka menu", "Tanya AI", "Salin", "Terjemah", "Gambar",
        "Browser", "Chat", "Hitung", "Klik", "Apps", "Auto Jawab"
    )
    private val gestureKeys = listOf("gTap", "gDouble", "gLong", "gUp", "gDown", "gSide")
    private val gestureDefs = intArrayOf(1, 2, 11, 0, 0, 0)

    /** Pilihan untuk gestur g: 0 ketuk, 1 dua kali, 2 tekan lama, 3 sapu atas, 4 sapu bawah, 5 sapu ke arah tengah layar. */
    fun gesture(c: Context, g: Int): Int =
        sp(c).getInt(gestureKeys[g], gestureDefs[g]).coerceIn(0, GESTURE_NAMES.size - 1)

    fun setGesture(c: Context, g: Int, v: Int) {
        sp(c).edit().putInt(gestureKeys[g], v).apply()
    }

    /** Blokir keyboard virtual selama jendela mengambang tampil. Layar pengaturan aplikasi tidak terpengaruh. */
    fun kbBlock(c: Context): Boolean = sp(c).getBoolean("kbBlock", true)

    fun setKbBlock(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("kbBlock", v).apply()
    }

    fun pinned(c: Context): List<String> =
        (sp(c).getString("pinned", "") ?: "").split(',').filter { it.isNotBlank() }

    fun setPinned(c: Context, list: List<String>) {
        sp(c).edit().putString("pinned", list.joinToString(",")).apply()
    }
}
