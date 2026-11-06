package com.example.floatstudy

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/** Layar pengaturan. Semua pengaturan bubble, menu, jendela, AI, dan API key ada di sini. */
class MainActivity : Activity() {

    private val reqOverlay = 1
    private val reqProjection = 2
    private val reqNotif = 3

    private lateinit var p: Pal
    private lateinit var status: TextView
    private val refreshers = mutableListOf<() -> Unit>()

    private fun dp(v: Float) = Ui.dp(this, v)
    private fun dp(v: Int) = Ui.dp(this, v.toFloat())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Ui.pal(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(24))
        }

        col.addView(TextView(this).apply {
            text = "flooww"
            textSize = 26f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(p.on)
        })
        col.addView(TextView(this).apply {
            text = "Bubble mengambang untuk belajar. Ketuk bubble, pilih alat, lalu pakai."
            textSize = 14f
            setTextColor(p.muted)
            setPadding(0, dp(2), 0, dp(14))
        })

        // ---- bubble ----
        status = TextView(this).apply {
            textSize = 13f
            setTextColor(p.muted)
            setPadding(0, dp(10), 0, 0)
        }
        col.addView(card("Layanan bubble",
            row(
                Ui.button(this, "Mulai bubble", true) { startFlow() },
                Ui.button(this, "Hentikan", false) {
                    startService(Intent(this, FloatService::class.java).setAction(FloatService.ACTION_STOP))
                    status.text = "Dihentikan."
                }
            ),
            status
        ))

        // ---- AI untuk Tanya AI ----
        val promptIn = Ui.field(this, "Pertanyaan yang dikirim bersama gambar", true).apply {
            setText(Prefs.prompt(this@MainActivity))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    Prefs.setPrompt(this@MainActivity, s?.toString().orEmpty())
                }
            })
        }
        val langBtn = Ui.button(this, "", false) {}
        fun showLang() { langBtn.text = "Terjemahkan ke: ${Prefs.LANGS[Prefs.lang(this)]}" }
        langBtn.setOnClickListener {
            Prefs.setLang(this, (Prefs.lang(this) + 1) % Prefs.LANGS.size)
            showLang()
        }
        showLang()
        col.addView(card("AI untuk Tanya AI",
            note("Gambar yang Anda lingkari dibuka di browser mengambang pada website AI ini (bukan aplikasi AI yang terpasang). Tekan tombol lampirkan di situs, gambar soal otomatis terpilih. Login di situs sekali, sesinya tersimpan. Tidak memakai API key."),
            segmented(Prefs.AI_NAMES, { Prefs.aiTarget(this) }) { Prefs.setAiTarget(this, it) },
            spaced(promptIn)
        ))
        col.addView(card("Terjemah",
            note("Teks pada area yang dilingkari dibaca dan diterjemahkan langsung di HP, tanpa website dan tanpa API key. Model bahasa diunduh sekali (sekitar 30 MB per bahasa), setelah itu bisa offline. Hanya huruf Latin yang terbaca."),
            spaced(langBtn)
        ))

        // ---- API key ----
        val apiViews = mutableListOf<View>()
        apiViews.add(note("Dipakai hanya oleh Chat dan Auto Jawab. Buat key gratis di Google AI Studio (aistudio.google.com/apikey), lalu tempel di sini."))
        if (Prefs.hasBuiltInKey()) {
            apiViews.add(TextView(this).apply {
                text = "Key bawaan sudah tertanam di aplikasi ini. Isian di bawah menggantikannya bila diisi."
                textSize = 12f
                setTextColor(0xFF16A34A.toInt())
                setPadding(0, dp(4), 0, 0)
            })
        }
        val keyIn = Ui.field(this, "API key Gemini").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(Prefs.manualKey(this@MainActivity))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    Prefs.setKey(this@MainActivity, s?.toString().orEmpty())
                }
            })
        }
        val modelIn = Ui.field(this, "Model (bawaan ${Prefs.DEFAULT_MODEL})").apply {
            setText(Prefs.model(this@MainActivity))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    Prefs.setModel(this@MainActivity, s?.toString().orEmpty())
                }
            })
        }
        apiViews.add(spaced(keyIn))
        apiViews.add(spaced(modelIn))
        apiViews.add(spaced(Ui.button(this, "Buka Google AI Studio", false) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
        }))
        col.addView(card("API key Gemini", *apiViews.toTypedArray()))

        // ---- tampilan ----
        col.addView(card("Tampilan bubble",
            slider("Ukuran", Setting.BUBBLE_SIZE, " dp"),
            slider("Opasitas", Setting.BUBBLE_ALPHA, "%"),
            label("Warna (juga dipakai menu)"),
            swatches()
        ))
        col.addView(card("Menu",
            slider("Ukuran", Setting.MENU_SIZE, "%"),
            slider("Opasitas", Setting.MENU_ALPHA, "%")
        ))
        col.addView(card("Keyboard di jendela mengambang",
            note("Diblokir: jendela mengambang tidak bisa menerima ketikan, keyboard tidak akan muncul. Gunakan tombol tempel di header jendela untuk menempel teks hasil salin dari gambar. Diizinkan: kolom di jendela bisa diketik, tetapi jendela mengambil fokus sehingga keyboard bisa muncul di atas aplikasi lain. Layar pengaturan ini selalu bisa memakai keyboard."),
            segmented(listOf("Diblokir", "Diizinkan"), { if (Prefs.kbBlock(this)) 0 else 1 }) { Prefs.setKbBlock(this, it == 0) }
        ))
        col.addView(card("Jendela bebas (untuk menu Apps)",
            note("Aplikasi hanya bisa terbuka sebagai jendela mengambang bila HP mengaktifkan mode jendela bebas. Tanpa itu, aplikasi terbuka layar penuh."),
            freeInfo,
            spaced(Ui.button(this, "Aktifkan otomatis", true) { enableFreeform() }),
            spaced(Ui.button(this, "Buka Opsi pengembang", false) {
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                } catch (e: Exception) {
                    android.widget.Toast.makeText(this, "Buka Pengaturan > Opsi pengembang manual", android.widget.Toast.LENGTH_LONG).show()
                }
            }),
            note("Beberapa merek (Xiaomi, Samsung, dll.) memblokir jendela bebas untuk aplikasi pihak ketiga walau opsinya sudah aktif.")
        ))
        col.addView(card("Aplikasi di menu Apps",
            note("Pilih aplikasi yang bisa dibuka sebagai jendela mengambang dari menu Apps."),
            appsInfo,
            spaced(Ui.button(this, "Pilih aplikasi", true) { pickApps() })
        ))
        col.addView(card("Gestur bubble",
            note("Atur apa yang terjadi saat bubble diketuk sekali, dua kali, atau ditekan lama. Saat Auto Jawab atau auto clicker menyala, gestur apa pun mematikannya."),
            gestureButton("Ketuk sekali", 0),
            gestureButton("Ketuk dua kali", 1),
            gestureButton("Tekan lama", 2),
            slider("Lama tekan", Setting.LONG_MS, " ms"),
            note("Bila ketuk dua kali dipakai, ketuk sekali jadi tertunda sekitar 0,3 detik untuk menunggu ketukan kedua.")
        ))
        col.addView(card("Jendela alat",
            note("Jendela bisa ditarik dari tepi mana pun: tepi kanan atau kiri melebarkan, tepi atas atau bawah memanjangkan, pojok mengubah keduanya."),
            slider("Lebar", Setting.WIN_W, " dp"),
            slider("Tinggi", Setting.WIN_H, " dp"),
            slider("Opasitas", Setting.WIN_ALPHA, "%"),
            slider("Zoom halaman web", Setting.WEB_ZOOM, "%")
        ))
        col.addView(card("Jendela aplikasi (menu Apps)",
            slider("Lebar", Setting.APP_W, " dp"),
            slider("Tinggi", Setting.APP_H, " dp"),
            spaced(Ui.button(this, "Kembalikan tampilan bawaan", false) {
                Prefs.resetLook(this)
                refreshers.forEach { it() }
            })
        ))

        // ---- auto clicker ----
        col.addView(card("Auto Clicker",
            note("Memakai layanan aksesibilitas hanya untuk mengetuk satu titik yang Anda tentukan. Tidak membaca isi layar. Bisa dimatikan kapan saja di Pengaturan > Aksesibilitas."),
            spaced(Ui.button(this, "Aktifkan di pengaturan aksesibilitas", false) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
        ))

        col.addView(TextView(this).apply {
            text = "Privasi: layar hanya diambil saat Anda melingkari area atau menyalakan Auto Jawab, " +
                "lalu dikirim ke AI yang Anda pilih. Notifikasi aktif selalu terlihat selama bubble berjalan. " +
                "Hormati aturan sekolah, kampus, dan ujian saat memakai aplikasi ini."
            textSize = 12f
            setTextColor(p.muted)
            setPadding(dp(4), dp(8), dp(4), 0)
        })

        showAppsInfo()
        showFreeInfo()
        window.decorView.setBackgroundColor(p.bg)
        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(p.bg)
                addView(col, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        )

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), reqNotif)
        }
    }

    // ---------- komponen ----------

    private fun card(title: String, vararg kids: View): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.round(p.surface, dp(16), p.outline, dp(1))
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        c.addView(TextView(this).apply {
            text = title
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(p.on)
            setPadding(0, 0, 0, dp(6))
        })
        kids.forEach { c.addView(it) }
        c.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
        return c
    }

    private val freeInfo by lazy {
        TextView(this).apply {
            textSize = 13f
            setTextColor(p.on)
            setPadding(0, dp(8), 0, 0)
        }
    }

    private fun globalOn(k: String) = try {
        Settings.Global.getInt(contentResolver, k, 0) == 1
    } catch (e: Exception) {
        false
    }

    private fun showFreeInfo() {
        val ff = packageManager.hasSystemFeature("android.software.freeform_window_management") ||
            globalOn("enable_freeform_support")
        val rs = globalOn("force_resizable_activities")
        freeInfo.text = "Jendela bentuk bebas: " + (if (ff) "aktif" else "BELUM aktif") +
            "\nPaksa aktivitas dapat diubah ukuran: " + (if (rs) "aktif" else "BELUM aktif") +
            "\nSetelah mengaktifkan, restart HP."
    }

    /** Menulis setelan sistem sendiri. Butuh izin WRITE_SECURE_SETTINGS yang diberikan sekali lewat ADB. */
    private fun enableFreeform() {
        try {
            Settings.Global.putInt(contentResolver, "enable_freeform_support", 1)
            Settings.Global.putInt(contentResolver, "force_resizable_activities", 1)
            showFreeInfo()
            android.app.AlertDialog.Builder(this)
                .setTitle("Berhasil diaktifkan")
                .setMessage("Restart HP supaya berlaku, lalu coba menu Apps lagi.")
                .setPositiveButton("OK", null)
                .show()
        } catch (e: SecurityException) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Butuh izin sekali lewat ADB")
                .setMessage(
                    "Sambungkan HP ke komputer (Debugging USB aktif), lalu jalankan:\n\n" +
                        "adb shell pm grant com.example.floatstudy android.permission.WRITE_SECURE_SETTINGS\n\n" +
                        "Setelah itu tekan Aktifkan otomatis lagi. Atau langsung lewat ADB:\n\n" +
                        "adb shell settings put global enable_freeform_support 1\n" +
                        "adb shell settings put global force_resizable_activities 1\n\n" +
                        "lalu restart HP."
                )
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private val appsInfo by lazy {
        TextView(this).apply {
            textSize = 13f
            setTextColor(p.on)
            setPadding(0, dp(8), 0, 0)
        }
    }

    private fun showAppsInfo() {
        val n = Prefs.pinned(this).size
        appsInfo.text = if (n == 0) "Belum ada aplikasi dipilih." else "$n aplikasi dipilih."
    }

    /** Daftar aplikasi terpasang dengan kotak centang. Pilihan disimpan saat menekan Simpan. */
    private fun pickApps() {
        Thread {
            val pm = packageManager
            val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = try {
                pm.queryIntentActivities(q, 0)
                    .filter { it.activityInfo.packageName != packageName }
                    .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                    .distinctBy { it.first }
                    .sortedBy { it.second.lowercase() }
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (list.isEmpty()) {
                    android.widget.Toast.makeText(this, "Daftar aplikasi tidak bisa dibaca", android.widget.Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val chosen = Prefs.pinned(this).toMutableSet()
                val labels = list.map { it.second }.toTypedArray()
                val checked = BooleanArray(list.size) { list[it].first in chosen }
                android.app.AlertDialog.Builder(this)
                    .setTitle("Aplikasi di menu Apps")
                    .setMultiChoiceItems(labels, checked) { _, i, on ->
                        if (on) chosen.add(list[i].first) else chosen.remove(list[i].first)
                    }
                    .setPositiveButton("Simpan") { _, _ ->
                        // urutan: yang sudah ada tetap, yang baru di belakang
                        val old = Prefs.pinned(this).filter { it in chosen }
                        Prefs.setPinned(this, old + (chosen - old.toSet()))
                        showAppsInfo()
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            }
        }.start()
    }

    private fun gestureButton(title: String, g: Int): View {
        val btn = Ui.button(this, "", false) {}
        fun show() {
            btn.text = "$title: ${Prefs.GESTURE_NAMES[Prefs.gesture(this, g)]}"
        }
        btn.setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(Prefs.GESTURE_NAMES.toTypedArray()) { _, i ->
                    Prefs.setGesture(this, g, i)
                    show()
                }
                .show()
        }
        show()
        return spaced(btn)
    }

    private fun note(t: String) = TextView(this).apply {
        text = t
        textSize = 12.5f
        setTextColor(p.muted)
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(p.on)
        setPadding(0, dp(8), 0, 0)
    }

    private fun spaced(v: View): View {
        v.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
        return v
    }

    private fun row(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        v.forEachIndexed { i, x ->
            addView(x, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) leftMargin = dp(8)
            })
        }
    }

    private fun segmented(names: List<String>, get: () -> Int, set: (Int) -> Unit): View {
        val accent = Prefs.bubbleColor(this)
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.round(p.field, dp(12))
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val cells = names.map { TextView(this) }
        fun paint() {
            cells.forEachIndexed { i, t ->
                val on = i == get()
                t.setTextColor(if (on) Color.WHITE else p.on)
                t.background = if (on) Ui.round(accent, dp(10)) else null
            }
        }
        cells.forEachIndexed { i, t ->
            t.text = names[i]
            t.gravity = Gravity.CENTER
            t.textSize = 14f
            t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            t.setPadding(dp(8), dp(9), dp(8), dp(9))
            t.setOnClickListener { set(i); paint() }
            line.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        paint()
        return spaced(line)
    }

    private fun slider(name: String, s: Setting, suffix: String): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tv = TextView(this).apply {
            textSize = 13f
            setTextColor(p.on)
            setPadding(0, dp(8), 0, 0)
        }
        val bar = SeekBar(this).apply { max = s.max - s.min }
        fun show() {
            val v = Prefs.get(this, s)
            bar.progress = v - s.min
            tv.text = "$name: $v$suffix"
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar, pr: Int, fromUser: Boolean) {
                if (!fromUser) return
                Prefs.set(this@MainActivity, s, pr + s.min)
                tv.text = "$name: ${pr + s.min}$suffix"
            }

            override fun onStartTrackingTouch(b: SeekBar) {}
            override fun onStopTrackingTouch(b: SeekBar) {}
        })
        refreshers += { show() }
        show()
        box.addView(tv)
        box.addView(bar)
        return box
    }

    private fun swatches(): View {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(2))
        }
        Prefs.COLORS.forEach { color ->
            line.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(dp(1), p.outline)
                }
                setOnClickListener { Prefs.setBubbleColor(this@MainActivity, color) }
            }, LinearLayout.LayoutParams(0, dp(30), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        return line
    }

    // ---------- mulai ----------

    private fun startFlow() {
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
