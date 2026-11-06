package com.example.floatstudy

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler

/**
 * Menyimpan frame layar terakhir dari MediaProjection.
 * Frame hanya dibaca saat pengguna menekan tombol di bubble.
 */
class ScreenGrabber(private val ctx: Context, private val ui: Handler) {

    private var projection: MediaProjection? = null
    private var projectionCb: MediaProjection.Callback? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var last: Image? = null
    private var w = 0
    private var h = 0
    private var dpi = 0

    val active: Boolean get() = projection != null

    fun start(code: Int, data: Intent, width: Int, height: Int, densityDpi: Int, onStopped: () -> Unit) {
        val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data)
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                release()
                onStopped()
            }
        }
        p.registerCallback(cb, ui)
        w = width
        h = height
        dpi = densityDpi
        val r = newReader(w, h)
        display = p.createVirtualDisplay(
            "floatstudy", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, ui
        )
        reader = r
        projection = p
        projectionCb = cb
    }

    private fun newReader(width: Int, height: Int): ImageReader {
        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd ->
            val img = try {
                rd.acquireLatestImage()
            } catch (e: Exception) {
                null
            }
            if (img != null) {
                last?.close()
                last = img
            }
        }, ui)
        return r
    }

    // Dipanggil saat layar berputar. Tidak membuat VirtualDisplay baru (Android 14 melarang).
    fun resizeIfNeeded(width: Int, height: Int) {
        if (projection == null || (width == w && height == h)) return
        val old = reader
        last?.close()
        last = null
        val r = newReader(width, height)
        display?.resize(width, height, dpi)
        display?.surface = r.surface
        reader = r
        old?.close()
        w = width
        h = height
    }

    fun latest(): Bitmap? {
        val img = last ?: return null
        return try {
            val plane = img.planes[0]
            val buf = plane.buffer
            buf.rewind()
            val pw = img.width
            val rowPad = plane.rowStride - plane.pixelStride * pw
            val wide = Bitmap.createBitmap(
                pw + rowPad / plane.pixelStride, img.height, Bitmap.Config.ARGB_8888
            )
            wide.copyPixelsFromBuffer(buf)
            if (rowPad == 0) wide else Bitmap.createBitmap(wide, 0, 0, pw, img.height)
        } catch (e: Exception) {
            null
        }
    }

    /** Sidik frame kecil (24x48, abu-abu) untuk mendeteksi layar berubah tanpa membandingkan seluruh piksel. */
    fun signature(): IntArray? {
        val full = latest() ?: return null
        val small = Bitmap.createScaledBitmap(full, 24, 48, true)
        if (small !== full) full.recycle()
        val px = IntArray(24 * 48)
        small.getPixels(px, 0, 24, 0, 0, 24, 48)
        small.recycle()
        return IntArray(px.size) {
            val p = px[it]
            (((p shr 16) and 0xFF) * 3 + ((p shr 8) and 0xFF) * 6 + (p and 0xFF)) / 10
        }
    }

    fun release() {
        last?.close()
        last = null
        display?.release()
        display = null
        reader?.close()
        reader = null
        val p = projection
        val cb = projectionCb
        projection = null
        projectionCb = null
        if (p != null) {
            if (cb != null) p.unregisterCallback(cb)
            p.stop()
        }
    }
}
