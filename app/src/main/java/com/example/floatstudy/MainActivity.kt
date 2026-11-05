package com.example.floatstudy

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private val reqOverlay = 1
    private val reqProjection = 2
    private val reqNotif = 3

    private var keyIn: EditText? = null
    private lateinit var modelIn: EditText
    private lateinit var status: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(20))
        }

        col.addView(TextView(this).apply {
            text = "FloatStudy"
            textSize = 26f
            setTextColor(Color.parseColor("#2563EB"))
        })
        col.addView(TextView(this).apply {
            text = "Bubble mengambang untuk belajar. Ketuk bubble, pilih alat, lalu pakai. Ukuran, warna, dan opasitas diatur lewat menu ⚙ Atur."
            textSize = 14f
            setPadding(0, dp(4), 0, dp(16))
        })

        if (Prefs.hasBuiltInKey()) {
            col.addView(TextView(this).apply {
                text = "API key: sudah tertanam di aplikasi. Tidak perlu diisi."
                textSize = 13f
                setTextColor(Color.parseColor("#16A34A"))
                setPadding(0, 0, 0, dp(8))
            })
        } else {
            col.addView(TextView(this).apply {
                text = "API key belum tertanam saat build. Isi manual di bawah, atau tambahkan " +
                    "secret ANTHROPIC_API_KEY di GitHub lalu build ulang."
                textSize = 12f
                setTextColor(Color.parseColor("#DC2626"))
            })
            val k = EditText(this).apply {
                hint = "API key Anthropic"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setText(Prefs.manualKey(this@MainActivity))
            }
            keyIn = k
            col.addView(k)
        }
        modelIn = EditText(this).apply {
            hint = "Model"
            setText(Prefs.model(this@MainActivity))
        }
        col.addView(modelIn)

        col.addView(Button(this).apply {
            text = "Mulai bubble"
            isAllCaps = false
            setOnClickListener { startFlow() }
        })
        col.addView(Button(this).apply {
            text = "Hentikan"
            isAllCaps = false
            setOnClickListener {
                startService(
                    Intent(this@MainActivity, FloatService::class.java)
                        .setAction(FloatService.ACTION_STOP)
                )
                status.text = "Dihentikan."
            }
        })

        col.addView(Button(this).apply {
            text = "Aktifkan Auto Clicker (aksesibilitas)"
            isAllCaps = false
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })
        col.addView(TextView(this).apply {
            text = "Auto Clicker memakai layanan aksesibilitas hanya untuk mengetuk satu titik " +
                "yang Anda tentukan. Layanan ini tidak membaca isi layar dan bisa dimatikan kapan saja " +
                "di Pengaturan > Aksesibilitas."
            textSize = 12f
            setTextColor(Color.GRAY)
        })

        status = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(12), 0, 0)
        }
        col.addView(status)

        col.addView(TextView(this).apply {
            text = "Privasi: layar hanya diambil saat Anda menekan tombol Scan, " +
                "lalu dikirim ke API AI. Notifikasi aktif selalu terlihat selama bubble berjalan."
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, dp(16), 0, 0)
        })

        setContentView(
            ScrollView(this).apply {
                addView(col, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        )

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), reqNotif)
        }
    }

    private fun startFlow() {
        Prefs.save(this, keyIn?.text?.toString(), modelIn.text.toString())

        if (!Settings.canDrawOverlays(this)) {
            status.text = "Aktifkan izin 'tampil di atas aplikasi lain', lalu tekan Mulai lagi."
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                reqOverlay
            )
            return
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), reqProjection)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqProjection) {
            if (resultCode == RESULT_OK && data != null) {
                val i = Intent(this, FloatService::class.java)
                    .putExtra(FloatService.EXTRA_CODE, resultCode)
                    .putExtra(FloatService.EXTRA_DATA, data)
                startForegroundService(i)
                status.text = "Bubble aktif."
                moveTaskToBack(true)
            } else {
                status.text = "Izin tangkap layar ditolak. Bubble tidak dimulai."
            }
        }
    }
}
