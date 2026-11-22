package com.frostbyte.hmtracker.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frostbyte.hmtracker.*
import java.util.Locale
import kotlin.math.roundToInt

private fun raw(d: Double): String = if (d == 0.0) "" else if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

@Composable
fun SettingsScreen(vm: AppVm) {
    val h = hm(); val ctx = LocalContext.current
    val d = vm.d; val ai = d.active; val u = d.unit; val c = calc(u); val s = u.un(); val p = d.profile
    val key = ai to vm.rev
    var pn by remember(key) { mutableStateOf(p.name) }
    var pr by remember(key) { mutableStateOf(p.role) }
    var pc by remember(key) { mutableStateOf(p.company) }
    var wn by remember(key) { mutableStateOf(u.name) }
    var wo by remember(key) { mutableStateOf(u.operator) }
    var wc by remember(key) { mutableStateOf(u.cat) }
    var wt by remember(key) { mutableStateOf(u.type) }
    var ws by remember(key) { mutableStateOf(if (s == "km") "KM" else "HM (jam)") }
    var wp by remember(key) { mutableStateOf(u.meterProfileId.ifBlank { "" }) }
    var wd by remember(key) { mutableStateOf(u.meterDisplayId) }
    var wl by remember(key) { mutableStateOf(u.loc) }
    var wh by remember(key) { mutableStateOf(raw(c.b)) }
    var wv by remember(key) { mutableStateOf(raw(u.interval)) }
    var wfr by remember(key) { mutableStateOf(raw(u.fuelRate)) }
    var wft by remember(key) { mutableStateOf(u.fuelTolPct.toString()) }
    var dx by remember { mutableStateOf("") }
    var refQ by remember { mutableStateOf("") }
    var arm by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmOut by remember { mutableStateOf(false) }

    fun saveAll(q: Boolean): Boolean {
        val iv = num(wv); val hh = num(wh)
        val fr = (num(wfr) ?: 0.0).coerceIn(0.0, 10_000.0); val ft = (num(wft)?.toInt() ?: DEFAULT_FUEL_TOL).coerceIn(0, 50)
        if (wn.isBlank()) { vm.toast("Isi nama unit"); return false }
        if (iv == null || iv <= 0) { vm.toast("Interval harus lebih dari 0"); return false }
        vm.upd { it.copy(profile = Profile(pn.trim(), pr.trim(), pc.trim())) }
        val base = calc(u).b
        vm.updUnit(ai) { x ->
            val nu = x.copy(name = wn.trim(), cat = wc, type = wt, sat = if (ws == "KM") "km" else "jam", loc = wl.trim(), operator = wo.trim(), interval = iv, meterProfileId = wp, meterDisplayId = wd, fuelRate = fr, fuelTolPct = ft)
            if (hh != null && (nu.readings.isEmpty() || hh != base)) nu.copy(readings = nu.readings + Reading(TrustedClock.now(), hh, true)) else nu
        }
        if (!q) vm.toast("Pengaturan tersimpan")
        return true
    }
    val locPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) fetchLocation(ctx) { la, lo -> if (la != null && lo != null) wl = String.format(Locale.US, "%.5f, %.5f", la, lo) else vm.toast("Lokasi tidak tersedia, ketik manual") }
        else vm.toast("Lokasi tidak tersedia, ketik manual")
    }

    // header
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Tx("Pengaturan", 18f, 700, h.ac); Pill("PRO", h.ac, h.on, 9f)
    }
    Mut("Sesuaikan unit, tampilan, watermark & data", Modifier.padding(bottom = 8.dp))

    fun fw(k: String, v: Boolean) { vm.upd { a -> a.copy(wmShow = when (k) { "unit" -> a.wmShow.copy(unit = v); "op" -> a.wmShow.copy(op = v); else -> a.wmShow.copy(loc = v) }) }
        vm.toast(mapOf("unit" to "Nama unit", "op" to "Operator", "loc" to "Lokasi")[k] + " di watermark: " + if (v) "ON" else "OFF") }

    @Composable fun FW(label: String, v: String, ph: String, k: String, on: Boolean, set: (String) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(label, v, set, ph, modifier = Modifier.weight(1f))
            ToggleW(on, "Tampil", "Sembunyi") { fw(k, !on) }
        }
    }

    // paket layanan: Basic / Premium / Platinum
    HCard {
        Ct("Paket Layanan")
        val cur = tierOf(d.tier)
        Seg(Tier.entries.map { it.id to it.label }, cur.id) { id ->
            vm.upd { it.copy(tier = id) }; vm.toast("Paket: " + tierOf(id).label)
        }
        Mut("Aktivasi paket masih lokal (demo): pembayaran dan lisensi belum tersambung. Paket ditandatangani kunci perangkat ini, jadi backup tidak membawa paket dan data yang diubah tanpa tanda tangan kembali ke Basic. Semua fitur Basic tetap aktif di paket apa pun.")
        Tier.entries.forEach { t ->
            val fs = Feature.entries.filter { it.min == t }
            Mut(t.label + ": " + fs.joinToString(" · ") { it.label }, c = if (cur.rank >= t.rank) h.tx else h.sub)
        }
    }

    // Data unit aktif
    HCard {
        Ct("Data Unit Aktif")
        Field("Nama petugas", pn, { pn = it }, "Dipakai sebagai operator jika kolom operator kosong")
        Field("Jabatan / Role", pr, { pr = it }, "Mekanik, Supervisor...")
        Field("Perusahaan", pc, { pc = it }, "Nama perusahaan (opsional)")
        FW("Nama unit", wn, "contoh: DT 01 / EX-12", "unit", d.wmShow.unit) { wn = it }
        FW("Nama operator", wo, "kosong = pakai nama petugas", "op", d.wmShow.op) { wo = it }
        SelectField("Kategori", wc, TYPES.keys.toList(), { wc = it; wt = TYPES[it]!!.first() })
        SelectField("Jenis unit", wt, TYPES[wc] ?: emptyList(), { wt = it })
        SelectField("Satuan hitungan", ws, listOf("HM (jam)", "KM"), { ws = it })
        SelectField("Jenis display meter", displayLabel(wd), listOf(DISPLAY_AUTO, DISPLAY_ODO, DISPLAY_HM_LCD, DISPLAY_DRUM).map(::displayLabel), onSelect = { label ->
            wd = when (label) {
                displayLabel(DISPLAY_ODO) -> DISPLAY_ODO
                displayLabel(DISPLAY_HM_LCD) -> DISPLAY_HM_LCD
                displayLabel(DISPLAY_DRUM) -> DISPLAY_DRUM
                else -> DISPLAY_AUTO
            }
        })
        Mut("ODO/KM untuk kendaraan, HM LCD untuk panel digital, dan drum untuk angka mekanik yang dapat memiliki digit 1/10 jam.")
        val profileOptions = listOf("Otomatis dari jenis unit") + METER_PROFILES.filter { it.id != "auto" }.map { it.label }
        val profileLabel = METER_PROFILES.firstOrNull { it.id == wp }?.label ?: profileOptions.first()
        SelectField("Profil panel meter", profileLabel, profileOptions, onSelect = { label ->
            wp = METER_PROFILES.firstOrNull { it.label == label }?.id ?: ""
        })
        Mut("Profil hanya memberi prior ringan pada label/format OCR. Sampel foto meter unit ini tetap paling akurat.")
        FW("Lokasi kerja", wl, "otomatis dari GPS atau ketik", "loc", d.wmShow.loc) { wl = it }
        OutBtn("Pakai lokasi saat ini", { locPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }, icon = "pin", fg = h.tx)
    }
    // HM & interval
    HCard {
        Ct("HM / KM & Interval")
        Field("Angka awal setelah servis", wh, { wh = it }, "contoh: 110", numeric = true)
        OutBtn("Verifikasi dengan foto", { if (saveAll(true)) vm.openCam("init") }, icon = "cam")
        Field("Target interval ($s)", wv, { value ->
            wv = value
            num(value)?.takeIf { it > 0 }?.let { interval ->
                if (vm.d.units.getOrNull(ai)?.interval != interval) vm.updUnit(ai) { it.copy(interval = interval) }
            }
        }, "contoh: 200", numeric = true)
        Mut("Interval yang valid tersinkron otomatis ke Status; simpan tetap diperlukan untuk detail lainnya.")
        Mut("Servis berikutnya di " + f1(c.b + u.interval) + " " + s + ". Indikator kuning <" + n0(warnLevels(u).second) + ", merah <" + n0(warnLevels(u).first) + " " + s + " tersisa.")
    }
    // konsumsi BBM: dasar estimasi solar/BBM di watermark
    HCard {
        Ct("Konsumsi BBM (estimasi di watermark)")
        Field(if (s == "km") "Konsumsi rata-rata (km per liter)" else "Konsumsi rata-rata (liter per jam)", wfr, { value ->
            wfr = value
            val r = num(value) ?: 0.0
            if (r >= 0.0 && vm.d.units.getOrNull(ai)?.fuelRate != r) vm.updUnit(ai) { it.copy(fuelRate = r) }
        }, if (s == "km") "contoh: 4" else "contoh: 12", numeric = true)
        Field("Toleransi ± (%)", wft, { value ->
            wft = value
            num(value)?.toInt()?.coerceIn(0, 50)?.let { tol -> if (vm.d.units.getOrNull(ai)?.fuelTolPct != tol) vm.updUnit(ai) { it.copy(fuelTolPct = tol) } }
        }, "contoh: 15", numeric = true)
        Mut("Isi dari rata-rata catatan pengisian BBM unit. Watermark menghitung (HM akhir − HM awal) × konsumsi, ditampilkan sebagai perkiraan ± toleransi. Ini estimasi, bukan hasil ukur: beban kerja, medan, dan waktu idle memengaruhi pemakaian sebenarnya. Kosongkan untuk menyembunyikan baris BBM.")
        val ex = u.readings.lastOrNull()?.let { wmFuelLines(u, c, it.t).firstOrNull() }
        if (ex != null) Mut("Contoh dari 2 pembacaan terakhir: $ex", c = h.ac)
    }
    PrimaryBtn("Simpan Pengaturan", { saveAll(false) }, icon = "chk")
    // daftar unit
    HCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Ct("Daftar Unit")
            GhostBtn("+ Unit", { vm.addUnit(); vm.toast("Unit baru: lengkapi data dan HM awal") })
        }
        d.units.forEachIndexed { i, x ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(h.ln))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Tx((if (i == d.active) "● " else "○ ") + x.name.ifEmpty { "Unit belum diatur" }, 14f, 700, maxLines = 1)
                    Tx(x.type.ifEmpty { "-" } + " · " + x.un(), 11f, 400, h.ac)
                }
                Box(Modifier.defaultMinSize(minWidth = 40.dp, minHeight = 36.dp).press {
                    if (arm != "u$i") arm = "u$i" else {
                        arm = ""
                        if (d.units.size < 2) vm.toast("Minimal satu unit") else vm.delUnit(i)
                    }
                }.clip(RoundedCornerShape(10.dp)).background(if (arm == "u$i") h.er.copy(alpha = .14f) else h.sub.copy(alpha = .10f)).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) { Tx(if (arm == "u$i") "Hapus?" else "✕", 14f, 700, if (arm == "u$i") h.er else h.sub) }
            }
        }
    }
    // tema & warna
    HCard {
        Ct("Tema & Warna")
        Mut("Mode tema")
        Seg(listOf("auto" to "Otomatis", "dark" to "Gelap", "light" to "Terang"), d.mode) { m -> vm.upd { it.copy(mode = m) } }
        Mut("Warna aksen", Modifier.padding(top = 6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ACCENTS.forEach { a ->
                val on = a.equals(d.acc, true)
                Box(
                    Modifier.size(42.dp).press { vm.upd { it.copy(acc = a) } }.then(if (on) Modifier.border(2.dp, h.ac, CircleShape) else Modifier).padding(if (on) 4.dp else 2.dp)
                        .clip(CircleShape).background(hex(a))
                )
            }
        }
    }
    // ukuran teks
    HCard {
        Ct("Ukuran Teks"); Mut("Sesuaikan keterbacaan di perangkat Anda")
        Seg(listOf("sm" to "Kecil", "md" to "Normal", "lg" to "Besar"), d.fs) { v ->
            vm.upd { it.copy(fs = v) }; vm.toast("Ukuran teks: " + mapOf("sm" to "Kecil", "md" to "Normal", "lg" to "Besar")[v])
        }
    }
    // kamera pintar
    HCard {
        Ct("Kamera Pintar")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Tx("Auto detektor angka HM", 16f, 600)
                Mut("Kamera mencari angka meter sendiri, menandainya dengan [ ], dan memfokuskannya. Bila ada beberapa angka, ketuk angka yang dimaksud. Hasilnya terisi otomatis saat foto dan tetap bisa diedit.")
            }
            ToggleW(d.det, "Aktif", "Mati") { vm.upd { it.copy(det = !d.det) }; vm.toast("Auto detektor HM: " + if (!d.det) "ON" else "OFF") }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Tx("Penyimpanan foto", 16f, 600)
            Mut("Setelah angka dicek dan dikonfirmasi, foto ber-watermark otomatis tersimpan di HP (folder Pictures/HM Tracker) dan di galeri aplikasi. Bagikan lewat tombol Bagikan di Detail Foto atau Simpan & bagikan.")
        }
    }
    // template watermark
    HCard {
        Ct("Template Watermark"); Mut("Pilih gaya watermark yang muncul di foto HM", Modifier.padding(bottom = 4.dp))
        WM_TEMPLATES.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { tp ->
                    val on = d.wmTpl == tp.id
                    val locked = tp.premium && !d.allows(Feature.WM_EXTRA_TEMPLATES)
                    Column(
                        Modifier.weight(1f).press {
                                if (locked) vm.toast("Template ${tp.label} butuh paket Premium")
                                else { vm.upd { it.copy(wmTpl = tp.id) }; vm.toast("Template watermark: ${tp.label}") }
                            }.clip(RoundedCornerShape(12.dp)).background(if (on) h.ac.copy(alpha = .12f) else Color.Transparent)
                            .border(if (on) 2.dp else 1.dp, if (on) h.ac else h.ln, RoundedCornerShape(12.dp)).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Tx(tp.label, 13f, 700, modifier = Modifier.weight(1f, fill = false))
                            if (locked) Pill("PREMIUM", h.ac, h.on, 8f)
                        }
                        Tx(tp.desc, 11f, 400, h.sub, lh = 1.3f)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        // pratinjau langsung + ukuran + kepekatan kartu
        var ls by remember(d.wmScale) { mutableStateOf(d.wmScale.toFloat()) }
        var la by remember(d.wmAlpha) { mutableStateOf(d.wmAlpha.toFloat()) }
        val pv = d.copy(wmScale = ls.roundToInt(), wmAlpha = la.roundToInt())
        Mut("Pratinjau (data contoh bila unit belum diisi)", Modifier.padding(top = 6.dp))
        WmPreview(pv)

        Mut("Ukuran watermark: ${ls.roundToInt()}%", Modifier.padding(top = 6.dp))
        Seg(listOf("55" to "Kecil", "80" to "Sedang", "100" to "Besar"), d.wmScale.toString()) { v ->
            vm.upd { it.copy(wmScale = v.toInt()) }; vm.toast("Ukuran watermark: $v%")
        }
        Slider(
            value = ls, onValueChange = { ls = it }, valueRange = WM_SCALE_MIN.toFloat()..WM_SCALE_MAX.toFloat(),
            onValueChangeFinished = { vm.upd { it.copy(wmScale = ls.roundToInt()) } },
            colors = SliderDefaults.colors(thumbColor = h.ac, activeTrackColor = h.ac, inactiveTrackColor = h.ln)
        )
        Mut(if (la.roundToInt() >= 100) "Kartu watermark: pekat" else "Kartu watermark: ${la.roundToInt()}% pekat (${100 - la.roundToInt()}% transparan)")
        Slider(
            value = la, onValueChange = { la = it }, valueRange = WM_ALPHA_MIN.toFloat()..100f,
            onValueChangeFinished = { vm.upd { it.copy(wmAlpha = la.roundToInt()) } },
            colors = SliderDefaults.colors(thumbColor = h.ac, activeTrackColor = h.ac, inactiveTrackColor = h.ln)
        )

        // isi watermark: tiap bagian bisa dimatikan (berlaku di Pro Card dan Detail Card)
        Mut("Isi watermark (Pro Card & Detail Card)", Modifier.padding(top = 6.dp))
        @Composable fun Chk(on: Boolean, label: String, set: (Boolean) -> Unit) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Checkbox(on, { v -> set(v) }, colors = CheckboxDefaults.colors(checkedColor = h.ac, checkmarkColor = h.on, uncheckedColor = h.sub))
                Mut(label)
            }
        }
        Chk(d.wmShow.hm, "Angka utama: HM saat ini, terpakai, sisa") { v -> vm.upd { it.copy(wmShow = it.wmShow.copy(hm = v)) } }
        Chk(d.wmShow.smr, "SMR & servis berikutnya") { v -> vm.upd { it.copy(wmShow = it.wmShow.copy(smr = v)) } }
        Chk(d.wmShow.bar, "Bar interval + persen") { v -> vm.upd { it.copy(wmShow = it.wmShow.copy(bar = v)) } }
        Chk(d.wmShow.more, "Rincian: selisih, HM awal → akhir, laju pemakaian, perkiraan servis") { v -> vm.upd { it.copy(wmShow = it.wmShow.copy(more = v)) } }
        Chk(d.wmShow.fuel, "Estimasi BBM: jam terpakai × konsumsi unit, ± toleransi") { v -> vm.upd { it.copy(wmShow = it.wmShow.copy(fuel = v)) } }
        Chk(d.wmShow.ref, "Bukti: sumber waktu, asal angka, sumber foto, kode Ref") { v -> vm.upd { it.copy(wmShow = it.wmShow.copy(ref = v)) } }
        Chk(d.wmGps, "Tampilkan koordinat GPS di watermark") { v -> vm.upd { it.copy(wmGps = v) }; vm.toast("GPS di watermark: " + if (v) "ON" else "OFF") }
    }
    // latih kamera
    HCard {
        Ct("Latih Kamera")
        Mut("Ajari aplikasi membaca meter kamu: belajar dari koreksi, latih manual, dan pasang model sendiri. ${vm.trainer.samples.size} sampel tersimpan.")
        OutBtn("Buka pusat latih", { vm.go("train") }, icon = "cam")
    }
    // tutorial
    HCard {
        Ct("Tutorial Penggunaan")
        Mut("Putar ulang panduan singkat: tombol kamera, auto detektor angka, template watermark, dan status servis.")
        OutBtn("Putar tutorial", { vm.showTutorial() }, icon = "auto")
    }
    // data & cadangan
    HCard {
        Ct("Data & Cadangan")
        Seg(listOf("csv" to "CSV", "bk" to "Backup", "cp" to "Salin"), "") { k ->
            when (k) {
                "csv" -> dx = (listOf("Unit,Waktu,HM,Titik servis") + d.units.flatMap { x ->
                    x.readings.map { r -> listOf(x.name.replace(",", " "), java.time.Instant.ofEpochMilli(r.t).toString(), r.hm.toString().removeSuffix(".0"), if (r.svc) "ya" else "").joinToString(",") }
                }).joinToString("\n")
                "bk" -> dx = vm.backupJson()
                else -> {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("HM Tracker", dx))
                    vm.toast("Tersalin")
                }
            }
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, h.ln, RoundedCornerShape(12.dp)).padding(10.dp)) {
            BasicTextField(dx, { dx = it }, textStyle = TextStyle(color = h.tx, fontSize = 12.sp), cursorBrush = SolidColor(h.ac), modifier = Modifier.fillMaxWidth(),
                decorationBox = { i -> Box { if (dx.isEmpty()) Text("Tempel backup JSON di sini lalu tekan Pulihkan.", color = h.sub.copy(alpha = .6f), fontSize = 12.sp); i() } })
        }
        OutBtn("Pulihkan dari teks", { vm.toast(if (vm.restore(dx)) "Backup dipulihkan" else "Teks backup tidak valid") })
        val scope = rememberCoroutineScope()
        OutBtn("Periksa integritas jejak audit", {
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { vm.store.verifyAll(vm.d) }.getOrNull() }
                vm.toast(if (r == null) "Pemeriksaan gagal" else {
                    val bad = r.values.count { it.bad }; val ok = r.values.count { it.trust == Trust.VERIFIED }
                    if (bad > 0) "$bad catatan bermasalah, $ok terverifikasi (buka detail foto)" else "Tidak ada masalah · $ok terverifikasi dari ${r.size}"
                })
            }
        })
        Field("Cek kode Ref foto", refQ, { refQ = it }, "mis. 3F9A-27C1 (dari watermark Detail Card)")
        OutBtn("Cocokkan kode Ref", {
            scope.launch {
                val a = withContext(Dispatchers.IO) { runCatching { vm.store.findByRef(refQ) }.getOrNull() }
                if (a == null) vm.toast("Kode Ref tidak ditemukan di perangkat ini (atau format salah, 8 karakter 0-9/A-F)")
                else {
                    val v = withContext(Dispatchers.IO) { runCatching { vm.store.verifyAll(vm.d, a.t)[a.t] }.getOrNull() }
                    vm.toast("Cocok: ${a.unit} · ${f1(a.value)} · ${dt(a.t)}" + (v?.let { " · " + it.trust.label() } ?: ""))
                }
            }
        })
        OutBtn("Reset semua data", { confirmReset = true }, fg = h.er, border = h.er.copy(alpha = .4f))
    }
    // akun
    HCard {
        Ct("Akun"); Mut("Sesi login tersimpan di perangkat ini")
        OutBtn("Keluar / Logout", { confirmOut = true }, fg = h.er, border = h.er.copy(alpha = .4f))
    }
    Mut("HM Tracker Professional · Frostbyte Technologies", Modifier.fillMaxWidth().padding(vertical = 12.dp))

    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false }, title = { Text("Hapus SEMUA data?") },
        confirmButton = { DialogBtn("Hapus", { confirmReset = false; vm.resetAll() }, color = h.er, filled = true) },
        dismissButton = { DialogBtn("Batal", { confirmReset = false }, color = h.sub) }
    )
    if (confirmOut) AlertDialog(
        onDismissRequest = { confirmOut = false }, title = { Text("Keluar dari akun?") },
        confirmButton = { DialogBtn("Keluar", { confirmOut = false; vm.logout(); vm.toast("Anda telah keluar") }, color = h.er, filled = true) },
        dismissButton = { DialogBtn("Batal", { confirmOut = false }, color = h.sub) }
    )
}


/** Pratinjau watermark di foto contoh; memakai [drawWatermark] yang sama dengan kamera dan foto jadi. */
@Composable
internal fun WmPreview(a: AppData) {
    val now = remember { System.currentTimeMillis() }
    val day = 86_400_000L
    val real = a.unit.cfg()
    val sampleBase = UnitData(
        name = "DT 01", type = "Dump Truck", operator = "Budi", loc = "Site Kaltim", interval = 250.0,
        readings = listOf(Reading(now - 3 * day, 1000.0, true), Reading(now - 2 * day, 1011.0, false), Reading(now - day, 1023.5, false))
    )
    val u = if (real) a.unit else sampleBase
    val m = if (real) calc(u) else calc(sampleBase.copy(readings = sampleBase.readings + Reading(now, 1037.0, false)))
    Box(
        Modifier.fillMaxWidth().aspectRatio(0.85f).clip(RoundedCornerShape(12.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF8A9AA8), Color(0xFF4A5560), Color(0xFF1A1F24))))
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawIntoCanvas {
                drawWatermark(
                    it.nativeCanvas, size.width, size.height, a, u, m, now,
                    if (a.wmGps) Pair(-0.50220, 117.15380) else null, true,
                    WmEvidence(srcNote = "OCR TERVERIFIKASI", proof = "A1B2-C3D4")
                )
            }
        }
    }
}
