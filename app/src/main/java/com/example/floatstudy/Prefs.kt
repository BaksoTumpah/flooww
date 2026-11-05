package com.example.floatstudy

import android.content.Context

/** Pengaturan angka yang bisa diubah pengguna lewat menu Atur. */
enum class Setting(val key: String, val def: Int, val min: Int, val max: Int) {
    BUBBLE_SIZE("bubbleSize", 48, 32, 96),     // dp
    BUBBLE_ALPHA("bubbleAlpha", 90, 20, 100),  // persen
    MENU_SIZE("menuSize", 100, 60, 150),       // persen
    MENU_ALPHA("menuAlpha", 95, 30, 100),      // persen
    WIN_W("winW", 300, 200, 480),              // dp, jendela alat
    WIN_H("winH", 360, 220, 640),              // dp
    WIN_ALPHA("winAlpha", 100, 30, 100),       // persen
    APP_W("appW", 320, 240, 700),              // dp, jendela aplikasi
    APP_H("appH", 480, 300, 900)               // dp
}

object Prefs {
    const val DEFAULT_MODEL = "claude-sonnet-5-5"
    const val DEFAULT_COLOR = 0xFF2563EB.toInt()

    val COLORS = intArrayOf(
        0xFF2563EB.toInt(), 0xFF16A34A.toInt(), 0xFFDC2626.toInt(), 0xFF9333EA.toInt(),
        0xFFEA580C.toInt(), 0xFF0891B2.toInt(), 0xFFDB2777.toInt(), 0xFF111827.toInt()
    )

    private fun sp(c: Context) = c.getSharedPreferences("floatstudy", Context.MODE_PRIVATE)

    // ---------- API key ----------

    /** Key yang ditanam saat build (secret ANTHROPIC_API_KEY). Kosong bila tidak ada. */
    fun builtInKey(): String = BuildConfig.API_KEY

    fun hasBuiltInKey(): Boolean = BuildConfig.API_KEY.isNotBlank()

    /** Key isian manual (cadangan). */
    fun manualKey(c: Context): String = sp(c).getString("key", "") ?: ""

    /** Key yang dipakai: isian manual bila ada, kalau tidak key tertanam. */
    fun key(c: Context): String = manualKey(c).ifBlank { BuildConfig.API_KEY }

    fun model(c: Context): String =
        (sp(c).getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL).ifBlank { DEFAULT_MODEL }

    /** Key kosong = jangan ubah key manual yang sudah tersimpan. */
    fun save(c: Context, key: String?, model: String) {
        val e = sp(c).edit().putString("model", model.trim())
        if (key != null) e.putString("key", key.trim())
        e.apply()
    }

    // ---------- tampilan ----------

    fun get(c: Context, s: Setting): Int = sp(c).getInt(s.key, s.def).coerceIn(s.min, s.max)

    fun set(c: Context, s: Setting, v: Int) {
        sp(c).edit().putInt(s.key, v.coerceIn(s.min, s.max)).apply()
    }

    fun bubbleColor(c: Context): Int = sp(c).getInt("bubbleColor", DEFAULT_COLOR)

    fun setBubbleColor(c: Context, color: Int) {
        sp(c).edit().putInt("bubbleColor", color).apply()
    }

    fun resetLook(c: Context) {
        val e = sp(c).edit().remove("bubbleColor")
        Setting.values().forEach { e.remove(it.key) }
        e.apply()
    }

    // ---------- aplikasi yang dipasang di menu Apps ----------

    fun pinned(c: Context): List<String> =
        (sp(c).getString("pinned", "") ?: "").split(',').filter { it.isNotBlank() }

    fun setPinned(c: Context, list: List<String>) {
        sp(c).edit().putString("pinned", list.joinToString(",")).apply()
    }
}
