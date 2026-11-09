package com.example.floowwadmin

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** flow admin: menerbitkan lisensi flooww, menyimpan data pembeli, daftar rilis, dan cadangan. */
class AdminActivity : Activity() {

    private lateinit var p: Pal
    private var unlocked = false
    private var googleOk = false
    private var picking = false
    private var tab = 0
    private lateinit var content: LinearLayout
    private lateinit var tabBar: LinearLayout

    private val fmt = SimpleDateFormat("d MMM yyyy HH:mm", Locale("id"))

    private fun dp(v: Int) = Ui.dp(this, v.toFloat())
    private fun dpf(v: Float) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        p = Ui.pal(this)
        window.decorView.setBackgroundColor(p.bg)
        route()
    }

    override fun onStop() {
        super.onStop()
        if (!picking) unlocked = false
    }

    override fun onStart() {
        super.onStart()
        if (!unlocked && ::p.isInitialized && !picking) route()
        picking = false
    }

    private fun route() {
        when {
            BuildConfig.GOOGLE_CLIENT_ID.isNotEmpty() && !googleOk -> googleScreen()
            !Store.hasPin(this) -> pinScreen(setup = true)
            !unlocked -> pinScreen(setup = false)
            !Store.hasKey(this) -> keyScreen()
            else -> mainScreen()
        }
    }

    // ---------- login Google ----------

    private fun googleScreen() {
        val msg = label(Store.owner(this)?.let { "Masuk dengan akun pemilik: $it" } ?: "Akun Google pertama yang masuk menjadi pemilik aplikasi ini.", 13f)
        scaffold("flow admin", "Masuk dengan Google",
            card(null,
                msg,
                spaced(btn("Masuk dengan Google", true) {
                    msg.text = "Membuka pilihan akun..."
                    Google.signIn(this, BuildConfig.GOOGLE_CLIENT_ID) { email, err ->
                        if (email == null) { msg.text = err ?: "Gagal masuk."; return@signIn }
                        val owner = Store.owner(this)
                        if (owner == null) Store.setOwner(this, email)
                        if (owner != null && owner != email.lowercase()) { msg.text = "Akun ini bukan pemilik. Pakai $owner."; return@signIn }
                        googleOk = true
                        route()
                    }
                })
            )
        )
    }

    // ---------- komponen ----------

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f, m: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(m, m, m, m) }

    private fun vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun label(t: String, size: Float = 13f, color: Int = p.muted, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun card(title: String?, vararg kids: View): LinearLayout {
        val c = vbox().apply {
            background = Ui.clay(this@AdminActivity, p.surface, dp(22), 5f)
            setPadding(dp(16), dp(14), dp(16), dp(20))
        }
        if (title != null) c.addView(label(title, 15f, p.on, true).apply { setPadding(0, 0, 0, dp(6)) })
        kids.forEach { c.addView(it) }
        c.layoutParams = lp().apply { bottomMargin = dp(12) }
        return c
    }

    private fun row(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        v.forEachIndexed { i, x ->
            addView(x, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(if (i == 0) 0 else dp(4), dp(4), dp(4), dp(4)) })
        }
    }

    private fun btn(t: String, primary: Boolean = false, f: () -> Unit) = Ui.button(this, t, primary, f)

    private fun spaced(v: View) = v.apply { layoutParams = lp().apply { setMargins(0, dp(4), 0, dp(4)) } }

    private fun copy(label: String, text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
        toast("Disalin")
    }

    private fun share(text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Kirim"))
    }

    private fun scaffold(title: String, subtitle: String?, vararg kids: View) {
        val col = vbox().apply { setPadding(dp(16), dp(28), dp(16), dp(24)) }
        col.addView(label(title, 28f, p.on).apply { typeface = Typeface.create("sans-serif-black", Typeface.NORMAL) })
        if (subtitle != null) col.addView(label(subtitle, 14f).apply { setPadding(0, dp(2), 0, dp(14)) })
        kids.forEach { col.addView(it) }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(p.bg)
            isFillViewport = true
            addView(col, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
    }

    // ---------- PIN 6 digit ----------

    private fun pinScreen(setup: Boolean) {
        var first: String? = null
        var buf = ""
        val info = label("", 14f).apply { gravity = Gravity.CENTER }
        val dots = label("", 30f, Ui.ACCENT).apply { gravity = Gravity.CENTER; letterSpacing = 0.3f }

        fun heading() = if (!setup) "Masukkan PIN" else if (first == null) "Buat PIN 6 digit" else "Ulangi PIN"

        val title = label(heading(), 18f, p.on, true).apply { gravity = Gravity.CENTER }

        fun redraw() {
            dots.text = "●".repeat(buf.length) + "○".repeat(6 - buf.length)
            title.text = heading()
        }

        fun done() {
            val now = System.currentTimeMillis()
            if (!setup && now < Store.lockUntil) {
                info.text = "Terlalu banyak salah. Coba lagi dalam ${((Store.lockUntil - now) / 1000) + 1} detik."
                buf = ""
                redraw()
                return
            }
            if (setup) {
                if (first == null) {
                    first = buf
                    buf = ""
                    info.text = ""
                } else if (first == buf) {
                    Store.setPin(this, buf)
                    unlocked = true
                    route()
                    return
                } else {
                    first = null
                    buf = ""
                    info.text = "PIN tidak sama. Ulangi dari awal."
                }
            } else if (Store.checkPin(this, buf)) {
                Store.failures = 0
                unlocked = true
                route()
                return
            } else {
                Store.failures = Store.failures + 1
                buf = ""
                info.text = if (Store.failures >= 5) {
                    Store.lockUntil = now + 30_000
                    Store.failures = 0
                    "PIN salah 5 kali. Terkunci 30 detik."
                } else "PIN salah."
            }
            redraw()
        }

        fun key(t: String, f: () -> Unit) = Ui.button(this, t, false, f).apply { textSize = 20f }
        val pad = vbox()
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫")
        keys.chunked(3).forEach { r ->
            pad.addView(row(*r.map { k ->
                if (k.isEmpty()) View(this) else key(k) {
                    if (k == "⌫") { if (buf.isNotEmpty()) buf = buf.dropLast(1) }
                    else if (buf.length < 6) { buf += k; if (buf.length == 6) { redraw(); done(); return@key } }
                    redraw()
                }
            }.toTypedArray()))
        }
        redraw()
        scaffold(
            "flow admin", if (setup) "Pengaturan awal" else "Terkunci",
            card(null, title, dots, info.apply { setPadding(0, dp(6), 0, dp(10)) }, pad)
        )
    }

    // ---------- impor kunci ----------

    private fun keyScreen() {
        val input = Ui.field(this, "Tempel kunci admin", true)
        val msg = label("", 13f)
        scaffold(
            "flow admin", "Kunci penandatangan",
            card(
                "Impor kunci admin",
                label("Tempel kunci pendek (43 karakter) atau teks dari berkas flooww-admin-key.txt. Kunci ini yang membuat lisensi sah, jadi simpan baik-baik dan jangan dibagikan."),
                spaced(input),
                row(
                    btn("Tempel dari salinan") {
                        val t = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
                        input.setText(t)
                    },
                    btn("Simpan kunci", true) {
                        val e = Store.importKey(this, input.text.toString())
                        if (e == null) route() else msg.text = e
                    }
                ),
                msg
            ),
            card(
                "Punya cadangan?", label("Pulihkan dari cadangan terenkripsi (berisi kunci, lisensi, dan rilis)."),
                spaced(btn("Pulihkan cadangan") { pickBackup() })
            )
        )
    }

    // ---------- layar utama ----------

    private fun mainScreen() {
        content = vbox()
        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = Ui.clay(this@AdminActivity, p.surface, dp(20), 4f)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
        }
        val head = vbox().apply { setPadding(dp(16), dp(24), dp(16), dp(8)) }
        head.addView(label("flow admin", 26f, p.on).apply { typeface = Typeface.create("sans-serif-black", Typeface.NORMAL) })
        head.addView(label("Lisensi flooww", 13f))
        root.addView(head)
        root.addView(ScrollView(this).apply {
            addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(dp(16), dp(6), dp(16), dp(12))
            clipToPadding = false
        }, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(tabBar, lp().apply { setMargins(dp(12), 0, dp(12), dp(12)) })
        setContentView(root)
        showTab(tab)
        // sinkron rilis terbaru di latar supaya lisensi baru langsung memakai versi terbaru
        Thread { try { Store.syncLatest(this) } catch (e: Exception) { } }.start()
        // seperti flooww: otomatis memeriksa pembaruan flow admin setiap dibuka
        checkUpdate(null, auto = true)
    }

    private fun showTab(t: Int) {
        tab = t
        tabBar.removeAllViews()
        listOf("Buat", "Pembeli", "Rilis", "Lainnya").forEachIndexed { i, n ->
            tabBar.addView(TextView(this).apply {
                text = n
                gravity = Gravity.CENTER
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(if (i == t) 0xFFFFFFFF.toInt() else p.on)
                setPadding(dp(4), dp(10), dp(4), dp(12))
                if (i == t) background = Ui.clay(this@AdminActivity, Ui.ACCENT, dp(14), 3f)
                setOnClickListener { showTab(i) }
            }, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        content.removeAllViews()
        when (t) {
            0 -> createTab()
            1 -> buyersTab()
            2 -> releaseTab()
            else -> moreTab()
        }
    }

    // ---------- tab Buat ----------

    private var editing: Lic? = null

    private fun createTab(prefill: Lic? = null) {
        val device = Ui.field(this, "Kode perangkat (A1B2-C3D4-E5F6)").apply { setText(prefill?.device ?: "") }
        val name = Ui.field(this, "Nama pembeli (opsional)").apply { setText(prefill?.name ?: "") }
        val phone = Ui.field(this, "Nomor HP (opsional)", number = true).apply { setText(prefill?.phone ?: "") }
        val school = Ui.field(this, "Sekolah (opsional)").apply { setText(prefill?.school ?: "") }
        val cls = Ui.field(this, "Kelas (opsional)").apply { setText(prefill?.cls ?: "") }
        val free = Ui.field(this, "Update gratis", number = true).apply { setText(Store.freeUpdates(this@AdminActivity).toString()) }
        val msg = label("", 13f, Ui.ACCENT)
        val result = vbox()
        // kedaluwarsa: 0 = jangan pernah; selain itu akhir hari (23:59:59) tanggal yang dipilih
        var expires = prefill?.expires ?: 0L
        val dateFmt = SimpleDateFormat("d MMM yyyy", Locale("id"))
        val expLabel = label("", 13f, p.on, true)
        fun showExp() { expLabel.text = if (expires == 0L) "Kedaluwarsa: jangan pernah" else "Kedaluwarsa: ${dateFmt.format(Date(expires))}" }
        showExp()
        val latest = Store.releases(this).maxOfOrNull { it.code } ?: Store.FIRST_RELEASE_CODE

        fun create() {
            val d = Store.normDevice(device.text.toString())
            if (d == null) { msg.text = "Kode perangkat harus 12 karakter (angka 0-9 dan huruf A-F)."; return }
            val n = free.text.toString().toIntOrNull() ?: Store.freeUpdates(this)
            val lic = Store.issue(this, name.text.toString().trim(), phone.text.toString().trim(), school.text.toString().trim(),
                cls.text.toString().trim(), d, n.coerceIn(0, 50), expires)
            val old = editing
            val all = Store.licenses(this).filter { it.id != old?.id && it.device != d }
            Store.setLicenses(this, all + lic)
            // pembeli sudah punya lisensi: hapus catatan trialnya di awan supaya data tidak menumpuk
            if (Store.cleanupReady(this)) Thread {
                val e = Store.deleteTrial(this, d)
                runOnUiThread { if (!isFinishing && !isDestroyed) toast(if (e == null) "Data trial di awan dihapus" else "Gagal hapus data trial: $e") }
            }.start()
            editing = null
            msg.text = ""
            result.removeAllViews()
            result.addView(card(
                "Lisensi siap",
                label("Untuk ${lic.name.ifBlank { "pembeli" }} · ID ${lic.id} · berlaku sampai versi ${lic.maxV} · " +
                    if (lic.expires == 0L) "tanpa kedaluwarsa" else "habis ${dateFmt.format(Date(lic.expires))}", 13f),
                spaced(TextView(this).apply {
                    text = lic.token
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                    setTextColor(p.on)
                    setTextIsSelectable(true)
                    background = Ui.dent(this@AdminActivity, p.field, dp(12))
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                }),
                row(btn("Salin lisensi", true) { copy("lisensi", lic.token) }, btn("Bagikan") { share(lic.token) })
            ))
        }

        content.addView(card(
            if (prefill != null) "Terbitkan ulang lisensi" else "Buat lisensi",
            label("Minta pembeli membuka flooww, menyalin kode perangkat, lalu kirim ke Anda."),
            spaced(device), spaced(name), spaced(phone), spaced(school), spaced(cls),
            label("Update gratis (sesudah rilis saat ini, kode $latest)").apply { setPadding(0, dp(6), 0, 0) },
            spaced(free),
            expLabel.apply { setPadding(0, dp(10), 0, 0) },
            row(
                btn("Pilih tanggal") {
                    val cal = java.util.Calendar.getInstance().apply { if (expires > 0) timeInMillis = expires else add(java.util.Calendar.MONTH, 1) }
                    android.app.DatePickerDialog(this, { _, y, m, d ->
                        val c = java.util.Calendar.getInstance()
                        c.set(y, m, d, 23, 59, 59)
                        c.set(java.util.Calendar.MILLISECOND, 0)
                        expires = c.timeInMillis
                        showExp()
                    }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH)).show()
                },
                btn("Jangan pernah") { expires = 0L; showExp() }
            ),
            spaced(btn(if (prefill != null) "Terbitkan ulang" else "Buat lisensi", true) { create() }),
            msg
        ))
        content.addView(result)
    }

    // ---------- tab Pembeli ----------

    private fun buyersTab() {
        val search = Ui.field(this, "Cari nama, sekolah, kelas, HP, atau ID")
        val list = vbox()
        fun render() {
            list.removeAllViews()
            val q = search.text.toString().trim().lowercase()
            val all = Store.licenses(this)
            val items = all.filter { q.isEmpty() || listOf(it.name, it.school, it.cls, it.phone, it.id, it.device).any { f -> f.lowercase().contains(q) } }
            if (items.isEmpty()) {
                list.addView(label(if (all.isEmpty()) "Belum ada lisensi. Buat di tab Buat." else "Tidak ada yang cocok.").apply { setPadding(dp(4), dp(8), 0, 0) })
            }
            items.forEach { l ->
                val detail = buildString {
                    if (l.school.isNotBlank() || l.cls.isNotBlank()) appendLine(listOf(l.school, l.cls).filter { it.isNotBlank() }.joinToString(", "))
                    if (l.phone.isNotBlank()) appendLine("HP ${l.phone}")
                    append("Perangkat ${l.device}\nID ${l.id} · ${fmt.format(Date(l.issued))}\nBerlaku sampai versi kode ${l.maxV}")
                }
                list.addView(card(
                    l.name.ifBlank { "(tanpa nama)" },
                    label(detail),
                    row(btn("Salin") { copy("lisensi", l.token) }, btn("Bagikan") { share(l.token) }),
                    row(btn("Edit") { editing = l; showTab(0); content.removeAllViews(); createTab(l) }, btn("Hapus") {
                        android.app.AlertDialog.Builder(this).setMessage("Hapus lisensi ${l.name.ifBlank { l.id }}? Pembeli tidak kehilangan akses karena lisensinya tetap sah di HP-nya.")
                            .setPositiveButton("Hapus") { _, _ -> Store.setLicenses(this, Store.licenses(this).filter { it.id != l.id }); render() }
                            .setNegativeButton("Batal", null).show()
                    })
                ))
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = render()
        })
        content.addView(label("${Store.licenses(this).size} pembeli").apply { setPadding(dp(4), 0, 0, dp(6)) })
        content.addView(spaced(search))
        content.addView(list)
        render()
    }

    // ---------- tab Rilis ----------

    private fun releaseTab() {
        val msg = label("Memeriksa rilis terbaru di GitHub...", 13f, Ui.ACCENT)
        val rels = Store.releases(this)
        content.addView(card(
            "Rilis terbaru",
            label("Tidak perlu mengisi alamat atau nomor versi. Rilis terbaru dibaca otomatis dari GitHub, dan lisensi baru mencakup rilis terbaru ditambah update gratis."),
            spaced(btn("Periksa lagi", true) { showTab(2) }),
            msg
        ))
        Thread {
            val res = try { Store.syncLatest(this) to null } catch (e: Exception) { null to (e.message ?: e.javaClass.simpleName) }
            runOnUiThread {
                if (isFinishing || isDestroyed || tab != 2) return@runOnUiThread
                if (res.second != null) {
                    msg.text = "Gagal membaca GitHub: ${res.second}. Daftar di bawah dari data tersimpan."
                } else if (res.first != null) {
                    showTab(2)
                } else {
                    msg.text = "Daftar rilis sudah yang terbaru."
                }
            }
        }.start()
        rels.forEach { r ->
            content.addView(card(
                "Versi ${r.code}",
                label(fmt.format(Date(r.date)) + (if (r.notes.isNotBlank()) "\n${r.notes}" else ""))
            ))
        }
    }

    // ---------- tab Lainnya ----------

    /**
     * Cek pembaruan flow admin dari rilis "admin" di GitHub (otomatis, tanpa alamat yang diisi manual).
     * auto = true: dijalankan sendiri saat dibuka; diam bila tidak ada pembaruan atau gagal, dan tidak menampilkan
     * lagi versi yang sudah dijawab "Nanti".
     */
    private fun checkUpdate(msg: TextView?, auto: Boolean = false) {
        if (!auto) msg?.text = "Memeriksa pembaruan..."
        Thread {
            try {
                val info = Updater.fetch()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (info.version <= BuildConfig.VERSION_CODE) {
                        msg?.text = "Versi ${BuildConfig.VERSION_NAME}. Sudah yang terbaru."
                    } else {
                        msg?.text = "Versi ${info.version} tersedia (terpasang ${BuildConfig.VERSION_NAME})."
                        if (!(auto && Store.updSkip(this) == info.version)) {
                            UpdateDialog.show(
                                this, info, "flow admin", BuildConfig.VERSION_NAME, Ui.ACCENT,
                                onLater = { Store.setUpdSkip(this, info.version) },
                                onStatus = { t -> msg?.text = t }
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                if (!auto) runOnUiThread { msg?.text = "Gagal memeriksa: ${e.message ?: e.javaClass.simpleName}" }
            }
        }.start()
    }

    private fun moreTab() {
        val upMsg = label("Versi ${BuildConfig.VERSION_NAME}.", 13f, Ui.ACCENT)
        content.addView(card(
            "Pembaruan flow admin",
            upMsg,
            label("Cara memperbarui: 1. Ketuk Cek pembaruan, atau tunggu jendela Pembaruan tersedia saat aplikasi dibuka. 2. Ketuk Unduh dan pasang, lalu izinkan pasang dari sumber ini bila diminta (sekali saja). 3. Ketuk Pasang di layar Android dan buka flow admin lagi."),
            spaced(btn("Cek pembaruan", true) { checkUpdate(upMsg) })
        ))
        val free = Ui.field(this, "Update gratis bawaan", number = true).apply { setText(Store.freeUpdates(this@AdminActivity).toString()) }
        content.addView(card(
            "Update gratis bawaan",
            label("Jumlah rilis setelah pembelian yang masih dicakup lisensi."),
            spaced(free),
            spaced(btn("Simpan") { Store.setFreeUpdates(this, free.text.toString().toIntOrNull() ?: 2); toast("Tersimpan") })
        ))
        val dbUrl = Ui.field(this, "Alamat Realtime Database (https://...)").apply { setText(Store.dbUrl(this@AdminActivity)) }
        val dbSecret = Ui.field(this, "Rahasia database").apply { setText(Store.dbSecret(this@AdminActivity)) }
        content.addView(card(
            "Bersihkan data trial (opsional)",
            label("Bila diisi, saat lisensi diterbitkan catatan trial pembeli itu di Firebase ikut dihapus. Aplikasi pembeli tidak pernah bisa menghapusnya sendiri, supaya trial tidak bisa diulang. Rahasia database memberi akses penuh ke database, jadi hanya disimpan di HP admin ini dan tidak ikut cadangan."),
            spaced(dbUrl), spaced(dbSecret),
            row(
                btn("Simpan", true) { Store.setCleanup(this, dbUrl.text.toString(), dbSecret.text.toString()); toast("Tersimpan") },
                btn("Hapus trial semua pembeli") {
                    Store.setCleanup(this, dbUrl.text.toString(), dbSecret.text.toString())
                    if (!Store.cleanupReady(this)) { toast("Isi alamat dan rahasia dulu"); return@btn }
                    val all = Store.licenses(this)
                    Thread {
                        val fail = all.count { Store.deleteTrial(this, it.device) != null }
                        runOnUiThread { toast(if (fail == 0) "Selesai: ${all.size} catatan dihapus" else "$fail dari ${all.size} gagal") }
                    }.start()
                }
            )
        ))
        content.addView(card(
            "Kunci penandatangan",
            label("Kunci pendek (43 karakter) sama kuatnya dengan kunci panjang dan bisa ditempel di layar Impor kunci. Siapa pun yang memegang kunci ini bisa membuat lisensi sah, jadi jangan dibagikan dan jangan disimpan di tempat umum."),
            spaced(btn("Salin kunci pendek") {
                val k = try { Store.shortKey(this) } catch (e: Exception) { null }
                if (k == null) toast("Kunci belum ada") else copy("kunci admin", k)
            })
        ))
        content.addView(card(
            "Cadangan",
            label("Cadangan terenkripsi kata sandi, berisi kunci, lisensi, dan rilis. Simpan di tempat aman."),
            row(btn("Ekspor", true) { askPassword("Kata sandi cadangan") { pw -> exportPending = pw; picking = true; startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
                    .putExtra(Intent.EXTRA_TITLE, "flow-admin-cadangan.json"), REQ_EXPORT) } },
                btn("Impor") { pickBackup() })
        ))
        content.addView(card(
            "Keamanan",
            row(btn("Ganti PIN") { pinChange() }, btn("Kunci sekarang") { unlocked = false; route() }),
            label("Aplikasi terkunci otomatis saat ditinggalkan.")
        ))
        content.addView(card(
            "Tentang",
            label("flow admin versi 2. Lisensi memakai tanda tangan ECDSA P-256 dan terikat pada kode perangkat pembeli. Kunci rahasia tidak pernah keluar dari aplikasi ini kecuali lewat cadangan terenkripsi.")
        ))
    }

    private fun pinChange() {
        // PIN lama sudah diverifikasi saat membuka aplikasi: langsung buat PIN baru
        pinScreen(setup = true)
    }

    // ---------- cadangan ----------

    private var exportPending: String? = null
    private var importPassword: String? = null
    private val REQ_EXPORT = 11
    private val REQ_IMPORT = 12

    private fun askPassword(title: String, then: (String) -> Unit) {
        val f = Ui.field(this, "Kata sandi").apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        android.app.AlertDialog.Builder(this).setTitle(title).setView(f)
            .setPositiveButton("Lanjut") { _, _ -> val t = f.text.toString(); if (t.length < 6) toast("Minimal 6 karakter") else then(t) }
            .setNegativeButton("Batal", null).show()
    }

    private fun pickBackup() {
        askPassword("Kata sandi cadangan") { pw ->
            importPassword = pw
            picking = true
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_IMPORT)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        try {
            if (requestCode == REQ_EXPORT) {
                val pw = exportPending ?: return
                contentResolver.openOutputStream(uri)?.use { it.write(Store.exportBackup(this, pw).toByteArray()) }
                exportPending = null
                toast("Cadangan tersimpan")
            } else if (requestCode == REQ_IMPORT) {
                val pw = importPassword ?: return
                val text = contentResolver.openInputStream(uri)?.use { String(it.readBytes()) } ?: ""
                val err = Store.importBackup(this, text, pw)
                importPassword = null
                if (err != null) toast(err) else { toast("Cadangan dipulihkan"); route() }
            }
        } catch (e: Exception) {
            toast("Gagal: ${e.message}")
        }
    }
}
