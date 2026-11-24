package com.example.floatstudy

import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Catatan kejadian singkat di dalam HP (tidak dikirim ke mana pun) untuk mencari penyebab masalah di ROM tertentu.
 * Pengguna bisa melihat dan menyalinnya dari Pengaturan, lalu mengirimkannya ke pengembang secara manual.
 */
object Diag {
    private const val KEY = "diagLog"
    private const val MAX = 80
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(c: Context, msg: String) {
        try {
            val sp = Prefs.sp(c)
            val lines = (sp.getString(KEY, "") ?: "").split('\n').filter { it.isNotBlank() }.toMutableList()
            lines.add(fmt.format(Date()) + " " + msg.replace('\n', ' ').take(220))
            while (lines.size > MAX) lines.removeAt(0)
            sp.edit().putString(KEY, lines.joinToString("\n")).apply()
        } catch (e: Exception) {
        }
    }

    @Synchronized
    fun clear(c: Context) {
        Prefs.sp(c).edit().remove(KEY).apply()
    }

    fun webViewInfo(): String = try {
        val p = if (Build.VERSION.SDK_INT >= 26) android.webkit.WebView.getCurrentWebViewPackage() else null
        if (p != null) "${p.packageName} ${p.versionName}" else "tidak diketahui"
    } catch (e: Exception) {
        "tidak diketahui"
    }

    /** Versi utama (angka pertama) WebView sistem, atau 0 bila tidak terbaca. */
    fun webViewMajor(): Int = try {
        val p = if (Build.VERSION.SDK_INT >= 26) android.webkit.WebView.getCurrentWebViewPackage() else null
        p?.versionName?.substringBefore('.')?.toIntOrNull() ?: 0
    } catch (e: Exception) {
        0
    }

    fun report(c: Context): String {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val head = "flooww ${BuildConfig.VERSION_NAME}\n" +
            "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "RAM ${mi.totalMem / 1048576} MB, sisa ${mi.availMem / 1048576} MB, RAM rendah: ${am.isLowRamDevice}\n" +
            "ABI HP: ${Build.SUPPORTED_ABIS.joinToString()}\n" +
            "WebView: ${webViewInfo()}\n" +
            "Tampil di atas aplikasi lain: ${android.provider.Settings.canDrawOverlays(c)}, aksesibilitas aktif: ${ClickService.instance != null}\n" +
            "Jendela bebas: ${android.provider.Settings.Global.getInt(c.contentResolver, "enable_freeform_support", 0)}\n---\n"
        return head + ((Prefs.sp(c).getString(KEY, "") ?: "").ifBlank { "(belum ada catatan)" })
    }
}
