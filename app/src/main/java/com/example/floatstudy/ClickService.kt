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

        /** Layar bergulir (dy piksel, ada = selisih diketahui). Dipakai kotak jawaban Answer agar ikut bergeser. */
        @Volatile
        var onScroll: ((Int, Boolean) -> Unit)? = null

        /** Terjemahan langsung layar (Inggris ke Indonesia). Lihat LiveTranslator. */
        fun liveActive(): Boolean = instance?.live?.active == true

        /** Nyalakan atau matikan terjemahan langsung. Mengembalikan false bila layanan aksesibilitas belum aktif. */
        fun setLive(on: Boolean, say: (String) -> Unit): Boolean {
            val s = instance ?: return false
            try {
                val t = s.live ?: LiveTranslator(s).also { s.live = it }
                if (on) t.start(say) else t.stop()
            } catch (e: Exception) {
                Diag.add(s, "error setLive($on): ${e.javaClass.simpleName} ${e.message ?: ""}")
                return false
            }
            return true
        }

        /**
         * Baca semua teks node yang berada di dalam kotak [box] (koordinat layar) dari jendela aktif.
         * Satu kali baca saat diminta, tanpa tangkap layar. Kolom sandi tidak dibaca. Null bila layanan belum aktif.
         */
        fun readBox(box: android.graphics.Rect): String? {
            val s = instance ?: return null
            val root = s.rootInActiveWindow ?: return ""
            val found = ArrayList<Triple<Int, Int, String>>()
            fun walk(n: AccessibilityNodeInfo, depth: Int) {
                if (depth > 60 || found.size >= 400 || !n.isVisibleToUser || n.isPassword) return
                val t = (n.text ?: n.contentDescription)?.toString()?.trim()
                if (!t.isNullOrEmpty()) {
                    val r = android.graphics.Rect()
                    n.getBoundsInScreen(r)
                    val area = r.width().toLong() * r.height()
                    val i = android.graphics.Rect(r)
                    // masuk bila minimal separuh luas node berada di dalam kotak
                    if (area > 0 && i.intersect(box) && i.width().toLong() * i.height() * 2 >= area) {
                        found.add(Triple(r.top, r.left, t))
                    }
                }
                for (k in 0 until n.childCount) {
                    val ch = n.getChild(k) ?: continue
                    walk(ch, depth + 1)
                }
            }
            try {
                walk(root, 0)
            } catch (e: Exception) {
            }
            // urut atas ke bawah lalu kiri ke kanan, teks kembar dibuang
            val seen = HashSet<String>()
            return found.sortedWith(compareBy({ it.first / 12 }, { it.second }))
                .map { it.third }.filter { seen.add(it) }.joinToString("\n")
        }

        /** Tangkap layar penuh lewat aksesibilitas (Android 11+). Hasil null bila gagal atau tidak didukung. */
        fun screenshot(cb: (android.graphics.Bitmap?) -> Unit) {
            val s = instance
            if (s == null || android.os.Build.VERSION.SDK_INT < 30) {
                cb(null)
                return
            }
            try {
                s.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, s.mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(r: ScreenshotResult) {
                        val bmp = try {
                            val hb = r.hardwareBuffer
                            val b = android.graphics.Bitmap.wrapHardwareBuffer(hb, r.colorSpace)
                                ?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                            hb.close()
                            b
                        } catch (e: Exception) {
                            null
                        }
                        cb(bmp)
                    }

                    override fun onFailure(errorCode: Int) = cb(null)
                })
            } catch (e: Exception) {
                cb(null)
            }
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
        fun armAutoInstall(ms: Long = 120_000L, c: android.content.Context? = null): Boolean {
            // beberapa ROM mematikan layanan aksesibilitas setelah aplikasi diperbarui/dihentikan: nyalakan lagi bila izin khusus ada
            if (instance == null && c != null) tryEnable(c)
            autoInstallUntil = android.os.SystemClock.uptimeMillis() + ms
            return instance != null
        }

        /** Nyalakan layanan lewat Secure Settings. Hanya berhasil bila izin WRITE_SECURE_SETTINGS pernah diberikan lewat adb. */
        fun tryEnable(c: android.content.Context): Boolean {
            if (instance != null) return true
            if (c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
            return try {
                val cn = android.content.ComponentName(c, ClickService::class.java)
                val full = cn.flattenToString()
                val short = cn.flattenToShortString()
                val cr = c.contentResolver
                val cur = android.provider.Settings.Secure.getString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
                val parts = cur.split(':').filter { it.isNotBlank() }
                if (full !in parts && short !in parts) {
                    android.provider.Settings.Secure.putString(
                        cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (parts + full).joinToString(":")
                    )
                }
                android.provider.Settings.Secure.putInt(cr, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                true
            } catch (e: Exception) {
                false
            }
        }

        /** Jendela pemasang paket: daftar bawaan, ditambah nama paket apa pun yang memuat "packageinstaller" (ROM kustom). */
        private fun isInstaller(pkg: String?): Boolean =
            pkg != null && (pkg in INSTALLERS || pkg.contains("packageinstaller", true) || pkg.contains("installer", true))
    }

    private var live: LiveTranslator? = null

    override fun onServiceConnected() {
        instance = this
        Diag.installCrashLog(this)
        Diag.add(this, "aksesibilitas tersambung")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Diag.add(this, "aksesibilitas terputus (onUnbind)")
        try {
            live?.stop()
        } catch (e: Exception) {
        }
        instance = null
        onLost?.invoke()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Diag.add(this, "aksesibilitas dihancurkan (onDestroy)")
        try {
            live?.stop()
        } catch (e: Exception) {
        }
        instance = null
        onLost?.invoke()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // error di Terjemah Langsung tidak boleh menjatuhkan proses: crash membuat Android memutus layanan aksesibilitas
        if (event != null && event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && event.packageName?.toString() != packageName) {
            try {
                val dy = if (android.os.Build.VERSION.SDK_INT >= 28) event.scrollDeltaY else 0
                val dx = if (android.os.Build.VERSION.SDK_INT >= 28) event.scrollDeltaX else 0
                onScroll?.invoke(dy, dx == 0 && dy != 0)
            } catch (e: Exception) {
            }
        }
        if (event != null) {
            try {
                live?.onEvent(event)
            } catch (e: Exception) {
                Diag.add(this, "error Terjemah Langsung: ${e.javaClass.simpleName} ${e.message ?: ""}")
            }
        }
        if (autoInstallUntil == 0L) return
        if (android.os.SystemClock.uptimeMillis() > autoInstallUntil) {
            autoInstallUntil = 0L
            return
        }
        val pkg = event?.packageName?.toString() ?: return
        if (!isInstaller(pkg)) return
        val root = rootInActiveWindow ?: return
        try {
            if (!isInstaller(root.packageName?.toString())) return
            val btn = findInstallButton(root) ?: return
            val label = btn.text?.toString()?.trim()?.lowercase() ?: ""
            // pengaman: jendela pemasang harus menyebut flooww (nama atau paket), atau berupa PEMBARUAN aplikasi yang sudah ada.
            // Pemasangan baru aplikasi lain tidak pernah ditekan otomatis.
            val mine = !root.findAccessibilityNodeInfosByText("flooww").isNullOrEmpty() ||
                !root.findAccessibilityNodeInfosByText(packageName).isNullOrEmpty()
            val isUpdate = label in setOf("update", "perbarui", "upgrade")
            if (!mine && !isUpdate) return
            if (clickNode(btn)) {
                // tetap siaga 15 detik untuk layar lanjutan (mis. konfirmasi kedua), lalu berhenti
                autoInstallUntil = minOf(autoInstallUntil, android.os.SystemClock.uptimeMillis() + 15_000L)
            }
        } catch (e: Exception) {
        }
    }

    private fun findInstallButton(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val t = (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase()
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
        // tombol tidak mau menerima aksi klik di ROM ini: ketuk tepat di tengahnya dengan gestur
        val r = android.graphics.Rect()
        n.getBoundsInScreen(r)
        if (r.width() > 0 && r.height() > 0) {
            tap(r.exactCenterX(), r.exactCenterY())
            return true
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
