package com.example.floatstudy

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Layanan aksesibilitas khusus untuk Auto Clicker.
 * Mengirim ketukan ke titik yang ditentukan pengguna. Selain itu, saat pembaruan flooww sedang dipasang,
 * menekan tombol Pasang/Perbarui di jendela pemasang paket (hanya pemasang bawaan HP, hanya untuk flooww).
 */
class ClickService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ClickService? = null

        /** Dipanggil bila layanan terputus (dimatikan pengguna atau sistem). FloatService memakainya untuk pindah ke overlay biasa. */
        @Volatile
        var onLost: (() -> Unit)? = null

        /** Terjemahan langsung layar (Inggris ke Indonesia). Lihat LiveTranslator. */
        fun liveActive(): Boolean = instance?.live?.active == true

        /** Nyalakan atau matikan terjemahan langsung. Mengembalikan false bila layanan aksesibilitas belum aktif. */
        fun setLive(on: Boolean, say: (String) -> Unit): Boolean {
            val s = instance ?: return false
            val t = s.live ?: LiveTranslator(s).also { s.live = it }
            if (on) t.start(say) else t.stop()
            return true
        }

        /** Batas waktu (uptime ms) klik otomatis tombol Pasang. 0 = tidak aktif. */
        @Volatile
        private var autoInstallUntil = 0L

        private val INSTALLERS = setOf(
            "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.miui.packageinstaller", "com.samsung.android.packageinstaller",
            "com.oplus.packageinstaller", "com.coloros.packageinstaller",
            "com.vivo.packageinstaller", "com.huawei.android.packageinstaller"
        )

        // teks tombol pemasangan (Indonesia dan Inggris); harus sama persis, bukan sebagian
        private val LABELS = setOf("install", "instal", "pasang", "update", "perbarui", "upgrade")

        /** Aktifkan klik otomatis selama 2 menit. Dipanggil tepat saat pemasang paket dibuka untuk pembaruan. Mengembalikan false bila layanan aksesibilitas tidak aktif. */
        fun armAutoInstall(ms: Long = 120_000L): Boolean {
            if (instance == null) return false
            autoInstallUntil = android.os.SystemClock.uptimeMillis() + ms
            return true
        }
    }

    private var live: LiveTranslator? = null

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        live?.stop()
        instance = null
        onLost?.invoke()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        live?.stop()
        instance = null
        onLost?.invoke()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) live?.onEvent(event)
        if (autoInstallUntil == 0L) return
        if (android.os.SystemClock.uptimeMillis() > autoInstallUntil) {
            autoInstallUntil = 0L
            return
        }
        val pkg = event?.packageName?.toString() ?: return
        if (pkg !in INSTALLERS) return
        val root = rootInActiveWindow ?: return
        try {
            if (root.packageName?.toString() !in INSTALLERS) return
            // pengaman: hanya jika jendela pemasang menyebut flooww, supaya tidak memasang aplikasi lain
            if (root.findAccessibilityNodeInfosByText("flooww").isNullOrEmpty()) return
            val btn = findInstallButton(root) ?: return
            if (clickNode(btn)) autoInstallUntil = 0L
        } catch (e: Exception) {
        }
    }

    private fun findInstallButton(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val t = n.text?.toString()?.trim()?.lowercase()
        if (t != null && t in LABELS && n.isEnabled) return n
        for (i in 0 until n.childCount) {
            val ch = n.getChild(i) ?: continue
            findInstallButton(ch)?.let { return it }
        }
        return null
    }

    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 4) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
            depth++
        }
        return false
    }

    override fun onInterrupt() {}

    fun tap(x: Float, y: Float) {
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 40))
            .build()
        dispatchGesture(g, null, null)
    }
}
