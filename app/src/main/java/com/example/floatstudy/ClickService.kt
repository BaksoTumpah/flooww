package com.example.floatstudy

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * Layanan aksesibilitas khusus untuk Auto Clicker.
 * Hanya mengirim ketukan ke titik yang ditentukan pengguna. Tidak membaca isi layar.
 */
class ClickService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ClickService? = null

        /** Dipanggil bila layanan terputus (dimatikan pengguna atau sistem). FloatService memakainya untuk pindah ke overlay biasa. */
        @Volatile
        var onLost: (() -> Unit)? = null
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        onLost?.invoke()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        onLost?.invoke()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun tap(x: Float, y: Float) {
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 40))
            .build()
        dispatchGesture(g, null, null)
    }
}
