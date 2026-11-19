package com.frostbyte.hmtracker.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frostbyte.hmtracker.*
import java.util.Locale

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
    var dx by remember { mutableStateOf("") }
    var refQ by remember { mutableStateOf("") }
    var arm by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmOut by remember { mutableStateOf(false) }

    fun saveAll(q: Boolean): Boolean {
        val iv = num(wv); val hh = num(wh)
        if (wn.isBlank()) { vm.toast("Isi nama unit"); return false }
        if (iv == null || iv <= 0) { vm.toast("Interval harus lebih dari 0"); return false }
        vm.upd { it.copy(profile = Profile(pn.trim(), pr.trim(), pc.trim())) }
        val base = calc(u).b
        vm.updUnit(ai) { x ->
            val nu = x.copy(name = wn.trim(), cat = wc, type = wt, sat = if (ws == "KM") "km" else "jam", loc = wl.trim(), operator = wo.trim(), interval = iv, meterProfileId = wp, meterDisplayId = wd)
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
        SelectField("Jenis display meter", displayLabel(wd), listOf(DISPLAY_AUTO, DISPLAY_ODO, DISPLAY_HM_LCD, DISPLAY_DRUM).map(::displayLabel)) { label ->
            wd = when (label) {
                displayLabel(DISPLAY_ODO) -> DISPLAY_ODO
                displayLabel(DISPLAY_HM_LCD) -> DISPLAY_HM_LCD
                displayLabel(DISPLAY_DRUM) -> DISPLAY_DRUM
                else -> DISPLAY_AUTO
            }
        }
        Mut("ODO/KM untuk kendaraan, HM LCD untuk panel digital, dan drum untuk angka mekanik yang dapat memiliki digit 1/10 jam.")
        val profileOptions = listOf("Otomatis dari jenis unit") + METER_PROFILES.filter { it.id != "auto" }.map { it.label }
        val profileLabel = METER_PROFILES.firstOrNull { it.id == wp }?.label ?: profileOptions.first()
        SelectField("Profil panel meter", profileLabel, profileOptions) { label ->
            wp = METER_PROFILES.firstOrNull { it.label == label }?.id ?: ""
        }
        Mut("Profil hanya memberi prior ringan pada label/format OCR. Sampel foto meter unit ini tetap paling akurat.")
        FW("Lokasi kerja", wl, "otomatis dari GPS atau ketik", "loc", d.wmShow.loc) { wl = it }
        OutBtn("Pakai lokasi saat ini", { locPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }, icon = "pin", fg = h.tx)
    }
    // HM & interval
    HCard {
        Ct("HM / KM & Interval")
        Field("Angka awal setelah servis", wh, { wh = it }, "contoh: 110", numeric = true)
        OutBtn("Verifikasi dengan foto", { if (saveAll(true)) vm.openCam("init") }, icon = "cam")
        Field("Target interval ($s)", wv, { wv = it }, "contoh: 200", numeric = true)
        Mut("Servis berikutnya di " + f1(c.b + u.interval) + " " + s + ". Indikator kuning <" + n0(warnLevels(u).second) + ", merah <" + n0(warnLevels(u).first) + " " + s + " tersisa.")
    }
    PrimaryBtn("Simpan Pengaturan", { saveAll(false) }, icon = "chk")
    // daftar unit
    HCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Ct("Daftar Unit")
            Box(Modifier.clickable { vm.addUnit(); vm.toast("Unit baru: lengkapi data dan HM awal") }.padding(horizontal = 4.dp, vertical = 8.dp)) { Tx("+ Unit", 14f, 500, h.ac) }
        }
        d.units.forEachIndexed { i, x ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(h.ln))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Tx((if (i == d.active) "● " else "○ ") + x.name.ifEmpty { "Unit belum diatur" }, 14f, 700, maxLines = 1)
                    Tx(x.type.ifEmpty { "-" } + " · " + x.un(), 11f, 400, h.ac)
                }
                Box(Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                    if (arm != "u$i") arm = "u$i" else {
                        arm = ""
                        if (d.units.size < 2) vm.toast("Minimal satu unit") else vm.delUnit(i)
                    }
                }.padding(horizontal = 8.dp, vertical = 6.dp)) { Tx(if (arm == "u$i") "Hapus?" else "✕", 15f, 400, h.sub) }
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
                    Modifier.size(42.dp).then(if (on) Modifier.border(2.dp, h.ac, CircleShape) else Modifier).padding(if (on) 4.dp else 2.dp)
                        .clip(CircleShape).background(hex(a)).clickable { vm.upd { it.copy(acc = a) } }
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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Tx("Simpan otomatis ke galeri", 16f, 600)
                Mut("Setiap foto ber-watermark juga disimpan ke HP (folder Pictures/HM Tracker).")
            }
            ToggleW(d.gal, "Aktif", "Mati") { vm.upd { it.copy(gal = !d.gal) }; vm.toast("Simpan otomatis ke galeri: " + if (!d.gal) "ON" else "OFF") }
        }
    }
    // template watermark
    HCard {
        Ct("Template Watermark"); Mut("Pilih gaya watermark yang muncul di foto HM", Modifier.padding(bottom = 4.dp))
        val tpls = listOf(
            Triple("pro", "Pro Card", "Panel lengkap + branding"), Triple("minimal", "Minimal", "Hanya waktu & status"),
            Triple("bold", "Bold", "Teks besar & tegas"), Triple("strip", "Top Strip", "Bar atas + info ringkas"),
            Triple("classic", "Classic", "Style lama sederhana"), Triple("detail", "Detail Card", "Lengkap: selisih, laju, servis, sumber & ref")
        )
        tpls.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (k, t, sub) ->
                    val on = d.wmTpl == k
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (on) h.ac.copy(alpha = .12f) else Color.Transparent)
                            .border(if (on) 2.dp else 1.dp, if (on) h.ac else h.ln, RoundedCornerShape(12.dp))
                            .clickable {
                                if (k in setOf("minimal", "bold", "strip", "detail") && !d.allows(Feature.WM_EXTRA_TEMPLATES)) vm.toast("Template $t butuh paket Premium")
                                else { vm.upd { it.copy(wmTpl = k) }; vm.toast("Template watermark: $k") }
                            }.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) { Tx(t, 13f, 700); Tx(sub, 11f, 400, h.sub, lh = 1.3f) }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Mut("Ukuran watermark", Modifier.padding(top = 6.dp))
        Seg(listOf("sm" to "Kecil", "md" to "Sedang", "lg" to "Besar"), d.wmSize) { v ->
            vm.upd { it.copy(wmSize = v) }; vm.toast("Ukuran watermark: " + mapOf("sm" to "Kecil", "md" to "Sedang", "lg" to "Besar")[v])
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Checkbox(d.wmGps, { v -> vm.upd { it.copy(wmGps = v) }; vm.toast("GPS di watermark: " + if (v) "ON" else "OFF") }, colors = CheckboxDefaults.colors(checkedColor = h.ac, checkmarkColor = h.on, uncheckedColor = h.sub))
            Mut("Tampilkan koordinat GPS di watermark")
        }
    }
    // latih kamera
    HCard {
        Ct("Latih Kamera")
        Mut("Ajari aplikasi membaca meter kamu: belajar dari koreksi, latih manual, dan pasang model sendiri. ${vm.trainer.samples.size} sampel tersimpan.")
        OutBtn("Buka pusat latih", { vm.go("train") }, icon = "cam")
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
        confirmButton = { TextButton({ confirmReset = false; vm.resetAll() }) { Text("Hapus", color = h.er) } },
        dismissButton = { TextButton({ confirmReset = false }) { Text("Batal") } }
    )
    if (confirmOut) AlertDialog(
        onDismissRequest = { confirmOut = false }, title = { Text("Keluar dari akun?") },
        confirmButton = { TextButton({ confirmOut = false; vm.logout(); vm.toast("Anda telah keluar") }) { Text("Keluar", color = h.er) } },
        dismissButton = { TextButton({ confirmOut = false }) { Text("Batal") } }
    )
}
