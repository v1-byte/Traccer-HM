package com.frostbyte.hmtracker.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.frostbyte.hmtracker.*
import kotlinx.coroutines.launch

private fun pct(a: Int, n: Int) = if (n == 0) "0%" else "${Math.round(a * 100.0 / n)}%"

/** Pusat latih: belajar otomatis, latih manual, uji akurasi, dan model sendiri. */
@Composable
fun TrainingScreen(vm: AppVm) {
    val h = hm(); val ctx = LocalContext.current; val tr = vm.trainer
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var prog by remember { mutableStateOf(0 to 0) }
    var report by remember { mutableStateOf<TrainReport?>(null) }
    var autoOn by remember { mutableStateOf(tr.autoLearn) }
    var confirm by remember { mutableStateOf("") }      // "" | "samples" | "learn" | "model"
    var preview by remember { mutableStateOf<Sample?>(null) }
    val uri = LocalUriHandler.current
    var aiOn by remember { mutableStateOf(AiAssist.enabled(ctx)) }
    var aiKey by remember { mutableStateOf(AiAssist.key(ctx)) }
    var aiModel by remember { mutableStateOf(AiAssist.model(ctx)) }

    val exportL = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.toast(if (tr.exportTo(uri)) "Dataset diekspor (${tr.samples.size} sampel)" else "Ekspor gagal")
    }
    val modelL = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.toast(if (tr.importModel(uri)) "Model sendiri dipasang" else "Berkas model tidak valid (butuh zip berisi digits.tflite dan digits.json)")
    }
    val galL = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) decodeUri(ctx, uri)?.let {
            vm.openCam("train"); vm.frameSrc = "gal"; vm.frameRoi = null; vm.frameWarn = null; vm.frame = it
        }
    }

    val l = tr.learned
    val manual = tr.samples.count { !it.auto }
    val autoN = tr.samples.size - manual

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        GhostBtn("← Atur", { vm.go("set") })
        Tx("Latih Kamera", 16f, 700)
        Spacer(Modifier.width(78.dp))
    }

    // ringkasan
    HCard {
        Ct("Ringkasan")
        Mut("Sampel: ${tr.samples.size} (manual $manual, otomatis $autoN) · koreksi yang dipelajari: ${l.events}")
        val top = l.conf.entries.sortedByDescending { it.value }.take(4)
        if (top.isNotEmpty()) Mut("Digit yang sering tertukar: " + top.joinToString(", ") { "${it.key.replace(">", "→")} (${it.value}×)" })
        Mut(if (tr.modelOn) "Model sendiri: terpasang dan aktif" else "Model sendiri: belum dipasang (pakai ML Kit)")
    }

    // 1. belajar otomatis
    HCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Ct("1 · Belajar otomatis")
                Mut("Tiap angka yang kamu simpan dipelajari: digit yang sering tertukar, pola digit dan desimal tiap unit, serta lintasan baca yang paling tepat. Fotonya disimpan sebagai sampel (maks ${Trainer.MAX_AUTO}), hanya di HP ini.")
            }
            Spacer(Modifier.width(10.dp))
            Toggle(autoOn) { autoOn = !autoOn; tr.autoLearn = autoOn }
        }
    }

    // 2. latih manual
    HCard {
        Ct("2 · Latih manual")
        Mut("Jepret meter (unit apa saja, termasuk meter drum atau LCD), lalu ketik angka yang benar. Sampel manual bobotnya 3× lebih berat dan tidak dibuang otomatis. Untuk drum mekanik yang punya drum 1/10 merah, tulis HANYA digit drum gelap yang dihitung, mis. 01058 (drum merah tidak wajib dan dipotong otomatis).")
        PrimaryBtn("Ambil sampel dengan kamera", { vm.openCam("train") }, icon = "cam")
        OutBtn("Ambil dari galeri", { galL.launch("image/*") }, icon = "up")
        val recent = tr.samples.sortedByDescending { it.id }.take(6)
        if (recent.isNotEmpty()) {
            Mut("Sampel terakhir (ketuk untuk melihat/hapus)")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                recent.forEach { s ->
                    val bm = remember(s.id) { tr.sampleBitmap(s.id)?.asImageBitmap() }
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { preview = s }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (bm != null) Image(bm, null, Modifier.fillMaxWidth().height(28.dp), contentScale = ContentScale.Crop)
                        Tx(s.label, 9f, 600, h.sub, maxLines = 1)
                    }
                }
            }
        }
    }

    // latih ulang & uji
    HCard {
        Ct("Latih ulang & uji akurasi")
        Mut("Membaca ulang semua sampel, membangun ulang statistik dari nol, dan mengukur akurasi: tiap sampel diuji dengan pelajaran dari sampel sebelumnya saja, jadi hasilnya jujur. Dibutuhkan minimal 10 sampel.")
        PrimaryBtn(
            if (busy) "Memproses ${prog.first}/${prog.second}…" else "Latih ulang & uji", {
                if (!busy && tr.samples.size >= 10) {
                    busy = true; prog = 0 to tr.samples.size
                    scope.launch {
                        val r = runCatching { tr.retrain { a, b -> prog = a to b } }.getOrNull()
                        busy = false
                        if (r == null) vm.toast("Pengujian gagal") else { report = r; tr.replaceLearned(r.result); vm.toast("Selesai: statistik dibangun ulang dari ${r.total} sampel") }
                    }
                } else if (!busy) vm.toast("Kumpulkan minimal 10 sampel dulu")
            }, icon = "chk", enabled = !busy && tr.samples.size >= 10
        )
        report?.let { r ->
            Tx("Hasil uji pada ${r.total} sampel", 13f, 700, h.tx)
            Mut("ML Kit tebakan teratas benar: ${r.base} (${pct(r.base, r.total)})")
            Mut("Sesudah penyesuaian pola unit: ${r.learned} (${pct(r.learned, r.total)})")
            r.model?.let { Mut("Model sendiri: $it (${pct(it, r.total)}) — sampel yang ikut melatih model membuat angka ini terlalu optimistis. Uji dengan sampel baru.") }
            if (r.passOk.isNotEmpty()) Mut("Lintasan yang benar: " + ALL_PASSES.filter { r.passOk.containsKey(it) }.joinToString(", ") { "$it ${r.passOk[it]}" })
        }
    }

    // 3. model sendiri
    HCard {
        Ct("3 · Model AI sendiri")
        Mut("Ekspor sampel beserta metadata sumber, mode ODO/HM/drum, kondisi gambar, dan dataset_manifest.json. Latih di komputer dengan tools/train_digits.py (butuh sekitar 300+ sampel, idealnya 1000+), lalu pasang hm_digits_model.zip di sini. Model dipakai di kamera live (cepat) dan ikut memberi suara pada pembacaan foto; bila ragu, ML Kit tetap jalan.")
        OutBtn("Ekspor dataset (zip)", { if (tr.samples.isEmpty()) vm.toast("Belum ada sampel") else exportL.launch("hm_dataset.zip") })
        OutBtn("Pasang model (zip)", { modelL.launch("*/*") })
        if (tr.modelOn) OutBtn("Hapus model", { confirm = "model" }, fg = h.er, border = h.er.copy(alpha = .4f))
    }

    // 4. bantuan AI gratis (opsional)
    HCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Ct("4 · Bantuan AI gratis (opsional)")
                Mut("Mati secara bawaan. Saat kamu mengambil sampel latih, AI menebak angka pada potongan meter supaya tidak perlu mengetik dari nol. Tebakan hanya mengisi kolom angka; kamu tetap wajib mencocokkannya dengan meter asli sebelum menyimpan.")
            }
            Spacer(Modifier.width(10.dp))
            Toggle(aiOn) { aiOn = !aiOn; AiAssist.setEnabled(ctx, aiOn) }
        }
        if (aiOn) {
            Mut("Yang dikirim ke Google hanya potongan gambar angka (tanpa watermark, lokasi, atau nama unit), memakai kunci Google AI Studio milikmu sendiri. Jatah gratis ada batasnya dan syarat Google bisa berubah, termasuk soal pemakaian data di paket gratis; cek di AI Studio sebelum dipakai untuk data sensitif.", c = h.wa)
            Field("Kunci API Google AI Studio", aiKey, { aiKey = it; AiAssist.setKey(ctx, it) }, "tempel kunci di sini", secret = true)
            Field("Model", aiModel, { aiModel = it; AiAssist.setModel(ctx, it) }, AiAssist.DEFAULT_MODEL)
            Mut("Kosongkan kolom Model untuk memakai bawaan (${AiAssist.DEFAULT_MODEL}). Bila Google mengganti nama model dan muncul galat 404, isi nama model yang baru di sini. Kunci disimpan di HP ini saja dan tidak ikut backup.")
            OutBtn("Buka Google AI Studio (ambil kunci)", { uri.openUri(AiAssist.KEYS_URL) }, icon = "up")
        }
    }

    HCard {
        Ct("Hapus data latih")
        OutBtn("Hapus semua sampel", { confirm = "samples" }, fg = h.er, border = h.er.copy(alpha = .4f))
        OutBtn("Reset hasil belajar", { confirm = "learn" }, fg = h.er, border = h.er.copy(alpha = .4f))
    }
    Mut("Semua data latih tersimpan di HP ini dan tidak ikut backup.", Modifier.fillMaxWidth().padding(bottom = 12.dp))

    if (confirm.isNotEmpty()) AlertDialog(
        onDismissRequest = { confirm = "" },
        title = { Text(when (confirm) { "samples" -> "Hapus semua sampel?"; "learn" -> "Reset hasil belajar?"; else -> "Hapus model sendiri?" }) },
        confirmButton = {
            DialogBtn("Hapus", {
                when (confirm) { "samples" -> tr.clearSamples(); "learn" -> tr.resetLearning(); else -> tr.removeModel() }
                confirm = ""; report = null
            }, color = h.er, filled = true)
        },
        dismissButton = { DialogBtn("Batal", { confirm = "" }, color = h.sub) }
    )
    preview?.let { s ->
        val bm = remember(s.id) { tr.sampleBitmap(s.id)?.asImageBitmap() }
        AlertDialog(
            onDismissRequest = { preview = null }, title = { Text("Angka: ${s.label}") },
            text = { if (bm != null) Image(bm, null, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) },
            confirmButton = { DialogBtn("Hapus sampel", { tr.removeSample(s.id); preview = null }, color = h.er, filled = true) },
            dismissButton = { DialogBtn("Tutup", { preview = null }, color = h.sub) }
        )
    }
}
