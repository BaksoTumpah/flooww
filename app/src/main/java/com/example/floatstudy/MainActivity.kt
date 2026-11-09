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
import android.os.Handler
import android.os.Looper
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

    private companion object {
        /** Sudah menekan "Lanjut ke flooww" pada halaman izin (berlaku selama proses aplikasi hidup). */
        @Volatile var permSkipped = false
    }

    private val reqOverlay = 1
    private val reqProjection = 2
    private val reqNotif = 3
    private val reqMedia = 4

    private lateinit var p: Pal
    private lateinit var status: TextView
    private val refreshers = mutableListOf<() -> Unit>()

    private fun dp(v: Float) = Ui.dp(this, v)
    private fun dp(v: Int) = Ui.dp(this, v.toFloat())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Ui.pal(this)
        // trial habis, atau lisensi tersimpan tidak sah: bubble dimatikan dan semua fitur terkunci sampai lisensi sah
        if (Trial.mustLock(this)) {
            // layanan bubble mengunci dirinya sendiri (bubble mati, popup tampil); popup tidak boleh ikut dimatikan di sini
            lockedScreen = true
            showLicense(
                if (License.state(this) == License.State.NONE) "Masa trial 10 menit habis. Bubble dimatikan dan semua fitur nonaktif. Kirim kode perangkat ke admin, lalu tempel lisensi."
                else null
            )
            return
        }

        // izin wajib belum lengkap: tampilkan daftar izin dulu (tanpa melompat ke pengaturan sistem)
        if (!permSkipped && buildSteps().any { !it.optional && !it.ok() }) {
            showPermPage()
            return
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(24))
        }

        col.addView(TextView(this).apply {
            text = "flooww"
            textSize = 28f
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            setTextColor(p.on)
        })
        // sapaan: nama diisi dari data pembeli (aplikasi admin); kosong = sapaan umum. Di sampingnya status trial/lisensi.
        chip = statusChip()
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                val n = Prefs.userName(this@MainActivity)
                text = if (n.isBlank()) "Halo!" else "Halo, $n"
                textSize = 18f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Ui.accentText(p, Prefs.bubbleColor(this@MainActivity)))
                setPadding(0, dp(2), 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        refreshChip()
        // setelah aplikasi dibuka ulang, baca sisa trial dari server (tanpa memulai trial baru)
        if (License.state(this) == License.State.NONE && Trial.remaining() == null) {
            Thread { if (Trial.peek(this) != null) runOnUiThread { if (!isFinishing && !isDestroyed) refreshChip() } }.start()
        }
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

        col.addView(permCard())

        val gAi = Group("Tanya AI dan terjemah", "Tujuan Tanya AI, pertanyaan", true)
        val gLook = Group("Tampilan", "Bubble, menu, jendela", false)
        val gCtl = Group("Kontrol", "Gestur bubble, keyboard", false)
        val gApps = Group("Aplikasi", "Menu Apps dan jendela bebas", false)
        val gMore = Group("Lainnya", "Auto Clicker", false)
        listOf(gAi, gLook, gCtl, gApps, gMore).forEach { col.addView(it.root) }

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
        gAi.add(sec("Tujuan Tanya AI",
            note("Direkomendasikan: ChatGPT (bawaan). Gambar soal paling stabil terlampir dan pengirimannya cepat. Claude lebih lambat dimuat dan Anda harus login dulu di jendela mengambang (buka Tanya AI, login sekali, lalu ulangi)."),
            segmented(Prefs.AI_NAMES, { Prefs.aiTarget(this) }) { Prefs.setAiTarget(this, it) },
            spaced(promptIn)
        ))

        // ---- tampilan ----
        gLook.add(sec("Bubble",
            label("Bentuk"),
            segmented(Prefs.SHAPE_NAMES, { Prefs.shape(this) }) { Prefs.setShape(this, it) },
            slider("Ukuran", Setting.BUBBLE_SIZE, " dp"),
            slider("Opasitas", Setting.BUBBLE_ALPHA, "%"),
            label("Warna (juga dipakai menu)"),
            swatches()
        ))
        gLook.add(sec("Menu",
            slider("Ukuran", Setting.MENU_SIZE, "%"),
            slider("Opasitas", Setting.MENU_ALPHA, "%")
        ))
        gApps.add(sec("Pilih aplikasi",
            appsInfo,
            spaced(Ui.button(this, "Pilih aplikasi", true) { pickApps() })
        ))
        gApps.add(sec("Jendela bebas",
            freeInfo,
            spaced(Ui.button(this, "Aktifkan otomatis", true) { enableFreeform() }),
            spaced(Ui.button(this, "Buka Opsi pengembang", false) {
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                } catch (e: Exception) {
                    android.widget.Toast.makeText(this, "Buka Pengaturan > Opsi pengembang manual", android.widget.Toast.LENGTH_LONG).show()
                }
            })
        ))
        gCtl.add(sec("Gestur bubble",
            gestureButton("Ketuk sekali", 0),
            gestureButton("Ketuk dua kali", 1),
            gestureButton("Tekan lama", 2),
            gestureButton("Sapu ke atas", 3),
            gestureButton("Sapu ke bawah", 4),
            gestureButton("Sapu ke arah tengah layar", 5),
            slider("Lama tekan", Setting.LONG_MS, " ms"),
        ))
        gCtl.add(sec("Keyboard di jendela mengambang",
            note("Direkomendasikan: Keyboard flooww (bawaan). Muncul di dasar layar dan tidak mengambil fokus aplikasi di bawahnya, jadi aman dipakai saat ujian. Keyboard HP memakai keyboard bawaan, tapi fokus berpindah ke jendela."),
            segmented(listOf("Keyboard flooww", "Keyboard HP"), { if (Prefs.kbBlock(this)) 0 else 1 }) { Prefs.setKbBlock(this, it == 0) },
            slider("Tinggi tombol keyboard flooww", Setting.OSK_H, "%"),
            slider("Opasitas keyboard flooww", Setting.OSK_ALPHA, "%")
        ))
        gLook.add(sec("Jendela alat",
            slider("Zoom halaman web", Setting.WEB_ZOOM, "%")
        ))
        gLook.add(sec("Bawaan",
            spaced(Ui.button(this, "Kembalikan tampilan bawaan", false) {
                Prefs.resetLook(this)
                refreshers.forEach { it() }
            })
        ))

        // ---- auto clicker ----
        gMore.add(sec("Auto Clicker",
            spaced(Ui.button(this, "Aktifkan di pengaturan aksesibilitas", false) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
        ))

        col.addView(TextView(this).apply {
            text = "Layar hanya diambil saat Anda memakai fitur baca layar."
            textSize = 12f
            setTextColor(p.muted)
            setPadding(dp(4), dp(8), dp(4), 0)
        })

        showAppsInfo()
        showFreeInfo()
        val upMsg = TextView(this).apply {
            text = "Versi ${BuildConfig.VERSION_NAME}"
            textSize = 13f
            setTextColor(p.muted)
        }
        col.addView(card("Pembaruan",
            upMsg,
            TextView(this).apply {
                text = "Cara memperbarui:\n" +
                    "1. Ketuk Cek pembaruan, atau tunggu dialog Pembaruan tersedia saat aplikasi dibuka.\n" +
                    "2. Ketuk Unduh dan pasang, tunggu unduhan selesai. Bila diminta, izinkan pasang dari sumber ini (sekali saja).\n" +
                    "3. Ketuk Pasang di layar Android, lalu buka flooww lagi."
                textSize = 12.5f
                setTextColor(p.muted)
                setPadding(0, dp(8), 0, dp(8))
            },
            Ui.button(this, "Cek pembaruan", true) { checkUpdate(upMsg) }
        ))
        // seperti Magisk: otomatis memeriksa rilis terbaru di GitHub setiap aplikasi dibuka
        checkUpdate(upMsg, auto = true)
        if (License.info(this) == null) {
            col.addView(card("Lisensi",
                TextView(this).apply {
                    text = "Mode trial: 10 menit sejak bubble dihidupkan (butuh internet, tidak bisa diulang). " +
                        "Setelah itu fitur terkunci sampai lisensi dimasukkan.\nKode perangkat ${License.deviceCode(this@MainActivity)}"
                    textSize = 13f
                    setTextColor(p.muted)
                },
                Ui.button(this, "Masukkan lisensi", true) { showLicense("Kirim kode perangkat ke admin, lalu tempel lisensi.", true) }
            ))
        }
        License.info(this)?.let { li ->
            col.addView(card("Lisensi",
                TextView(this).apply {
                    text = "Aktif untuk ${li.name.ifBlank { "pengguna" }}" +
                        (if (li.school.isNotBlank()) "\n${li.school}" + (if (li.cls.isNotBlank()) ", ${li.cls}" else "") else "") +
                        "\nID lisensi ${li.id}\nKode perangkat ${License.deviceCode(this@MainActivity)}"
                    textSize = 13f
                    setTextColor(p.muted)
                },
                Ui.button(this, "Ganti lisensi", false) {
                    Prefs.sp(this).edit().remove("license").apply()
                    startService(Intent(this, FloatService::class.java).setAction(FloatService.ACTION_STOP))
                    recreate()
                }
            ))
        }
        window.decorView.setBackgroundColor(p.bg)
        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(p.bg)
                addView(col, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        )
    }

    // ---------- status trial dan lisensi ----------

    private var chip: TextView? = null
    private var lockedScreen = false
    private val tickH = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            // trial habis saat layar ini terbuka: langsung ke halaman terkunci
            if (!lockedScreen && Trial.mustLock(this@MainActivity)) {
                recreate()
                return
            }
            val rem = Trial.remaining()
            if (!lockedScreen && License.state(this@MainActivity) == License.State.NONE && rem != null && rem <= 0L) {
                Trial.markExpired(this@MainActivity)
                recreate()
                return
            }
            refreshChip()
            tickH.postDelayed(this, 1000)
        }
    }

    private fun statusChip() = TextView(this).apply {
        textSize = 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(10), dp(4), dp(10), dp(4))
        setTextColor(Color.WHITE)
    }

    /** Teks dan warna status: Trial mm:ss, Trial habis, Verified, Expired. */
    private fun refreshChip() {
        val c = chip ?: return
        val green = 0xFF2EB67D.toInt()
        val red = 0xFFE5484D.toInt()
        val amber = 0xFFF5A524.toInt()
        val (t, bg) = when (License.state(this)) {
            License.State.OK -> "Verified" to green
            License.State.EXPIRED -> "Expired" to red
            License.State.NONE -> {
                val rem = Trial.remaining()
                when {
                    rem != null && rem > 0L -> {
                        val sec = (rem + 999L) / 1000L
                        "Trial %02d:%02d".format(sec / 60, sec % 60) to (if (sec <= 60) red else amber)
                    }
                    (rem != null) || Trial.expiredHint(this) -> "Trial habis" to red
                    else -> "Trial 10:00" to amber
                }
            }
            else -> "Tidak valid" to red
        }
        c.text = t
        c.setTextColor(if (bg == amber) 0xFF2A1A00.toInt() else Color.WHITE)
        c.background = Ui.round(bg, dp(14))
    }

    /**
     * Cek pembaruan dari rilis terbaru GitHub (otomatis, tanpa alamat yang diisi manual).
     * auto = true: dijalankan sendiri saat aplikasi dibuka; hasil diam bila tidak ada pembaruan atau gagal,
     * dan dialog tidak muncul lagi untuk versi yang sudah dijawab "Nanti".
     */
    private fun checkUpdate(msg: TextView, auto: Boolean = false) {
        if (!auto) msg.text = "Memeriksa pembaruan..."
        Thread {
            try {
                val info = Updater.fetch()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val li = License.info(this)
                    val skipped = Prefs.sp(this).getInt("upd_skip", 0)
                    when {
                        info.version <= BuildConfig.VERSION_CODE -> msg.text = "Versi ${BuildConfig.VERSION_NAME}. Sudah yang terbaru."
                        li == null -> msg.text = "Versi ${info.version} tersedia. Masukkan lisensi dulu untuk memperbarui."
                        li.maxVersion in 1 until info.version ->
                            msg.text = "Versi ${info.version} tersedia, tetapi di luar jatah update lisensi (sampai versi ${li.maxVersion}). Hubungi admin."
                        else -> {
                            msg.text = "Versi ${info.version} tersedia (terpasang ${BuildConfig.VERSION_NAME})."
                            if (!(auto && skipped == info.version)) {
                                android.app.AlertDialog.Builder(this)
                                    .setTitle("Pembaruan tersedia: versi ${info.version}")
                                    .setMessage(info.notes.ifBlank { "Pembaruan baru." })
                                    .setNegativeButton("Nanti") { _, _ ->
                                        Prefs.sp(this).edit().putInt("upd_skip", info.version).apply()
                                    }
                                    .setPositiveButton("Unduh dan pasang") { _, _ -> downloadUpdate(info, msg) }
                                    .show()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (!auto) runOnUiThread { msg.text = "Gagal memeriksa: ${e.message ?: e.javaClass.simpleName}" }
            }
        }.start()
    }

    private fun downloadUpdate(info: Updater.Info, msg: TextView) {
        msg.text = "Mengunduh versi ${info.version}... 0%"
        Thread {
            try {
                val f = Updater.download(this, info) { pct -> runOnUiThread { msg.text = "Mengunduh versi ${info.version}... $pct%" } }
                runOnUiThread {
                    msg.text = if (Updater.install(this, f)) "Unduhan selesai. Ikuti pemasangan." else "Izinkan flooww memasang aplikasi, lalu tekan Cek pembaruan lagi."
                }
            } catch (e: Exception) {
                runOnUiThread { msg.text = "Gagal mengunduh: ${e.message ?: e.javaClass.simpleName}" }
            }
        }.start()
    }

    // ---------- izin ----------
    // Aplikasi tidak lagi melompat ke pengaturan sistem saat dibuka. Daftar izin tampil di halaman sendiri
    // (atau kartu "Izin" di layar utama), tiap izin punya penjelasan singkat dan tombol menuju pengaturannya.

    private class Step(
        val name: String,
        val desc: String,
        val ok: () -> Boolean,
        val ask: () -> Unit,
        val optional: Boolean = false,
        val action: String = "Buka pengaturan"
    )

    private val asked = mutableSetOf<String>()
    private var chaining = false
    private var onPermPage = false
    private val permMarks = mutableListOf<TextView>()
    private val permBtns = mutableListOf<TextView>()
    private var permSteps: List<Step> = emptyList()

    private fun a11yOn(): Boolean {
        if (ClickService.instance != null) return true
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return s.contains(packageName)
    }

    private fun batteryOk(): Boolean =
        getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun xiaomi() = Build.MANUFACTURER.lowercase().let { it.contains("xiaomi") || it.contains("redmi") || it.contains("poco") }

    private fun buildSteps(): List<Step> {
        val l = mutableListOf<Step>()
        l += Step("Tampil di atas aplikasi lain",
            "Supaya bubble dan jendela flooww bisa muncul di atas aplikasi lain. Tanpa ini bubble tidak tampil.",
            { Settings.canDrawOverlays(this) }, {
                startActivityForResult(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), reqOverlay
                )
            })
        if (Build.VERSION.SDK_INT >= 33) {
            l += Step("Notifikasi",
                "Android mewajibkan notifikasi tetap selama bubble aktif. Dipakai juga untuk menghentikan bubble dari notifikasi.",
                { checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED },
                { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), reqNotif) },
                action = "Beri izin")
        }
        l += Step("Akses foto (Galeri mengambang)",
            "Untuk membuka Galeri mengambang. Foto hanya dibaca saat Anda membukanya, tidak diunggah.",
            {
                if (Build.VERSION.SDK_INT >= 33) {
                    checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                        (Build.VERSION.SDK_INT >= 34 &&
                            checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == PackageManager.PERMISSION_GRANTED)
                } else {
                    checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                }
            }, {
                requestPermissions(
                    if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
                    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    reqMedia
                )
            }, true, "Beri izin")
        l += Step("Pasang aplikasi dari sumber ini",
            "Agar pembaruan flooww bisa diunduh lalu dipasang dari dalam aplikasi. Hanya perlu bila Anda memperbarui lewat flooww.",
            { packageManager.canRequestPackageInstalls() }, {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                )
            }, true)
        l += Step("Tanpa batasan baterai",
            "Agar sistem tidak mematikan bubble saat dipakai lama atau layar dikunci.",
            { batteryOk() }, {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    )
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            })
        if (xiaomi()) {
            l += Step("Mulai otomatis (Xiaomi)",
                "Di Xiaomi, HyperOS dan MIUI sering menutup aplikasi latar. Izinkan flooww mulai otomatis agar bubble tetap hidup.",
                { Prefs.sp(this).getBoolean("autostartDone", false) }, {
                    Prefs.sp(this).edit().putBoolean("autostartDone", true).apply()
                    try {
                        startActivity(Intent().setClassName(
                            "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"
                        ))
                    } catch (e: Exception) {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    }
                })
        }
        l += Step("Aksesibilitas (Auto Clicker)",
            "Hanya untuk Auto Clicker mengetuk titik target. Tidak membaca layar atau isi aplikasi. Boleh dilewati bila tidak memakai Auto Clicker.",
            { a11yOn() }, {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }, true)
        return l
    }

    /** Daftar izin: nama dan status, penjelasan singkat, dan tombol menuju pengaturannya. */
    private fun permList(): LinearLayout {
        permSteps = buildSteps()
        permMarks.clear()
        permBtns.clear()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        permSteps.forEach { s ->
            val mark = TextView(this).apply {
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            val btn = Ui.button(this, s.action, false) {
                try {
                    s.ask()
                } catch (e: Exception) {
                }
            }
            permMarks += mark
            permBtns += btn
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                addView(mark)
                addView(TextView(this@MainActivity).apply {
                    text = s.desc
                    textSize = 13f
                    setTextColor(p.muted)
                    setPadding(0, dp(2), 0, dp(6))
                })
                addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            })
        }
        refreshPerm()
        return box
    }

    private var permSummary: TextView? = null

    /** Izin di layar utama: terlipat supaya tidak memakan tempat; ringkasannya menunjukkan jumlah izin aktif. */
    private fun permCard(): View {
        val g = Group("Izin", "", false)
        permSummary = g.sub
        g.body.addView(permList())
        g.body.addView(TextView(this).apply {
            text = "Izin tangkap layar diminta Android setiap bubble dimulai. Itu normal dan tidak bisa dimatikan."
            textSize = 12f
            setTextColor(p.muted)
            setPadding(0, dp(6), 0, dp(6))
        })
        g.body.addView(spaced(Ui.button(this, "Beri semua izin satu per satu", true) {
            asked.clear()
            chaining = true
            nextPermission()
        }))
        refreshers.add { refreshPerm() }
        refreshPerm()
        return g.root
    }

    private fun refreshPerm() {
        permSteps.forEachIndexed { i, s ->
            val ok = s.ok()
            permMarks[i].text = (if (ok) "✓  " else "✕  ") + s.name + (if (s.optional) " (opsional)" else "")
            permMarks[i].setTextColor(if (ok) 0xFF16A34A.toInt() else p.on)
            permBtns[i].visibility = if (ok) View.GONE else View.VISIBLE
        }
        val on = permSteps.count { it.ok() }
        val needed = permSteps.count { !it.optional && !it.ok() }
        permSummary?.text = "$on dari ${permSteps.size} aktif" + if (needed > 0) " · $needed wajib belum diberikan" else ""
    }

    /** Dipakai tombol "Beri semua izin satu per satu": minta izin yang belum ada, satu per satu, tiap izin sekali. */
    private fun nextPermission() {
        refreshPerm()
        val s = permSteps.firstOrNull { !it.ok() && it.name !in asked }
        if (s == null) {
            chaining = false
            return
        }
        asked.add(s.name)
        try {
            s.ask()
        } catch (e: Exception) {
        }
    }

    /** Halaman daftar izin, tampil saat aplikasi dibuka bila ada izin wajib yang belum diberikan. */
    private fun showPermPage() {
        onPermPage = true
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(24))
        }
        col.addView(TextView(this).apply {
            text = "flooww"
            textSize = 28f
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            setTextColor(p.on)
        })
        col.addView(TextView(this).apply {
            text = "Izin aplikasi"
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Ui.accentText(p, Prefs.bubbleColor(this@MainActivity)))
            setPadding(0, dp(2), 0, dp(4))
        })
        col.addView(TextView(this).apply {
            text = "flooww butuh beberapa izin agar bubble bisa berjalan. Tiap izin dijelaskan di bawah, dan tombolnya membuka pengaturan yang sesuai. Izin opsional boleh dilewati."
            textSize = 14f
            setTextColor(p.muted)
            setPadding(0, 0, 0, dp(14))
        })
        col.addView(card("Daftar izin", permList()))
        col.addView(TextView(this).apply {
            text = "Izin tangkap layar diminta Android setiap bubble dimulai. Itu normal dan tidak bisa dimatikan."
            textSize = 12f
            setTextColor(p.muted)
            setPadding(dp(4), 0, dp(4), dp(12))
        })
        col.addView(Ui.button(this, "Lanjut ke flooww", true) {
            permSkipped = true
            recreate()
        })
        window.decorView.setBackgroundColor(p.bg)
        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(p.bg)
                addView(col, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        )
    }

    /** Layar aktivasi: semua fitur terkunci sampai lisensi dari admin ditempel. */
    private fun showLicense(msgOverride: String? = null, canBack: Boolean = false) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(24))
        }
        col.addView(TextView(this).apply {
            text = "flooww"
            textSize = 28f
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            setTextColor(p.on)
        })
        chip = statusChip()
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
            addView(TextView(this@MainActivity).apply {
                text = "Aktivasi diperlukan"
                textSize = 18f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Ui.accentText(p, Prefs.bubbleColor(this@MainActivity)))
                setPadding(0, dp(2), 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        refreshChip()
        val code = License.deviceCode(this)
        val msg = TextView(this).apply {
            text = msgOverride ?: License.message(License.state(this@MainActivity))
            textSize = 13f
            setTextColor(p.muted)
            setPadding(0, dp(8), 0, dp(4))
        }
        val input = Ui.field(this, "Tempel lisensi dari admin", true)
        fun tryActivate(tok: String) {
            val err = License.activate(this, tok)
            if (err == null) recreate() else msg.text = err
        }
        col.addView(card("1. Kode perangkat",
            TextView(this).apply {
                text = code
                textSize = 24f
                typeface = Typeface.MONOSPACE
                setTextColor(p.on)
                setPadding(0, dp(4), 0, dp(8))
            },
            TextView(this).apply {
                text = "Kirim kode ini ke admin untuk mendapat lisensi."
                textSize = 13f
                setTextColor(p.muted)
                setPadding(0, 0, 0, dp(8))
            },
            row(
                Ui.button(this, "Salin kode", true) {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("kode perangkat", code))
                    msg.text = "Kode perangkat disalin."
                },
                Ui.button(this, "Bagikan", false) {
                    startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Kode perangkat flooww: $code"), "Kirim kode"
                    ))
                }
            )
        ))
        col.addView(card("2. Lisensi",
            input,
            row(
                Ui.button(this, "Tempel dari salinan", false) {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
                    if (t.isBlank()) msg.text = "Belum ada teks yang disalin." else input.setText(t)
                },
                Ui.button(this, "Aktifkan", true) { tryActivate(input.text.toString()) }
            ),
            msg
        ))
        if (canBack) col.addView(Ui.button(this, "Kembali", false) { recreate() })
        window.decorView.setBackgroundColor(p.bg)
        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(p.bg)
                addView(col, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        )
    }

    override fun onPause() {
        tickH.removeCallbacks(tick)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        tickH.removeCallbacks(tick)
        if (!lockedScreen) tickH.post(tick)
        if (permSteps.isNotEmpty()) {
            refreshPerm()
            if (chaining) window.decorView.postDelayed({ nextPermission() }, 350)
        }
    }

    // ---------- komponen ----------

    /** Kelompok pengaturan yang bisa dibuka dan ditutup. */
    private inner class Group(title: String, summary: String, open: Boolean) {
        val root = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.clay(this@MainActivity, p.surface, dp(22), 5f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        val body = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(8))
            visibility = if (open) View.VISIBLE else View.GONE
        }

        val sub = TextView(this@MainActivity).apply {
            text = summary
            textSize = 12.5f
            setTextColor(p.muted)
        }

        init {
            val chevron = TextView(this@MainActivity).apply {
                text = if (open) "▾" else "▸"
                textSize = 18f
                setTextColor(p.muted)
            }
            val head = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@MainActivity).apply {
                        text = title
                        textSize = 16f
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        setTextColor(p.on)
                    })
                    addView(sub)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(chevron)
                setOnClickListener {
                    val show = body.visibility != View.VISIBLE
                    body.visibility = if (show) View.VISIBLE else View.GONE
                    chevron.text = if (show) "▾" else "▸"
                }
            }
            root.addView(head)
            root.addView(body)
        }

        fun add(v: View) {
            if (body.childCount > 0) {
                body.addView(View(this@MainActivity).apply { setBackgroundColor(p.outline) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                        topMargin = dp(4); bottomMargin = dp(4)
                    })
            }
            body.addView(v)
        }
    }

    /** Satu bagian di dalam kelompok: judul kecil lalu isinya. */
    private fun sec(title: String, vararg kids: View): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        c.addView(TextView(this).apply {
            text = title
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(p.on)
        })
        kids.forEach { c.addView(it) }
        return c
    }

    private fun card(title: String, vararg kids: View): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.clay(this@MainActivity, p.surface, dp(22), 5f)
            setPadding(dp(18), dp(16), dp(18), dp(22))
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
            ""
    }

    /** Menulis setelan sistem sendiri. Butuh izin WRITE_SECURE_SETTINGS yang diberikan sekali lewat ADB. */
    private fun enableFreeform() {
        try {
            Settings.Global.putInt(contentResolver, "enable_freeform_support", 1)
            Settings.Global.putInt(contentResolver, "force_resizable_activities", 1)
            showFreeInfo()
            android.app.AlertDialog.Builder(this)
                .setTitle("Berhasil diaktifkan")
                .setMessage("Restart HP.")
                .setPositiveButton("OK", null)
                .show()
        } catch (e: SecurityException) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Butuh izin ADB")
                .setMessage(
                    "adb shell pm grant com.example.floatstudy android.permission.WRITE_SECURE_SETTINGS"
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
            background = Ui.dent(this@MainActivity, p.field, dp(16))
            setPadding(dp(4), dp(4), dp(4), dp(5))
        }
        val cells = names.map { TextView(this) }
        fun paint() {
            cells.forEachIndexed { i, t ->
                val on = i == get()
                t.setTextColor(if (on) Color.WHITE else p.on)
                t.background = if (on) Ui.clay(this@MainActivity, accent, dp(13), 3f) else null
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
