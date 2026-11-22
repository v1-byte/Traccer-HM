package com.frostbyte.hmtracker.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.frostbyte.hmtracker.*
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Proyeksi hitungan dari angka yang sedang diketik (tanpa menyimpan). */
fun projM(u: UnitData, v: String, cm: String, t: Long = TrustedClock.now()): Calc {
    val x = num(v)
    if (x == null || x < 0) return if (u.cfg()) calc(u) else emptyCalc(u)
    val rd = u.readings
    if (cm != "init" && rd.isNotEmpty() && x < rd.last().hm) return calc(u)
    return calc(u.copy(interval = if (u.interval > 0) u.interval else 200.0, readings = rd + Reading(t, x, cm == "init" || rd.isEmpty())))
}

@Composable
private fun IssueCard(i: Issue) {
    val h = hm()
    val c = when (i.sev) { Sev.BLOCK -> h.er; Sev.WARN -> h.wa; Sev.INFO -> h.sub }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.copy(alpha = .13f))
            .border(1.dp, c.copy(alpha = .45f), RoundedCornerShape(10.dp)).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.padding(top = 5.dp)) { Dot(c) }
        Tx(i.msg, 12.5f, 400, h.tx, Modifier.weight(1f), lh = 1.4f)
    }
}

@Composable
private fun ConfirmRow(on: Boolean, onClick: () -> Unit) {
    val h = hm()
    Row(
        Modifier.fillMaxWidth().press(onClick = onClick).clip(RoundedCornerShape(10.dp)).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(if (on) h.ac else Color.Transparent)
                .border(1.5.dp, if (on) h.ac else h.sub, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) { if (on) HIcon("chk", 16.dp, h.on) }
        Tx("Saya sudah mencocokkan angka ini dengan layar meter asli", 13f, 400, h.tx, Modifier.weight(1f), lh = 1.4f)
    }
}

@Composable
fun ReviewSheet(vm: AppVm, frame: Bitmap, geo: Pair<Double, Double>?, detLock: String, onClose: () -> Unit) {
    val h = hm(); val ctx = LocalContext.current
    val u = vm.d.unit; val cm = vm.cm; val init = cm == "init"
    val train = cm == "train"            // mode latih: foto + angka benar disimpan sebagai sampel, bukan sebagai catatan HM
    val tr = vm.trainer
    val scope = rememberCoroutineScope()
    val aiReady = AiAssist.ready(ctx)
    var aiBusy by remember(frame) { mutableStateOf(false) }
    var aiNote by remember(frame) { mutableStateOf<String?>(null) }
    val need = u.name.isNotEmpty() || init || train
    val z = if (u.un() == "km") "KM" else "HM"
    val lbl = if (init) "$z awal setelah servis" else "Angka $z di meter"
    val live = if (vm.d.det) detLock.replace(",", ".") else ""
    // Waktu foto: SATU nilai per frame untuk pratinjau, validasi, dan simpan, supaya jam, selisih, dan kode Ref
    // di foto yang tersimpan sama dengan yang terlihat di pratinjau.
    val shotT = remember(frame) { TrustedClock.now() }
    val shotSkew = remember(frame) { TrustedClock.skewMs() }

    var input by remember(frame) { mutableStateOf(live) }
    var touched by remember(frame) { mutableStateOf(false) }
    var autoVal by remember(frame) { mutableStateOf(live) }       // angka hasil baca otomatis (untuk mendeteksi "diedit")
    var photoRead by remember(frame) { mutableStateOf("") }
    var dotNote by remember(frame) { mutableStateOf(false) }
    var repairNote by remember(frame) { mutableStateOf<String?>(null) }
    var ocrState by remember(frame) { mutableStateOf(if (need) "run" else "off") }   // run | ok | conflict | photo | live | none
    var confirmed by remember(frame) { mutableStateOf(false) }
    var alts by remember(frame) { mutableStateOf<List<Alt>>(emptyList()) }       // tafsiran lain dari bacaan OCR untuk dipilih
    var negativeMode by remember(frame) { mutableStateOf(false) }
    var negativeKind by remember(frame) { mutableStateOf("rpm") }
    var vehicleModel by remember(frame) { mutableStateOf(u.type) }
    var unitYear by remember(frame) { mutableStateOf("") }
    var countryMarket by remember(frame) { mutableStateOf("") }
    var lightCondition by remember(frame) { mutableStateOf("normal") }
    var glareLevel by remember(frame) { mutableStateOf("none") }
    var cameraAngle by remember(frame) { mutableStateOf("front") }
    var displayState by remember(frame) { mutableStateOf("normal") }
    var finalRead by remember(frame) { mutableStateOf<FinalRead?>(null) }
    // edit foto (hanya saat menyimpan): putar & kontras sesuai paket; OCR dan foto asli tetap memakai foto mentah
    var rot by remember(frame) { mutableIntStateOf(0) }
    var contrast by remember(frame) { mutableFloatStateOf(0f) }
    val canRot = vm.d.allows(Feature.EDIT_ROTATE)
    val canCon = vm.d.allows(Feature.EDIT_CONTRAST)
    val edit = PhotoEdit(if (canRot) rot else 0, if (canCon) contrast else 0f).normalized()

    // OCR ulang pada foto beresolusi penuh: inilah angka yang akan tercetak di bukti foto
    LaunchedEffect(frame) {
        if (!need) return@LaunchedEffect
        val fr = withContext(Dispatchers.Default) {
            val rc = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                readFinalDetailed(
                    rc, frame, vm.frameRoi, u.meterKind(),
                    tr.learned.passPlan(u.name), tr.model,
                    u.ocrProfile(), drum = u.meterDisplayId == DISPLAY_DRUM
                )
            } catch (e: Exception) { null } finally { rc.close() }
        }
        finalRead = fr
        // hasil belajar: sesuaikan kandidat dengan pola unit, dan izinkan koreksi digit yang pernah tertukar
        val cands = tr.learned.refine(u.name, fr?.ranked ?: emptyList())
        val now = shotT; val sk = shotSkew
        fun plain(t: String) = t.toDoubleOrNull()?.let { Resolved(it, t, false, emptyList()) }
        val pr = if (train) cands.firstOrNull()?.let { plain(it) } else chooseFinal(u, cands, now, init, sk, tr.learned.conf, u.ocrProfile().decimalLikely)
        val lr = if (live.isEmpty()) null else if (train) plain(live) else resolveReading(u, live, now, init, sk, tr.learned.conf, u.ocrProfile().decimalLikely)
        val (pick, st) = when {
            pr != null && lr != null && sameValue(pr.value, lr.value) -> Pair<Resolved?, String>(pr, "ok")
            pr != null && lr != null -> Pair<Resolved?, String>(pr, "conflict")
            pr != null -> Pair<Resolved?, String>(pr, "photo")
            lr != null -> Pair<Resolved?, String>(lr, "live")
            else -> Pair<Resolved?, String>(null, "none")
        }
        photoRead = pr?.text ?: ""
        if (!train) alts = alternativesOf(u, (listOfNotNull(live.ifEmpty { null }) + cands).distinct().take(4), now, init, sk)
        ocrState = st
        autoVal = pick?.text ?: ""
        dotNote = pick?.dotAdded == true
        repairNote = pick?.repairNote
        if (!touched && pick != null) input = pick.text
    }

    // asal angka untuk watermark Detail: diedit manual > OCR live dan foto sepakat > OCR saja
    val srcNote: String? = srcNoteOf(need, train, ocrState, input, autoVal)
    val small = remember(frame) { scaleDown(frame, 1280) }
    val x = num(input)
    val issues: List<Issue> = if (!need) emptyList() else buildList {
        if (x != null && !train) addAll(checkReading(u, x, shotT, init, shotSkew))
        if (ocrState == "conflict") add(Issue(Sev.WARN, "Bacaan live ($live) berbeda dengan bacaan foto ($photoRead). Cocokkan dengan meter asli."))
        if (ocrState == "none") add(Issue(Sev.INFO, "Angka tidak terbaca otomatis. Ketik manual dari meter."))
        vm.frameWarn?.let { add(it) }
        if (dotNote && input.trim() == autoVal) add(Issue(Sev.INFO, "Titik desimal dipasang otomatis ($input). Pastikan sesuai meter."))
        repairNote?.let { if (input.trim() == autoVal) add(Issue(Sev.WARN, it)) }
        if (u.meterDisplayId == DISPLAY_DRUM) finalRead?.tenth?.let { add(Issue(Sev.INFO, tenthInfo(it))) }
    }
    val block = issues.any { it.sev == Sev.BLOCK }
    val warn = issues.any { it.sev == Sev.WARN }
    val edited = input.trim().replace(',', '.') != autoVal
    val canSave = !need || (x != null && !block && confirmed)
    val warnCount = issues.count { it.sev >= Sev.WARN }
    val src = vm.frameSrc.ifEmpty { "cam" }
    val auditUnit = u.name.ifEmpty { "-" }
    // kode Ref bertanda tangan: hanya bila foto ini akan masuk jejak audit (ada angka valid)
    val proof = remember(frame, x, auditUnit, shotT, need, train) {
        if (!need || train || x == null) null
        else vm.store.nextAuditSlot().let { (n, prev) -> vm.store.proofFor(n, shotT, auditUnit, x, prev) }
    }
    val ev = WmEvidence(srcNote, proof, src, shotSkew, warnCount)
    val preview = remember(small, input, vm.d, edit, ev) {
        val bm = applyEdit(small, edit).copy(Bitmap.Config.ARGB_8888, true)
        drawWatermark(Canvas(bm), bm.width.toFloat(), bm.height.toFloat(), vm.d, u, projM(u, input, cm, shotT), shotT, geo, true, ev)
        bm.asImageBitmap()
    }

    /** Simpan potongan area angka + angka benar sebagai sampel latih dan catat ke statistik belajar. Mengembalikan true bila tersimpan. */
    fun saveSample(manual: Boolean, negative: Boolean = false): Boolean {
        val truth = if (negative) "" else normTruth(input) ?: return false
        val fr = finalRead
        val area = if (negative) (vm.frameRoi ?: Roi(0, 0, frame.width, frame.height)) else (fr?.area ?: vm.frameRoi ?: return false)
        val meta = DatasetMeta(
            exactText = truth,
            semanticLabel = if (negative) negativeKind.uppercase() else if (u.meterKind() == MeterKind.KM) "ODO/KM" else if (u.meterDisplayId == DISPLAY_DRUM) "Service Hour Meter / drum" else "Hour Meter / HM",
            targetStatus = if (negative) "negative" else "target",
            regionType = if (negative) negativeKind else "numeric_readout",
            unit = u.un(), machineType = u.type, brandProfile = u.meterProfileId,
            displayMode = u.meterDisplayId, sourceKind = "field_photo", sourceId = u.referenceSourceId(),
            modelContext = u.name, vehicleModel = vehicleModel, unitYear = unitYear, countryMarket = countryMarket,
            lightCondition = lightCondition, glareLevel = glareLevel, cameraAngle = cameraAngle, displayState = displayState,
            glare = glareLevel != "none", occlusion = false
        )
        // drum mekanik: gambar sampel = hanya drum yang dihitung, supaya cocok dengan angka benar (mis. 01058)
        val sampleCrop = cropSafe(frame, area).let { if (u.meterDisplayId == DISPLAY_DRUM && !negative) drumCounted(it) else it }
        val ok = tr.addSample(sampleCrop, u.name, truth, vm.frameSrc, !manual && !negative, meta)
        if (!negative) {
            val winners = fr?.passes?.filter { it.second.firstOrNull() == truth }?.map { it.first }?.distinct() ?: emptyList()
            tr.record(u.name, truth, fr?.ranked?.firstOrNull() ?: "", winners, if (manual) 3 else 1)
        }
        return ok
    }

    fun keep(share: Boolean = false) {
        if (train) {
            if (negativeMode) {
                val ok = saveSample(true, true)
                vm.frame = null; vm.frameWarn = null
                vm.toast(if (ok) "Contoh gangguan $negativeKind tersimpan" else "Gagal menyimpan contoh gangguan")
                return
            }
            if (x == null) { vm.toast("Isi angka dari meter"); return }
            if (normTruth(input) == null) { vm.toast("Format angka: digit saja atau satu digit desimal (mis. 1041.0)"); return }
            val ok = saveSample(true)
            vm.frame = null; vm.frameWarn = null
            vm.toast(if (ok) "Sampel latih tersimpan (${tr.samples.size}). Jepret lagi atau tekan kembali." else "Angka tidak ditemukan pada foto, sampel tidak disimpan")
            return
        }
        if (share && !canSave) { vm.toast("Cek dan konfirmasi angka dulu"); return }
        val t = shotT; val sk = shotSkew
        var hh = Double.NaN
        if (need) {
            if (x == null) { vm.toast("Isi angka dari meter"); return }
            if (block) { vm.toast(issues.first { it.sev == Sev.BLOCK }.msg); return }
            hh = x; vm.addHM(vm.d.active, x, init, t)
        }
        val u2 = vm.d.unit; val m2 = calc(u2)
        val out = applyEdit(frame, edit).copy(Bitmap.Config.ARGB_8888, true)
        // nomor urut dan tanda tangan sebelumnya dibaca ulang di sini (satu thread dengan penyimpanan di bawah), jadi Ref pasti cocok dengan entri audit
        val saveProof = if (need && !hh.isNaN()) vm.store.nextAuditSlot().let { (n, prev) -> vm.store.proofFor(n, t, u2.name.ifEmpty { "-" }, hh, prev) } else null
        drawWatermark(Canvas(out), out.width.toFloat(), out.height.toFloat(), vm.d, u2, m2, t, geo, true, ev.copy(proof = saveProof))
        val au = if (need) Audit(
            t, u2.name.ifEmpty { "-" }, live, photoRead, hh, edited, sk, geo != null,
            issues.filter { it.sev >= Sev.WARN }.joinToString(" | ") { it.msg }.take(400), src = vm.frameSrc
        ) else null
        vm.addPhoto(out, Photo(t, u2.name.ifEmpty { "-" }, if (hh.isNaN()) 0.0 else hh), frame, au)
        if (need && tr.autoLearn && !hh.isNaN()) saveSample(false)    // belajar otomatis: bacaan yang dikonfirmasi ikut jadi sampel
        // setelah dicek dan dikonfirmasi, foto selalu disalin ke HP (Pictures/HM Tracker); tidak ada pengaturan terpisah
        val inGallery = saveFileToGallery(ctx, vm.store.file(t), galName(u2.name, t))
        vm.frame = null; vm.frameWarn = null; onClose()
        vm.toast((if (need) (if (init) "HM awal tersimpan: " else "Tersimpan: ") + f1(hh) + " " + u2.un() else "Foto tersimpan") +
            (if (inGallery) " · tersimpan di HP (Pictures/HM Tracker)" else " · di aplikasi (gagal menyalin ke galeri HP)"))
        if (share && !sharePhoto(ctx, vm.store.file(t), photoCaption(Photo(t, u2.name.ifEmpty { "-" }, if (hh.isNaN()) 0.0 else hh)))) vm.toast("Gagal membuka menu berbagi")
        if (need && m2.st == "er") vm.stopAlert = true
    }

    Sheet({ vm.frame = null }) {
        Ct(if (train) "Sampel latih" else "Preview + Watermark", 15f)
        Mut(if (train) "Pastikan kotak angka di bawah memuat angka meter, lalu ketik angka yang benar." else "Template: ${vm.d.wmTpl.uppercase()} — cek watermark di foto", Modifier.offset(y = (-6).dp))
        Image(preview, null, Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
        if (train) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutBtn(if (!negativeMode) "Mode angka ✓" else "Mode angka", { negativeMode = false }, modifier = Modifier.weight(1f))
                OutBtn(if (negativeMode) "Gangguan ✓" else "Simpan gangguan", { negativeMode = true }, modifier = Modifier.weight(1f))
            }
            if (negativeMode) {
                Mut("Pilih jenis area negatif; contoh ini diekspor sebagai negative dan tidak ikut evaluasi angka.")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("rpm", "trip", "speedometer", "fuel_gauge", "temperature", "serial_number", "date", "coordinates", "warning_light", "icon_graph").take(5).forEach { kind ->
                        OutBtn(if (negativeKind == kind) "$kind ✓" else kind, { negativeKind = kind }, modifier = Modifier.weight(1f))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("rpm", "trip", "speedometer", "fuel_gauge", "temperature", "serial_number", "date", "coordinates", "warning_light", "icon_graph").drop(5).forEach { kind ->
                        OutBtn(if (negativeKind == kind) "$kind ✓" else kind, { negativeKind = kind }, modifier = Modifier.weight(1f))
                    }
                }
            }
            // pratinjau potongan yang akan disimpan: bila bukan angka meter, buang saja
            val area = if (negativeMode) (vm.frameRoi ?: Roi(0, 0, frame.width, frame.height)) else (finalRead?.area ?: vm.frameRoi)
            val thumb = remember(frame, area) { area?.let { r -> cropSafe(frame, r).asImageBitmap() } }
            if (thumb != null) Image(thumb, null, Modifier.fillMaxWidth().heightIn(max = 110.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Fit)
            else if (!negativeMode && ocrState != "run") Mut("Angka tidak ditemukan pada foto ini, sampel tidak bisa disimpan. Jepret ulang dengan angka di dalam kotak.", c = h.wa)
            if (!negativeMode && aiReady) {
                OutBtn(if (aiBusy) "AI sedang membaca…" else "Tebak angka dengan AI", {
                    val ar = area
                    if (aiBusy) { }
                    else if (ar == null) vm.toast("Angka tidak ditemukan pada foto ini")
                    else {
                        aiBusy = true; aiNote = null
                        val crop = cropSafe(frame, ar).let { if (u.meterDisplayId == DISPLAY_DRUM) drumCounted(it) else it }
                        val key = AiAssist.key(ctx); val model = AiAssist.model(ctx)
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { AiAssist.guess(crop, key, model) }
                            aiBusy = false
                            r.onSuccess { t ->
                                val n = normTruth(t)
                                if (n == null) aiNote = "AI membalas \"$t\", bukan format angka meter. Isi manual."
                                else { touched = true; input = n; confirmed = false; aiNote = "Tebakan AI: $n. Cocokkan dengan meter asli sebelum menyimpan." }
                            }.onFailure { e -> aiNote = e.message ?: "AI gagal" }
                        }
                    }
                }, icon = "cam", fg = h.ac, border = h.ac.copy(alpha = .45f))
                aiNote?.let { Mut(it, c = h.sub) }
            }
            if (negativeMode) {
                Field("Model kendaraan", vehicleModel, { vehicleModel = it }, "mis. Canter FE 74")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Tahun unit", unitYear, { unitYear = it }, "2020", modifier = Modifier.weight(1f))
                    Field("Negara/pasar", countryMarket, { countryMarket = it }, "Indonesia", modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Cahaya", lightCondition, { lightCondition = it }, "normal / malam", modifier = Modifier.weight(1f))
                    Field("Silau", glareLevel, { glareLevel = it }, "none / low / high", modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Sudut kamera", cameraAngle, { cameraAngle = it }, "front / side", modifier = Modifier.weight(1f))
                    Field("Status display", displayState, { displayState = it }, "normal / startup", modifier = Modifier.weight(1f))
                }
            }
        }
        if (!train) {
            if (canRot || canCon) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (canRot) OutBtn("Putar 90°", { rot = (rot + 90) % 360 }, modifier = Modifier.weight(1f))
                if (canCon) OutBtn("Kontras −", { contrast = (contrast - 0.1f).coerceAtLeast(-0.5f) }, modifier = Modifier.weight(1f))
                if (canCon) OutBtn("Kontras +", { contrast = (contrast + 0.1f).coerceAtMost(0.5f) }, modifier = Modifier.weight(1f))
                if (!edit.isNone) OutBtn("Reset", { rot = 0; contrast = 0f }, modifier = Modifier.weight(1f))
            }
            if (!canRot) Mut("Putar foto tersedia di paket Premium, kontras di paket Platinum.")
            else if (!canCon) Mut("Atur kontras tersedia di paket Platinum.")
        }
        if (need && !negativeMode) {
            Field(lbl, input, { touched = true; input = it; confirmed = false }, "ketik angka dari meter", numeric = true)
            val (st, stc) = when (ocrState) {
                "run" -> "Memeriksa angka pada foto…" to h.sub
                "ok" -> "Terverifikasi: bacaan live dan foto sama" to h.ok
                "conflict" -> "Bacaan live dan foto berbeda, periksa" to h.wa
                "photo" -> "Terbaca dari foto beresolusi penuh" to h.sub
                "live" -> "Terbaca dari kamera (foto tidak terbaca ulang)" to h.sub
                else -> "Tidak terbaca otomatis, isi manual" to h.sub
            }
            Mut(st + if (touched && edited && ocrState != "run") " · diedit manual" else "", c = stc)
            if (!train && x != null) describeRise(u, x, shotT)?.let { Mut(it) }
            // tafsiran lain dari bacaan OCR (titik desimal digeser, digit mirip yang tertukar): ketuk untuk memakai
            val shown = alts.filter { it.text != input.trim().replace(',', '.') }
            if (!train && ocrState != "run" && shown.isNotEmpty()) {
                Mut("Tafsiran lain dari bacaan:")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    shown.take(3).forEach { a ->
                        OutBtn((if (a.ok) "✓ " else "⚠ ") + a.text, { touched = true; input = a.text; confirmed = false }, modifier = Modifier.weight(1f))
                    }
                }
                shown.take(3).forEach { a -> Mut(a.text + ": " + a.note + (if (a.ok) " · lolos pemeriksaan riwayat" else " · tidak lolos pemeriksaan riwayat"), c = h.sub) }
            }
            issues.forEach { IssueCard(it) }
            ConfirmRow(confirmed) { confirmed = !confirmed }
        } else Mut("Unit belum diatur. Lengkapi data di Atur agar watermark lengkap.")
        PrimaryBtn(
            if (train) if (negativeMode) "Simpan contoh gangguan" else "Simpan sampel latih" else if (warn) "Simpan tetap (ada peringatan)" else "Simpan foto ber-watermark", { keep() }, icon = "chk",
            bg = if (warn) h.wa else h.ac, fg = if (warn) Color(0xFF111111) else h.on, enabled = if (train && negativeMode) true else canSave
        )
        if (!train) OutBtn("Simpan & bagikan", { keep(true) }, icon = "share")
        OutBtn("Buang", { vm.frame = null; vm.frameWarn = null })
    }
}

@Composable
fun PhotoDetailSheet(vm: AppVm, p: Photo) {
    val h = hm(); val ctx = LocalContext.current
    val bm by produceState<Bitmap?>(null, p.t) { value = withContext(Dispatchers.IO) { vm.store.bitmap(p.t, 1) } }
    val au = remember(p.t) { vm.store.audit(p.t) }
    val hasOrig = remember(p.t) { vm.store.hasOrig(p.t) }
    val vd by produceState<Verdict?>(null, p.t) { value = withContext(Dispatchers.IO) { runCatching { vm.store.verifyAll(vm.d, p.t)[p.t] }.getOrNull() } }
    var arm by remember { mutableStateOf(false) }
    Sheet({ vm.viewPhoto = null }) {
        Ct("Detail Foto")
        bm?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, h.ln, RoundedCornerShape(14.dp)), contentScale = ContentScale.Fit) }
        Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Tx(p.u.ifEmpty { "Unit" }, 15f, 700)
            Mut(f1(p.hm) + " · " + dt(p.t), Modifier.padding(top = 4.dp))
        }
        au?.let { a ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Tx("Jejak audit", 12.5f, 700, h.ac)
                vd?.let { v ->
                    val c = when { v.bad -> h.er; v.trust == Trust.VERIFIED -> h.ok; else -> h.wa }
                    Tx("Integritas: " + v.trust.label(), 12.5f, 700, c)
                    Mut(v.detail, c = c)
                }
                if (a.src == "gal") Mut("Sumber: galeri (tidak melewati pemeriksaan kamera dan GPS)", c = h.wa)
                Mut("Asal angka: " + if (a.edited) "diedit manual" else "otomatis (OCR)")
                Mut("OCR live: ${a.live.ifEmpty { "-" }} · OCR foto: ${a.photo.ifEmpty { "-" }}")
                Mut("Waktu: " + (if (a.skewMs > 120_000) "jam HP selisih ${a.skewMs / 60_000} mnt dari GPS (waktu GPS dipakai)" else "sesuai") + " · GPS: " + if (a.gps) "ada" else "tidak ada")
                if (a.warn.isNotEmpty()) Mut("Peringatan saat simpan: ${a.warn}", c = h.wa)
                if (a.sha.isNotEmpty()) Mut("SHA-256: ${a.sha.take(16)}…")
                if (a.sig.isNotEmpty()) vm.store.proofFor(a.n, a.t, a.unit, a.value, a.prev)?.let { Mut("Kode Ref: $it (sama dengan yang tercetak di watermark Detail Card)") }
            }
        }
        PrimaryBtn("Bagikan", { if (!sharePhoto(ctx, vm.store.file(p.t), photoCaption(p))) vm.toast("Gagal membuka menu berbagi") }, icon = "share")
        Mut("Foto yang sudah disimpan otomatis ada di HP (Pictures/HM Tracker).")
        OutBtn("Simpan salinan ke galeri", { vm.toast(if (saveFileToGallery(ctx, vm.store.file(p.t), galName(p.u, p.t))) "Disimpan ke galeri / folder Pictures" else "Gagal menyimpan") })
        if (hasOrig) OutBtn("Simpan foto asli (tanpa watermark)", {
            vm.toast(if (saveFileToGallery(ctx, vm.store.origFile(p.t), "ASLI_" + galName(p.u, p.t))) "Foto asli disimpan ke galeri" else "Gagal menyimpan")
        })
        OutBtn("Tutup", { vm.viewPhoto = null })
        OutBtn(if (arm) "Tekan lagi untuk menghapus" else "Hapus foto", {
            if (!arm) arm = true else { vm.removePhoto(p.t); vm.viewPhoto = null }
        }, fg = h.er, border = h.er.copy(alpha = .35f))
    }
}

@Composable
fun StopSheet(vm: AppVm) {
    val h = hm()
    Sheet({ vm.stopAlert = false }, topBorder = h.er) {
        Tx("STOP, LAKUKAN SERVIS BERKALA", 18f, 700, h.er)
        val su = vm.d.unit
        Tx("Sisa interval kurang dari ${n0(warnLevels(su).first)} ${su.un()}. Hentikan operasi unit dan lakukan servis berkala sekarang.", 12.5f, 400, h.tx, lh = 1.45f)
        PrimaryBtn("Mengerti", { vm.stopAlert = false }, bg = h.er, fg = androidx.compose.ui.graphics.Color.White)
    }
}
