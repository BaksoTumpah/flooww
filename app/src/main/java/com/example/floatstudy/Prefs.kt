package com.example.floatstudy

import android.content.Context
import android.content.SharedPreferences

/** Pengaturan angka yang bisa diubah pengguna di layar pengaturan aplikasi. */
enum class Setting(val key: String, val def: Int, val min: Int, val max: Int) {
    BUBBLE_SIZE("bubbleSize", 55, 32, 96),     // dp
    BUBBLE_ALPHA("bubbleAlpha", 29, 20, 100),  // persen
    MENU_SIZE("menuSize", 60, 40, 140),       // persen
    MENU_ALPHA("menuAlpha", 80, 40, 100),     // persen
    WIN_W("winW", 300, 220, 480),              // dp, jendela alat
    WIN_H("winH", 360, 240, 640),              // dp
    WIN_ALPHA("winAlpha", 100, 40, 100),       // persen
    APP_W("appW", 320, 240, 700),              // dp, jendela aplikasi
    APP_H("appH", 480, 300, 900),              // dp
    LONG_MS("longMs", 700, 400, 3000),         // ms, lama tekan bubble
    WEB_ZOOM("webZoom", 150, 40, 150),          // %, skala halaman di Browser mengambang
    OSK_H("oskH", 100, 70, 170),               // %, tinggi tombol keyboard flooww
    OSK_ALPHA("oskAlpha", 100, 50, 100)        // %, opasitas keyboard flooww
}

object Prefs {
    const val DEFAULT_COLOR = 0xFF7C6CF0.toInt()
    /** Warna bawaan bubble baru: abu-abu seperti sidebar bawaan HP. */
    const val GRAY_COLOR = 0xFFB8BCC4.toInt()
    const val DEFAULT_PROMPT = "Jawab soal pada gambar ini. Langsung tulis jawaban akhirnya saja, tanpa penjelasan."
    /** Instruksi bawaan lama: bila masih tersimpan persis begitu, otomatis diganti ke yang baru. */
    private const val OLD_DEFAULT_PROMPT = "Jawab soal pada gambar ini. Jelaskan langkah singkat, lalu tulis jawaban akhir."

    val COLORS = intArrayOf(
        0xFF7C6CF0.toInt(), 0xFF2EC4B6.toInt(), 0xFFFF6B81.toInt(), 0xFFFFA94D.toInt(),
        0xFF4CC9F0.toInt(), 0xFF56C271.toInt(), 0xFFF368E0.toInt(), 0xFF2D2A4A.toInt(),
        // putih dan abu-abu, mirip bola asisten / sidebar bawaan HP
        0xFFFFFFFF.toInt(), 0xFFB8BCC4.toInt()
    )
    /** Kode bahasa ML Kit untuk LANGS, urutannya sama. */
    val LANG_CODES = listOf("id", "en", "ar", "ja", "zh", "ko", "de", "fr", "es")
    val AI_NAMES = listOf("Duck.ai", "Google AI Mode")
    val LANGS = listOf(
        "Indonesia", "Inggris", "Arab", "Jepang", "Mandarin",
        "Korea", "Jerman", "Prancis", "Spanyol"
    )

    fun sp(c: Context): SharedPreferences = c.getSharedPreferences("floatstudy", Context.MODE_PRIVATE)

    // ---------- catatan cepat ----------

    /** Daftar catatan (waktu, teks), terbaru di atas. */
    fun notes(c: Context): List<Pair<Long, String>> = try {
        val a = org.json.JSONArray(sp(c).getString("notes", "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it); o.getLong("t") to o.getString("x") }
    } catch (e: Exception) {
        emptyList()
    }

    fun setNotes(c: Context, l: List<Pair<Long, String>>) {
        val a = org.json.JSONArray()
        l.forEach { a.put(org.json.JSONObject().put("t", it.first).put("x", it.second)) }
        sp(c).edit().putString("notes", a.toString()).apply()
    }

    // ---------- AI tujuan untuk Tanya AI dan Terjemah ----------

    /** Claude diganti Google AI Mode: pilihan lama 1 (Claude) jadi ChatGPT agar tidak berubah diam-diam. */
    private fun migrateAi(c: Context) {
        val sp = sp(c)
        if (sp.getBoolean("aimig2", false)) return
        val e = sp.edit()
        if (sp.getBoolean("aimig1", false) && sp.getInt("aiTarget", 0) == 1) e.putInt("aiTarget", 0)
        else if (!sp.getBoolean("aimig1", false) && sp.contains("aiTarget")) e.putInt("aiTarget", 0)
        e.putBoolean("aimig1", true).putBoolean("aimig2", true).apply()
    }

    fun aiTarget(c: Context): Int {
        migrateAi(c)
        // bawaan: Google AI Mode (tidak mewajibkan login); pilihan yang sudah disimpan pengguna tidak diubah
        return sp(c).getInt("aiTarget", 1).coerceIn(0, AI_NAMES.size - 1)
    }

    /** Memilih AI di pengaturan juga membersihkan tanda "kena batas", supaya pilihan pengguna langsung dipakai. */
    fun setAiTarget(c: Context, i: Int) {
        sp(c).edit().putInt("aiTarget", i).remove("aiBlock0").remove("aiBlock1").apply()
    }

    /** Nama pengguna untuk sapaan. Nanti diisi dari data pembeli (aplikasi admin). */
    fun userName(c: Context): String = sp(c).getString("userName", "") ?: ""

    fun setUserName(c: Context, n: String) {
        sp(c).edit().putString("userName", n.trim()).apply()
    }

    /** Bahasa sumber Live Translate (indeks LANGS). */
    fun liveSrc(c: Context): Int = sp(c).getInt("liveSrc", 1).coerceIn(0, LANGS.size - 1)

    /** Bahasa tujuan Live Translate (indeks LANGS), bawaan Indonesia. Selalu berbeda dari bahasa sumber. */
    fun liveDst(c: Context): Int {
        val d = sp(c).getInt("liveDst", 0).coerceIn(0, LANGS.size - 1)
        return if (d == liveSrc(c)) (if (d == 0) 1 else 0) else d
    }

    fun setLiveDst(c: Context, i: Int) {
        sp(c).edit().putInt("liveDst", i).apply()
    }

    fun setLiveSrc(c: Context, i: Int) {
        sp(c).edit().putInt("liveSrc", i).apply()
    }

    /** Sesi AI yang kena batas (Duck.ai: batas harian) dilewati sampai waktu ini (milidetik). */
    fun aiBlocked(c: Context, t: Int): Boolean = System.currentTimeMillis() < sp(c).getLong("aiBlock$t", 0L)

    fun setAiBlocked(c: Context, t: Int, until: Long) {
        sp(c).edit().putLong("aiBlock$t", until).apply()
    }

    /** Tengah malam UTC berikutnya: saat batas harian Duck.ai direset. */
    fun untilUtcMidnight(): Long {
        val day = 86_400_000L
        return (System.currentTimeMillis() / day + 1) * day
    }

    fun lang(c: Context): Int = sp(c).getInt("lang", 1).coerceIn(0, LANGS.size - 1)

    fun setLang(c: Context, i: Int) {
        sp(c).edit().putInt("lang", i).apply()
    }

    fun prompt(c: Context): String =
        (sp(c).getString("prompt", DEFAULT_PROMPT) ?: DEFAULT_PROMPT).ifBlank { DEFAULT_PROMPT }
            .let { if (it == OLD_DEFAULT_PROMPT) DEFAULT_PROMPT else it }

    fun setPrompt(c: Context, p: String) {
        sp(c).edit().putString("prompt", p.trim()).apply()
    }

    // ---------- tampilan ----------

    /** Ukuran bubble disimpan per bentuk (bulat, panah, garis tepi); pengaturan lain memakai satu kunci. */
    private fun keyOf(c: Context, s: Setting) = if (s == Setting.BUBBLE_SIZE) "bubbleSize${shape(c)}" else s.key

    /** Bawaan ukuran bubble: bulat dan panah paling kecil, garis tepi 40. */
    private fun defOf(c: Context, s: Setting): Int =
        if (s == Setting.BUBBLE_SIZE) (if (shape(c) == 2) 40 else s.min) else s.def

    fun get(c: Context, s: Setting): Int {
        val k = keyOf(c, s)
        val p = sp(c)
        // pengguna lama yang pernah mengatur ukuran bubble (satu kunci untuk semua bentuk) tetap memakai nilainya
        val v = if (s == Setting.BUBBLE_SIZE && !p.contains(k) && p.contains(s.key)) p.getInt(s.key, defOf(c, s))
        else p.getInt(k, defOf(c, s))
        return v.coerceIn(s.min, s.max)
    }

    fun set(c: Context, s: Setting, v: Int) {
        sp(c).edit().putInt(keyOf(c, s), v.coerceIn(s.min, s.max)).apply()
    }

    fun bubbleColor(c: Context): Int =
        sp(c).getInt("bubbleColor", GRAY_COLOR).let { if (it == 0xFF2563EB.toInt()) DEFAULT_COLOR else it }

    fun setBubbleColor(c: Context, color: Int) {
        sp(c).edit().putInt("bubbleColor", color).apply()
    }

    /** Bentuk bubble: 0 bulat, 1 panah kecil di tepi, 2 garis tepi. */
    val SHAPE_NAMES = listOf("Bulat", "Panah", "Garis tepi")

    fun shape(c: Context): Int = sp(c).getInt("bubbleShape", 2).coerceIn(0, 2)

    fun setShape(c: Context, v: Int) {
        sp(c).edit().putInt("bubbleShape", v).apply()
    }

    fun resetLook(c: Context) {
        val e = sp(c).edit().remove("bubbleColor").remove("bubbleShape")
        Setting.values().forEach { e.remove(it.key) }
        for (i in 0..2) e.remove("bubbleSize$i")
        e.apply()
    }

    // ---------- aplikasi yang dipasang di menu Apps ----------

    /** Aksi gestur bubble. Indeks 0 = tidak ada, 1 = buka menu, 2..11 = alat menu (id alat = indeks - 2). */
    val GESTURE_NAMES = listOf(
        "Tidak ada", "Buka menu", "Tanya AI", "Salin", "Terjemah", "Screenshot",
        "Browser", "Kalkulator", "Klik", "Apps", "Catatan", "Galeri", "Matikan bubble"
    )
    private val gestureKeys = listOf("gTap", "gDouble", "gLong", "gUp", "gDown", "gSide")
    private val gestureDefs = intArrayOf(1, 2, 0, 0, 0, 0)

    /** Pilihan untuk gestur g: 0 ketuk, 1 dua kali, 2 tekan lama, 3 sapu atas, 4 sapu bawah, 5 sapu ke arah tengah layar. */
    fun gesture(c: Context, g: Int): Int {
        migrateGestures(c)
        return sp(c).getInt(gestureKeys[g], gestureDefs[g]).coerceIn(0, GESTURE_NAMES.size - 1)
    }

    /** Chat dan Auto Jawab dihapus: pilihan gestur lama digeser ke urutan baru (yang menunjuk fitur terhapus jadi "Tidak ada"). */
    private fun migrateGestures(c: Context) {
        val sp = sp(c)
        if (sp.getBoolean("gmig3", false)) return
        val e = sp.edit()
        val v2 = !sp.getBoolean("gmig2", false)
        for (k in gestureKeys) {
            if (!sp.contains(k)) continue
            var v = sp.getInt(k, 0)
            if (v2) v = when {
                v <= 6 -> v
                v == 7 || v == 11 -> 0
                v in 8..10 -> v - 1
                v == 12 -> 10
                else -> 0
            }
            // Catatan dan Galeri disisipkan sebelum "Matikan bubble"
            if (v == 10) v = 12
            e.putInt(k, v)
        }
        e.putBoolean("gmig2", true).putBoolean("gmig3", true).apply()
    }

    fun setGesture(c: Context, g: Int, v: Int) {
        sp(c).edit().putInt(gestureKeys[g], v).apply()
    }

    /**
     * Keyboard bawaan HP selalu diblokir selama jendela mengambang tampil; hanya keyboard flooww yang dipakai
     * (tidak mengambil fokus). Layar pengaturan aplikasi tidak terpengaruh. Tidak ada lagi pilihan untuk mematikannya.
     */
    @Suppress("UNUSED_PARAMETER")
    fun kbBlock(c: Context): Boolean = true

    /** Nama semua tile menu bubble, urutannya sama dengan indeks di FloatService. */
    val MENU_NAMES = listOf(
        "Tanya AI", "Salin", "Terjemah", "Screenshot", "Browser",
        "Kalkulator", "Klik", "Apps", "Catatan", "Galeri", "Live", "OCR", "Matikan", "Kamera", "Answer"
    )

    /** Id tile Matikan (selalu tampil). Tile baru ditambah di belakang dan disisipkan sebelum Matikan di urutan. */
    const val OFF_ID = 12

    /** Id semua tile yang bisa disembunyikan (semua kecuali Matikan), sesuai urutan MENU_NAMES. */
    val TILE_IDS: List<Int> get() = MENU_NAMES.indices.filter { it != OFF_ID }

    /** Urutan bawaan tile di menu bubble (id tile = indeks MENU_NAMES), dari kiri atas: Tanya AI, OCR, Live, Terjemah, Salin, Browser, Kalkulator, Catatan, Galeri, Screenshot, Apps, Klik, Matikan. */
    val MENU_DEF_ORDER = listOf(0, 11, 10, 2, 1, 4, 5, 8, 9, 3, 7, 6, 13, 14, 12)

    /** Urutan tile yang dipilih pengguna. Id tidak dikenal dibuang, tile yang belum tercatat ditambahkan di akhir sesuai urutan bawaan. */
    fun menuOrder(c: Context): List<Int> {
        val saved = (sp(c).getString("menuOrder", "") ?: "").split(',').mapNotNull { it.toIntOrNull() }
            .filter { it in MENU_NAMES.indices }.distinct()
        val missing = MENU_DEF_ORDER.filter { it !in saved }
        // tile baru (mis. Kamera) disisipkan sebelum Matikan agar Matikan tetap di ujung
        val off = saved.indexOf(OFF_ID)
        return if (off >= 0 && missing.isNotEmpty()) saved.take(off) + missing.filter { it != OFF_ID } + saved.drop(off)
        else saved + missing
    }

    /** Posisi dan ukuran terakhir kotak OCR: x, y, lebar, tinggi dalam piksel. Null bila belum pernah diatur. */
    fun readBoxRect(c: Context): IntArray? {
        val p = (sp(c).getString("readBox", "") ?: "").split(',').mapNotNull { it.toIntOrNull() }
        return if (p.size == 4 && p[2] > 0 && p[3] > 0) p.toIntArray() else null
    }

    fun setReadBoxRect(c: Context, x: Int, y: Int, w: Int, h: Int) {
        sp(c).edit().putString("readBox", "$x,$y,$w,$h").apply()
    }

    fun setMenuOrder(c: Context, l: List<Int>) {
        sp(c).edit().putString("menuOrder", l.joinToString(",")).apply()
    }

    /** Tile yang disembunyikan pengguna dari menu bubble. Tile Matikan (terakhir) selalu tampil. */
    fun menuHidden(c: Context): Set<Int> =
        (sp(c).getString("menuHidden", "") ?: "").split(',').mapNotNull { it.toIntOrNull() }
            .filter { it in MENU_NAMES.indices && it != OFF_ID }.toSet()

    fun setMenuHidden(c: Context, s: Set<Int>) {
        sp(c).edit().putString("menuHidden", s.sorted().joinToString(",")).apply()
    }

    /** Tinggi panel menu bubble (dp) yang diatur pengguna lewat pegangan di bawah panel. 0 = otomatis sesuai isi. */
    fun menuHeight(c: Context): Int = sp(c).getInt("menuHeight", 0)

    fun setMenuHeight(c: Context, dp: Int) {
        sp(c).edit().putInt("menuHeight", dp).apply()
    }

    fun pinned(c: Context): List<String> =
        (sp(c).getString("pinned", "") ?: "").split(',').filter { it.isNotBlank() }

    fun setPinned(c: Context, list: List<String>) {
        sp(c).edit().putString("pinned", list.joinToString(",")).apply()
    }
}
