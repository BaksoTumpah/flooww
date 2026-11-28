package com.example.floatstudy

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Kamera flooww sendiri (Camera2, tanpa pustaka tambahan): foto dan video, kamera depan dan belakang.
 * Tampil di jendela overlay flooww, jadi tidak mengambil fokus aplikasi di bawahnya.
 * Hasil disimpan di Pictures/flooww dan Movies/flooww.
 */
class Cam(
    private val c: Context,
    private val ui: Handler,
    private val say: (String) -> Unit,
    private val askPerm: () -> Unit
) {
    private val mgr = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var thread: HandlerThread? = null
    private var bg: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var recorder: MediaRecorder? = null
    private var recUri: Uri? = null
    private var recFile: File? = null
    private var recPfd: ParcelFileDescriptor? = null
    private var chars: CameraCharacteristics? = null
    private var previewSize: Size? = null
    private var started = false
    private var opening = false
    private var recording = false
    private var recT0 = 0L
    private var front = false
    private var video = false
    private var askedOnce = false

    private lateinit var box: FrameLayout
    private lateinit var tex: TextureView
    private lateinit var msg: TextView
    private lateinit var permBtn: TextView
    private lateinit var timer: TextView
    private lateinit var shutter: View
    private lateinit var modePhoto: TextView
    private lateinit var modeVideo: TextView
    private lateinit var flipBtn: TextView

    private fun dp(v: Int) = (v * c.resources.displayMetrics.density).toInt()
    private fun hasCam() = c.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun hasMic() = c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun rotation(): Int = (c.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation

    // ---------- tampilan ----------

    fun view(): View {
        val pal = Ui.pal(c)
        val root = FrameLayout(c).apply { setBackgroundColor(0xFF000000.toInt()) }
        box = FrameLayout(c).apply { clipChildren = true }
        tex = TextureView(c)
        box.addView(tex, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        tex.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                if (started && device == null) openCamera()
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        box.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (r - l != orr - ol || b - t != ob - ot) applyPreviewLayout()
        }
        root.addView(box, FrameLayout.LayoutParams(-1, -1))

        timer = TextView(c).apply {
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = Ui.round(0xCCDC2626.toInt(), dp(12))
            visibility = View.GONE
        }
        root.addView(timer, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(10), dp(10), 0, 0) })

        msg = TextView(c).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(20), dp(12), dp(20), dp(12))
            visibility = View.GONE
        }
        permBtn = TextView(c).apply {
            text = "Beri izin"
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Ui.onAccent(Prefs.bubbleColor(c)))
            background = Ui.round(Prefs.bubbleColor(c), dp(16))
            setPadding(dp(22), dp(10), dp(22), dp(10))
            visibility = View.GONE
            setOnClickListener { askPerm() }
        }
        val center = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(msg)
            addView(permBtn)
        }
        root.addView(center, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))

        // ---- kontrol bawah: FOTO | VIDEO, tombol rana, balik kamera ----
        fun chip(label: String) = TextView(c).apply {
            text = label
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        modePhoto = chip("FOTO").apply { setOnClickListener { setVideo(false) } }
        modeVideo = chip("VIDEO").apply { setOnClickListener { setVideo(true) } }
        val modes = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(modePhoto)
            addView(modeVideo)
            background = Ui.round(0x99000000.toInt(), dp(14))
        }
        shutter = object : View(c) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(cv: Canvas) {
                val w = width.toFloat()
                val cx = w / 2f
                p.style = Paint.Style.STROKE
                p.strokeWidth = dp(4).toFloat()
                p.color = 0xFFFFFFFF.toInt()
                cv.drawCircle(cx, cx, cx - dp(3), p)
                p.style = Paint.Style.FILL
                if (recording) {
                    p.color = 0xFFDC2626.toInt()
                    val s = w * 0.28f
                    cv.drawRoundRect(RectF(cx - s, cx - s, cx + s, cx + s), dp(5).toFloat(), dp(5).toFloat(), p)
                } else {
                    p.color = if (video) 0xFFDC2626.toInt() else 0xFFFFFFFF.toInt()
                    cv.drawCircle(cx, cx, cx - dp(11), p)
                }
            }
        }.apply { setOnClickListener { onShutter() } }
        flipBtn = chip("BALIK").apply {
            background = Ui.round(0x99000000.toInt(), dp(14))
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setOnClickListener { flip() }
        }
        val bar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(modes, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(18) })
            addView(shutter, LinearLayout.LayoutParams(dp(68), dp(68)))
            addView(flipBtn, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(18) })
        }
        root.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(14) })
        paintModes()
        // pal dipakai agar pewarnaan konsisten dengan tema bila nanti dibutuhkan
        @Suppress("UNUSED_VARIABLE") val unused = pal
        return root
    }

    private fun paintModes() {
        fun on(t: TextView, sel: Boolean) {
            t.setTextColor(if (sel) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            t.background = if (sel) Ui.round(0xFFFFFFFF.toInt(), dp(14)) else null
        }
        on(modePhoto, !video)
        on(modeVideo, video)
        shutter.invalidate()
    }

    private fun showMsg(t: String, perm: Boolean) {
        msg.text = t
        msg.visibility = View.VISIBLE
        permBtn.visibility = if (perm) View.VISIBLE else View.GONE
    }

    private fun hideMsg() {
        msg.visibility = View.GONE
        permBtn.visibility = View.GONE
    }

    // ---------- siklus hidup ----------

    /** Dipanggil saat alat Kamera tampil. */
    fun start() {
        if (started || !::tex.isInitialized) return
        if (!hasCam()) {
            showMsg("Kamera butuh izin kamera.", true)
            if (!askedOnce) {
                askedOnce = true
                askPerm()
            }
            return
        }
        hideMsg()
        started = true
        val t = HandlerThread("flooww-cam").also { it.start() }
        thread = t
        bg = Handler(t.looper)
        if (tex.isAvailable) openCamera()
    }

    /** Izin baru diberikan: coba lagi. */
    fun retry() {
        if (started) return
        start()
    }

    /** Dipanggil saat alat Kamera disembunyikan atau layanan berhenti. Kamera dilepas supaya aplikasi lain bisa memakainya. */
    fun stop() {
        if (recording) stopRecord(false)
        started = false
        opening = false
        try {
            session?.close()
        } catch (e: Exception) {
        }
        session = null
        try {
            device?.close()
        } catch (e: Exception) {
        }
        device = null
        try {
            reader?.close()
        } catch (e: Exception) {
        }
        reader = null
        try {
            previewSurface?.release()
        } catch (e: Exception) {
        }
        previewSurface = null
        thread?.quitSafely()
        thread = null
        bg = null
        if (::timer.isInitialized) timer.visibility = View.GONE
    }

    // ---------- membuka kamera ----------

    private fun pickId(wantFront: Boolean): String? {
        val want = if (wantFront) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        for (id in mgr.cameraIdList) {
            val ch = mgr.getCameraCharacteristics(id)
            if (ch.get(CameraCharacteristics.LENS_FACING) != want) continue
            if (ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) == null) continue
            return id
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val h = bg ?: return
        if (opening || device != null) return
        try {
            val id = pickId(front)
            if (id == null) {
                ui.post { showMsg("Kamera ${if (front) "depan" else "belakang"} tidak ditemukan di HP ini.", false) }
                return
            }
            chars = mgr.getCameraCharacteristics(id)
            opening = true
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    opening = false
                    if (!started) {
                        d.close()
                        return
                    }
                    device = d
                    createPreviewSession()
                }

                override fun onDisconnected(d: CameraDevice) {
                    opening = false
                    d.close()
                    if (device === d) device = null
                    ui.post { showMsg("Kamera terputus (mungkin dipakai aplikasi lain).", false) }
                }

                override fun onError(d: CameraDevice, error: Int) {
                    opening = false
                    d.close()
                    if (device === d) device = null
                    Diag.add(c, "kamera gagal dibuka, kode $error")
                    ui.post { showMsg("Kamera gagal dibuka (kode $error). Bisa karena dipakai aplikasi lain atau ditolak sistem saat berjalan di latar belakang.", false) }
                }
            }, h)
        } catch (e: CameraAccessException) {
            opening = false
            Diag.add(c, "kamera ditolak: ${e.javaClass.simpleName} ${e.message}")
            ui.post { showMsg("Kamera ditolak sistem: ${e.message ?: "akses ditolak"}", false) }
        } catch (e: Exception) {
            opening = false
            Diag.add(c, "kamera gagal: ${e.javaClass.simpleName} ${e.message}")
            ui.post { showMsg("Kamera gagal dibuka: ${e.javaClass.simpleName}", false) }
        }
    }

    // ---------- ukuran ----------

    private fun area(s: Size) = s.width.toLong() * s.height

    private fun stillSize(): Size? {
        val map = chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        return map.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { area(it) }
    }

    /** Ukuran video: paling besar sampai 1920x1080, mengutamakan 16:9. */
    private fun videoSize(): Size? {
        val map = chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val all = (map.getOutputSizes(MediaRecorder::class.java) ?: return null).filter { it.width <= 1920 && it.height <= 1080 }
        val wide = all.filter { kotlin.math.abs(it.width / it.height.toFloat() - 16f / 9f) < 0.03f }
        return (if (wide.isNotEmpty()) wide else all).maxByOrNull { area(it) }
    }

    /** Ukuran pratinjau: sebangun dengan hasil (foto atau video), paling besar sampai 1920x1080. */
    private fun previewFor(target: Size): Size? {
        val map = chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val sizes = (map.getOutputSizes(SurfaceTexture::class.java) ?: return null).filter { it.width <= 1920 && it.height <= 1080 }
        val ta = target.width / target.height.toFloat()
        val same = sizes.filter { kotlin.math.abs(it.width / it.height.toFloat() - ta) < 0.02f }
        return (if (same.isNotEmpty()) same else sizes).maxByOrNull { area(it) }
    }

    /** Pasang TextureView seukuran pratinjau (tanpa melar), memenuhi kotak dan terpotong di tepi bila perlu. */
    private fun applyPreviewLayout() {
        val ps = previewSize ?: return
        val bw = box.width
        val bh = box.height
        if (bw == 0 || bh == 0) return
        val rot = rotation()
        val land = rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270
        val a = if (land) ps.width / ps.height.toFloat() else ps.height / ps.width.toFloat()
        val w: Int
        val h: Int
        if (bw / bh.toFloat() > a) {
            w = bw
            h = (bw / a).toInt()
        } else {
            h = bh
            w = (bh * a).toInt()
        }
        tex.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
        val m = Matrix()
        if (land) {
            // sama seperti contoh Camera2 resmi untuk layar landscape
            val viewRect = RectF(0f, 0f, w.toFloat(), h.toFloat())
            val bufRect = RectF(0f, 0f, ps.height.toFloat(), ps.width.toFloat())
            val cx = viewRect.centerX()
            val cy = viewRect.centerY()
            bufRect.offset(cx - bufRect.centerX(), cy - bufRect.centerY())
            m.setRectToRect(viewRect, bufRect, Matrix.ScaleToFit.FILL)
            val s = maxOf(h.toFloat() / ps.height, w.toFloat() / ps.width)
            m.postScale(s, s, cx, cy)
            m.postRotate(90f * (rot - 2), cx, cy)
        } else if (rot == Surface.ROTATION_180) {
            m.postRotate(180f, w / 2f, h / 2f)
        }
        tex.setTransform(m)
        // pratinjau kamera depan dicerminkan seperti bercermin
        tex.pivotX = w / 2f
        tex.scaleX = if (front) -1f else 1f
    }

    // ---------- sesi pratinjau ----------

    @Suppress("DEPRECATION")
    private fun createPreviewSession() {
        val d = device ?: return
        val ch = chars ?: return
        val st = tex.surfaceTexture
        if (st == null) {
            ui.post { showMsg("Layar kamera belum siap, coba lagi.", false) }
            return
        }
        val still = stillSize()
        val vid = videoSize()
        val target = (if (video) vid else still) ?: still ?: vid
        val ps = target?.let { previewFor(it) }
        if (ps == null) {
            ui.post { showMsg("HP tidak memberi ukuran pratinjau.", false) }
            return
        }
        previewSize = ps
        st.setDefaultBufferSize(ps.width, ps.height)
        ui.post {
            applyPreviewLayout()
            hideMsg()
        }
        try {
            session?.close()
        } catch (e: Exception) {
        }
        session = null
        previewSurface?.release()
        val surf = Surface(st)
        previewSurface = surf
        val targets = mutableListOf(surf)
        if (!video && still != null) {
            reader?.close()
            val r = ImageReader.newInstance(still.width, still.height, ImageFormat.JPEG, 2)
            r.setOnImageAvailableListener({ rd -> saveImage(rd) }, bg)
            reader = r
            targets.add(r.surface)
        }
        try {
            d.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (device !== d || !started) return
                    session = s
                    try {
                        val rb = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        rb.addTarget(surf)
                        setAf(rb, ch, video)
                        s.setRepeatingRequest(rb.build(), null, bg)
                    } catch (e: Exception) {
                        ui.post { showMsg("Pratinjau gagal: ${e.javaClass.simpleName}", false) }
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    ui.post { showMsg("Gagal menyiapkan kamera.", false) }
                }
            }, bg)
        } catch (e: Exception) {
            ui.post { showMsg("Gagal menyiapkan kamera: ${e.javaClass.simpleName}", false) }
        }
    }

    private fun setAf(rb: CaptureRequest.Builder, ch: CameraCharacteristics, forVideo: Boolean) {
        val modes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        val want = if (forVideo) CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        if (modes.contains(want)) rb.set(CaptureRequest.CONTROL_AF_MODE, want)
        rb.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
    }

    // ---------- tombol ----------

    private fun setVideo(v: Boolean) {
        if (recording || video == v) return
        video = v
        paintModes()
        if (device != null) createPreviewSession()
    }

    private fun flip() {
        if (recording) {
            say("Hentikan rekaman dulu")
            return
        }
        front = !front
        stop()
        start()
    }

    private fun onShutter() {
        if (device == null || session == null) {
            say("Kamera belum siap")
            return
        }
        if (video) {
            if (recording) stopRecord(true) else startRecord()
        } else {
            takePhoto()
        }
    }

    // ---------- foto ----------

    /** Arah gambar hasil: sudut sensor ditambah (kamera depan: dikurangi) arah HP, dari rotasi layar. */
    private fun outOrientation(): Int {
        val so = chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val dev = when (rotation()) {
            Surface.ROTATION_90 -> 270
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 90
            else -> 0
        }
        return if (front) (so - dev + 360) % 360 else (so + dev) % 360
    }

    private fun takePhoto() {
        val d = device ?: return
        val s = session ?: return
        val r = reader ?: return
        val ch = chars ?: return
        try {
            val rb = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            rb.addTarget(r.surface)
            setAf(rb, ch, false)
            rb.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            rb.set(CaptureRequest.JPEG_ORIENTATION, outOrientation())
            s.capture(rb.build(), null, bg)
            ui.post { shutter.animate().scaleX(0.85f).scaleY(0.85f).setDuration(70).withEndAction { shutter.animate().scaleX(1f).scaleY(1f).setDuration(90).start() }.start() }
        } catch (e: Exception) {
            say("Gagal memotret: ${e.javaClass.simpleName}")
        }
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun saveImage(rd: ImageReader) {
        val img = try {
            rd.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return
        val bytes = try {
            val buf = img.planes[0].buffer
            ByteArray(buf.remaining()).also { buf.get(it) }
        } finally {
            img.close()
        }
        try {
            val name = "flooww_${stamp()}.jpg"
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/flooww")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = c.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: throw IllegalStateException("insert gagal")
                c.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                cv.clear()
                cv.put(MediaStore.MediaColumns.IS_PENDING, 0)
                c.contentResolver.update(uri, cv, null, null)
                say("Foto disimpan di Pictures/flooww")
            } else {
                val dir = c.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: throw IllegalStateException("folder tidak ada")
                File(dir, name).writeBytes(bytes)
                say("Foto disimpan di folder aplikasi flooww")
            }
        } catch (e: Exception) {
            say("Gagal menyimpan foto: ${e.javaClass.simpleName}")
        }
    }

    // ---------- video ----------

    @Suppress("DEPRECATION")
    private fun startRecord() {
        val d = device ?: return
        val ch = chars ?: return
        val vs = videoSize()
        val st = tex.surfaceTexture
        if (vs == null || st == null) {
            say("HP tidak mendukung rekam video di kamera ini")
            return
        }
        try {
            val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(c) else MediaRecorder()
            val mic = hasMic()
            if (mic) rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            val name = "flooww_${stamp()}.mp4"
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/flooww")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = c.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: throw IllegalStateException("insert gagal")
                val pfd = c.contentResolver.openFileDescriptor(uri, "w") ?: throw IllegalStateException("berkas tidak bisa dibuka")
                recUri = uri
                recPfd = pfd
                rec.setOutputFile(pfd.fileDescriptor)
            } else {
                val dir = c.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: throw IllegalStateException("folder tidak ada")
                val f = File(dir, name)
                recFile = f
                rec.setOutputFile(f.absolutePath)
            }
            rec.setVideoEncodingBitRate((vs.width * vs.height * 5).coerceIn(2_000_000, 16_000_000))
            rec.setVideoFrameRate(30)
            rec.setVideoSize(vs.width, vs.height)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (mic) {
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setAudioEncodingBitRate(128_000)
                rec.setAudioSamplingRate(44_100)
            }
            rec.setOrientationHint(outOrientation())
            rec.prepare()
            recorder = rec

            val ps = previewFor(vs) ?: vs
            previewSize = ps
            st.setDefaultBufferSize(ps.width, ps.height)
            try {
                session?.close()
            } catch (e: Exception) {
            }
            session = null
            previewSurface?.release()
            val surf = Surface(st)
            previewSurface = surf
            d.createCaptureSession(listOf(surf, rec.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (device !== d || !started) return
                    session = s
                    try {
                        val rb = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        rb.addTarget(surf)
                        rb.addTarget(rec.surface)
                        setAf(rb, ch, true)
                        s.setRepeatingRequest(rb.build(), null, bg)
                        rec.start()
                        recording = true
                        recT0 = System.currentTimeMillis()
                        ui.post {
                            paintModes()
                            timer.visibility = View.VISIBLE
                            tick()
                            if (!mic) say("Merekam tanpa suara (izin mikrofon belum diberikan)")
                        }
                    } catch (e: Exception) {
                        cleanupRecorder(true)
                        say("Gagal mulai merekam: ${e.javaClass.simpleName}")
                        createPreviewSession()
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    cleanupRecorder(true)
                    say("Gagal menyiapkan rekaman")
                    createPreviewSession()
                }
            }, bg)
        } catch (e: Exception) {
            cleanupRecorder(true)
            say("Gagal memulai video: ${e.javaClass.simpleName} ${e.message ?: ""}".trim())
        }
    }

    private fun tick() {
        if (!recording) return
        val s = (System.currentTimeMillis() - recT0) / 1000
        timer.text = "● %02d:%02d".format(s / 60, s % 60)
        ui.postDelayed({ tick() }, 500)
    }

    /** Hentikan rekaman dan simpan. refreshPreview = false saat kamera sedang ditutup. */
    private fun stopRecord(refreshPreview: Boolean) {
        val rec = recorder ?: return
        recording = false
        var ok = true
        try {
            rec.stop()
        } catch (e: RuntimeException) {
            ok = false // rekaman terlalu singkat
        }
        cleanupRecorder(!ok)
        if (ok) say("Video disimpan di Movies/flooww") else say("Rekaman terlalu singkat, tidak disimpan")
        ui.post {
            timer.visibility = View.GONE
            paintModes()
        }
        if (refreshPreview) createPreviewSession()
    }

    /** Lepas MediaRecorder dan tutup berkas. delete = hapus berkas yang gagal. */
    private fun cleanupRecorder(delete: Boolean) {
        try {
            recorder?.reset()
            recorder?.release()
        } catch (e: Exception) {
        }
        recorder = null
        recording = false
        try {
            recPfd?.close()
        } catch (e: Exception) {
        }
        recPfd = null
        val uri = recUri
        if (uri != null) {
            try {
                if (delete) {
                    c.contentResolver.delete(uri, null, null)
                } else {
                    val cv = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    c.contentResolver.update(uri, cv, null, null)
                }
            } catch (e: Exception) {
            }
        }
        recUri = null
        val f = recFile
        if (f != null && delete) f.delete()
        recFile = null
    }
}
