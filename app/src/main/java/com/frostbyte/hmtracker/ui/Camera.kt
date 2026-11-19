package com.frostbyte.hmtracker.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.frostbyte.hmtracker.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

// ===== util =====

/** Waktu GPS asli (bukan lokasi tiruan) dipakai untuk mengoreksi jam tepercaya. */
@Suppress("DEPRECATION")
private fun syncGpsTime(loc: android.location.Location) {
    val mock = if (Build.VERSION.SDK_INT >= 31) loc.isMock else loc.isFromMockProvider
    if (loc.provider == android.location.LocationManager.GPS_PROVIDER && !mock && loc.time > 0) TrustedClock.sync(loc.time)
}

@SuppressLint("MissingPermission")
fun fetchLocation(ctx: Context, cb: (Double?, Double?) -> Unit) {
    val fine = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarse = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!fine && !coarse) { cb(null, null); return }
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
    val en = lm.getProviders(true)
    val prov = when {
        fine && en.contains("gps") -> "gps"
        en.contains("network") -> "network"
        en.isNotEmpty() -> en[0]
        else -> null
    }
    if (prov == null) { cb(null, null); return }
    androidx.core.location.LocationManagerCompat.getCurrentLocation(lm, prov, null, ContextCompat.getMainExecutor(ctx)) { loc ->
        if (loc != null) { syncGpsTime(loc); cb(loc.latitude, loc.longitude) }
        else { val l = runCatching { lm.getLastKnownLocation(prov) }.getOrNull(); cb(l?.latitude, l?.longitude) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private suspend fun Context.cameraProvider(): ProcessCameraProvider = suspendCoroutine { c ->
    val f = ProcessCameraProvider.getInstance(this)
    f.addListener({ c.resume(f.get()) }, ContextCompat.getMainExecutor(this))
}

fun scaleDown(b: Bitmap, max: Int = 2560): Bitmap {
    val s = minOf(1f, max.toFloat() / maxOf(b.width, b.height))
    return if (s >= 1f) b else Bitmap.createScaledBitmap(b, Math.round(b.width * s), Math.round(b.height * s), true)
}

private fun rotate(b: Bitmap, deg: Int): Bitmap =
    if (deg == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg.toFloat()) }, true)

fun decodeUri(ctx: Context, uri: Uri): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var ss = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / ss > 2560) ss *= 2
    val bm = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = ss }) }
    val ori = ctx.contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) } ?: 1
    val deg = when (ori) { 6 -> 90; 3 -> 180; 8 -> 270; else -> 0 }
    bm?.let { scaleDown(rotate(it, deg)) }
} catch (e: Exception) { null }

/** Tetap tersedia untuk kompatibilitas: ambil urutan digit terpanjang dari teks mentah. */
fun detParse(t: String): String =
    Regex("\\d{2,7}([.,]\\d)?").findAll(t.replace(Regex("\\s+"), "")).map { it.value }.maxByOrNull { it.length } ?: ""

private fun fmtZ(z: Float) = String.format(Locale.US, "%.0f×", z)

@Composable
fun CameraScreen(vm: AppVm) {
    val ctx = LocalContext.current; val lo = LocalLifecycleOwner.current; val dens = LocalDensity.current.density
    val haptic = LocalHapticFeedback.current
    var hasCam by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var permaDenied by remember { mutableStateOf(false) }
    var camErr by remember { mutableStateOf(false) }
    var facing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var geo by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var gpsState by remember { mutableStateOf("search") }   // search | on | off
    var now by remember { mutableLongStateOf(TrustedClock.now()) }
    var vw by remember { mutableIntStateOf(0) }; var vh by remember { mutableIntStateOf(0) }
    val voter = remember { Voter() }
    var dQual by remember { mutableStateOf<Issue?>(null) }

    // kendali kamera
    var cam by remember { mutableStateOf<Camera?>(null) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var ana by remember { mutableStateOf<ImageAnalysis?>(null) }
    var hasFlash by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var ev by remember { mutableIntStateOf(0) }
    var evMin by remember { mutableIntStateOf(0) }; var evMax by remember { mutableIntStateOf(0) }
    var evStep by remember { mutableFloatStateOf(0f) }
    var focusPt by remember { mutableStateOf<Offset?>(null) }
    var focusTick by remember { mutableIntStateOf(0) }
    // pencarian angka otomatis
    var track by remember { mutableStateOf<NumTrack?>(null) }                 // kotak angka yang sedang dilacak (null = pakai kotak [ ] tetap)
    var missN by remember { mutableIntStateOf(0) }                            // berapa kali berturut-turut area terlacak tidak terbaca
    var cycleN by remember { mutableIntStateOf(0) }
    var lastTapAt by remember { mutableLongStateOf(0L) }                      // ketukan manual diutamakan dari fokus otomatis
    var prefPt by remember { mutableStateOf<Triple<Float, Float, Long>?>(null) }   // titik yang diketuk (normal) + batas waktu
    var frameDim by remember { mutableStateOf(0 to 0) }
    var capturing by remember { mutableStateOf(false) }
    // fokus & zoom otomatis
    var trackScore by remember { mutableDoubleStateOf(0.0) }                   // skor pencarian kotak yang sedang dilacak (histeresis)
    var forceSearchUntil by remember { mutableLongStateOf(0L) }                // setelah zoom otomatis: cari ulang penuh sampai waktu ini
    var lastPinchAt by remember { mutableLongStateOf(0L) }                     // cubit manual menahan zoom otomatis 10 dtk
    var autoZoom by remember { mutableStateOf(true) }
    var afState by remember { mutableStateOf(AfState.IDLE) }
    var afFails by remember { mutableIntStateOf(0) }
    var lastSharp by remember { mutableStateOf<Double?>(null) }                // ketajaman area angka terakhir
    val policy = remember { FocusPolicy() }
    // alat bantu profesional: kisi, deteksi goyang, kilat layar + bunyi rana, animasi cincin fokus
    var grid by remember { mutableStateOf(false) }
    var shaky by remember { mutableStateOf(false) }
    var roll by remember { mutableFloatStateOf(0f) }                           // kemiringan HP (derajat), 0 = tegak
    var showLevel by remember { mutableStateOf(true) }                         // penunjuk level (Premium)
    var aeLock by remember { mutableStateOf(false) }                           // AF/AE terkunci (Platinum)
    var autoShoot by remember { mutableStateOf(false) }                        // jepret otomatis saat angka stabil & valid
    val shootGate = remember { AutoShootGate() }
    var stamps by remember { mutableStateOf<List<Roi>>(emptyList()) }          // kotak stempel tanggal/jam di frame (piksel frame analisis)
    var shutterFlash by remember { mutableStateOf(false) }
    val ringAnim = remember { androidx.compose.animation.core.Animatable(1f) }
    val shutterSound = remember { MediaActionSound() }

    val u = vm.d.unit; val m = if (u.cfg()) calc(u) else emptyCalc(u)
    val want = u.meterKind()              // mode display ODO, HM LCD, atau drum
    val meterProfile = u.ocrProfile()
    val train = vm.cm == "train"      // mode latih: sampel foto+angka benar; tanpa watermark, riwayat unit, peringatan servis, dan cek jenis meter

    fun getGeo() {
        gpsState = "search"
        fetchLocation(ctx) { la, lo2 -> if (la != null && lo2 != null) { geo = la to lo2; gpsState = "on" } else { geo = null; gpsState = "off" } }
    }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        hasCam = r[Manifest.permission.CAMERA] == true || ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!hasCam) {
            val act = ctx.findActivity()
            permaDenied = act != null && !act.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        } else permaDenied = false
        getGeo()
    }
    fun askPerms() {
        val need = buildList {
            add(Manifest.permission.CAMERA); add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) getGeo() else perms.launch(need.toTypedArray())
    }
    fun openAppSettings() {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    LaunchedEffect(Unit) {
        askPerms()
        if (!train && u.cfg() && calc(u).st == "er") vm.stopAlert = true
    }
    // kembali dari pengaturan HP: segarkan izin kamera dan GPS
    DisposableEffect(lo) {
        val ob = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                hasCam = ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                if (hasCam) permaDenied = false
                if (gpsState != "on") getGeo()
            }
        }
        lo.lifecycle.addObserver(ob); onDispose { lo.lifecycle.removeObserver(ob) }
    }
    LaunchedEffect(Unit) { while (true) { now = TrustedClock.now(); delay(1000) } }
    LaunchedEffect(focusTick) { if (focusTick > 0) { delay(900); focusPt = null } }
    LaunchedEffect(focusTick) {
        if (focusTick > 0) { ringAnim.snapTo(1.45f); ringAnim.animateTo(1f, androidx.compose.animation.core.tween(240)) }
    }
    LaunchedEffect(shutterFlash) { if (shutterFlash) { delay(90); shutterFlash = false } }
    // akselerometer: peringatan "Tahan HP stabil" saat tangan bergetar; berhenti otomatis saat aplikasi di latar
    DisposableEffect(lo) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val acc = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gate = MotionGate()
        val sl = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val mv = gate.feed(e.values[0], e.values[1], e.values[2]); if (mv != shaky) shaky = mv
                val r = Math.toDegrees(Math.atan2(e.values[0].toDouble(), e.values[1].toDouble())).toFloat()
                if (Math.abs(r - roll) > 0.5f) roll = r
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        val ob = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) { gate.reset(); shaky = false; if (acc != null) sm?.registerListener(sl, acc, SensorManager.SENSOR_DELAY_GAME) }
            if (e == Lifecycle.Event.ON_PAUSE) { sm?.unregisterListener(sl); shaky = false }
        }
        lo.lifecycle.addObserver(ob)
        onDispose { lo.lifecycle.removeObserver(ob); sm?.unregisterListener(sl) }
    }

    // ---- kamera ----
    val exec = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; keepScreenOn = true } }
    val recog = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    var busy by remember { mutableStateOf(false) }
    var lastRun by remember { mutableLongStateOf(0L) }

    // Analisis tiap ~0,5 dtk. Angka meter dicari otomatis di seluruh frame (OCR penuh tiap 3 siklus), lalu area itu dibaca
    // dengan pengolahan khusus; ketajaman/silau dinilai, 4 dari 6 frame harus sepakat. Bila tak ada angka ditemukan,
    // dipakai kotak [ ] tetap seperti sebelumnya.
    // area baca di sekitar angka, dipangkas supaya tidak memuat stempel tanggal/jam di dekatnya
    fun roiFor(tr: NumTrack, fw: Int, fh: Int, padX: Float, padY: Float, st: List<Roi>, sw: Int, sh: Int): Roi {
        val roi = trackToRoi(tr, fw, fh, padX, padY)
        if (st.isEmpty()) return roi
        val k = fw.toFloat() / sw.coerceAtLeast(1)
        val scaled = if (sw == fw && sh == fh) st else st.map { Roi((it.x * k).toInt(), (it.y * k).toInt(), (it.w * k).toInt().coerceAtLeast(1), (it.h * k).toInt().coerceAtLeast(1)) }
        return trimRoiAwayFromStamps(roi, trackToRoi(tr, fw, fh, 0f, 0f), scaled)
    }
    val analyzer = ImageAnalysis.Analyzer { img ->
        val t = SystemClock.elapsedRealtime()
        if (!vm.d.det || vm.frame != null || busy || t - lastRun < 500 || vw == 0) { img.close(); return@Analyzer }
        lastRun = t; busy = true
        try {
            val full = rotate(img.toBitmap(), img.imageInfo.rotationDegrees)
            val fw = full.width; val fh = full.height
            frameDim = fw to fh
            cycleN++
            fun readRoi(roi: Roi, tracked: Boolean) {
                try {
                    val crop = cropSafe(full, roi)
                    val st = statsOf(crop); dQual = judgeQuality(st); lastSharp = st.sharp
                    fun finish(c: String) {
                        voter.feed(c)
                        if (tracked) { if (c.isEmpty()) { missN++; if (missN >= 2) track = null } else missN = 0 }
                        busy = false
                    }
                    // model sendiri (bila terpasang): sangat cepat; dipakai langsung bila yakin, selain itu lanjut ke ML Kit
                    vm.trainer.model?.let { dm ->
                        val mr = dm.read(crop)
                        val mt = mr?.let { normalizeCandidate(it.text) }
                        if (mr != null && mt != null && mr.conf >= 0.85f) { finish(mt); return }
                    }
                    // hasil belajar: unit yang lebih sering benar dengan polaritas terbalik langsung mencoba itu (hemat satu panggilan OCR)
                    val flip = vm.trainer.learned.preferInverse(vm.d.unit.name)
                    val first = if (flip) prepForOcr(crop, 128, st.mean >= 110) else prepForOcr(crop, 128, null)
                    recog.process(InputImage.fromBitmap(first, 0))
                        .addOnSuccessListener { r1 ->
                            val c1 = bestCandidate(r1)
                            if (c1.isNotEmpty()) { finish(c1); return@addOnSuccessListener }
                            // tidak terbaca: coba sekali lagi dengan polaritas dibalik (angka terang di latar gelap atau sebaliknya)
                            val alt = if (flip) prepForOcr(crop, 128, null) else {
                                val inv = prepForOcr(crop, 128, true)
                                if (inv.sameAs(first)) prepForOcr(crop, 128, false) else inv
                            }
                            recog.process(InputImage.fromBitmap(alt, 0))
                                .addOnSuccessListener { r2 -> finish(bestCandidate(r2)) }
                                .addOnFailureListener { finish("") }
                        }
                        .addOnFailureListener { voter.feed(""); busy = false }
                } catch (e: Exception) { busy = false }
            }
            val fixed = boxToFrame(vw, vh, dens, fw, fh, 0.06f)
            val cur = track
            val forced = t < forceSearchUntil
            if (cur != null && cycleN % 3 != 0 && !forced) readRoi(roiFor(cur, fw, fh, 0.15f, 0.4f, stamps, fw, fh), true)
            else {
                val pp = prefPt?.takeIf { it.third > t }
                recog.process(InputImage.fromBitmap(full, 0))
                    .addOnSuccessListener { txt ->
                        // angka HM dipilih dengan bantuan label di dekatnya; kotak digabung dengan histeresis agar tidak loncat
                        val lines = tokensOf(txt)
                        stamps = lines.flatMap { ln -> ln.filter { isDateLike(it.s) }.map { Roi(it.l, it.t, (it.r - it.l).coerceAtLeast(1), (it.b - it.t).coerceAtLeast(1)) } }
                        val hit = pickMeter(lines, fw, fh, pp?.first ?: 0.5f, pp?.second ?: 0.5f, want = want, profile = meterProfile)
                        val st = mergeTrack(
                            TrackState(track, trackScore),
                            hit?.let { trackOf(it.l, it.t, it.r, it.b, fw, fh, it.tag) }, hit?.score ?: 0.0, force = forced
                        )
                        track = st.track; trackScore = st.score
                        if (hit != null) missN = 0
                        val tr = st.track
                        readRoi(if (tr != null) roiFor(tr, fw, fh, 0.15f, 0.4f, stamps, fw, fh) else fixed, tr != null)
                    }
                    .addOnFailureListener {
                        val tr = track
                        readRoi(if (tr != null) roiFor(tr, fw, fh, 0.15f, 0.4f, stamps, fw, fh) else fixed, tr != null)
                    }
            }
        } catch (e: Exception) { busy = false } finally { img.close() }
    }

    // Picu fokus + eksposur + white balance pada titik (x,y) di view. Hasilnya (berhasil/gagal) dilaporkan ke [afState] dan [policy].
    // [size] = lebar area ukur (pecahan, 0..1): kecil supaya yang diukur hanya angka, bukan latar belakang.
    fun triggerFocus(c: Camera, x: Float, y: Float, size: Float, cancelSec: Long) {
        val pt = previewView.meteringPointFactory.createPoint(x, y, size)
        val act = FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB)
            .setAutoCancelDuration(cancelSec, TimeUnit.SECONDS).build()
        if (!c.cameraInfo.isFocusMeteringSupported(act)) { afState = AfState.IDLE; return }   // lensa fokus-tetap (mis. kamera depan)
        afState = AfState.SEARCHING
        val fut = c.cameraControl.startFocusAndMetering(act)
        fut.addListener({
            val ok = runCatching { fut.get().isFocusSuccessful }.getOrDefault(false)
            policy.onResult(ok); afFails = policy.failures
            afState = if (ok) AfState.LOCKED else AfState.FAILED
        }, ContextCompat.getMainExecutor(ctx))
    }

    // Kunci AF/AE (Platinum): fokus & eksposur dikunci pada angka dan tidak dipicu ulang otomatis sampai dilepas
    fun toggleAeLock() {
        val c = cam ?: return
        if (aeLock) {
            runCatching { c.cameraControl.cancelFocusAndMetering() }
            aeLock = false; afState = AfState.IDLE
            vm.toast("Kunci AF/AE dilepas")
        } else {
            val tr = track
            val p = if (tr != null && vw > 0) trackInView(tr, vw, vh, dens).let { Offset(it[0] + it[2] / 2f, it[1] + it[3] / 2f) }
                else focusPt ?: Offset(vw / 2f, vh / 2f)
            val pt = previewView.meteringPointFactory.createPoint(p.x, p.y, 0.15f)
            val act = FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .disableAutoCancel().build()
            runCatching { c.cameraControl.startFocusAndMetering(act) }
            aeLock = true; afState = AfState.LOCKED
            vm.toast("AF/AE terkunci")
        }
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    // Pengendali fokus otomatis: memantau angka yang dilacak dan memicu fokus hanya bila perlu (angka baru/bergeser,
    // foto mulai buram, fokus gagal, atau penyegaran berkala). Ketukan manual diutamakan 4 detik.
    LaunchedEffect(cam, track != null) {
        val c = cam ?: return@LaunchedEffect
        if (track == null) { afState = AfState.IDLE; return@LaunchedEffect }
        policy.reset()
        while (true) {
            val tr = track ?: break
            val now = SystemClock.elapsedRealtime()
            if (!aeLock && vw > 0 && policy.shouldTrigger(now, tr.cx, tr.cy, lastSharp, lastTapAt + 4000)) {
                policy.markTriggered(now, tr.cx, tr.cy)
                val b = trackInView(tr, vw, vh, dens)
                val cx = b[0] + b[2] / 2f; val cy = b[1] + b[3] / 2f
                runCatching { triggerFocus(c, cx, cy, (tr.b - tr.t).coerceIn(0.1f, 0.22f), 3) }.onFailure { afState = AfState.FAILED }
                focusPt = Offset(cx, cy); focusTick++
            }
            delay(300)
        }
        afState = AfState.IDLE
    }

    // Zoom otomatis: angka kecil diperbesar (pelan, maks. 4x) hanya bila tetap utuh di dalam frame; angka yang kebesaran
    // diperkecil. Cubit atau ketukan manual menahannya. Koordinat kotak dihitung ulang karena zoom memusat di tengah frame.
    LaunchedEffect(cam, autoZoom) {
        val c = cam ?: return@LaunchedEffect
        while (autoZoom) {
            delay(700)
            val tr = track ?: continue
            val now = SystemClock.elapsedRealtime()
            if (now - lastPinchAt < 10_000 || now - lastTapAt < 4_000 || missN > 0 || vm.frame != null) continue
            val zs = c.cameraInfo.zoomState.value ?: continue
            val cur = zs.zoomRatio
            val nz = autoZoomFor(tr, cur, zs.minZoomRatio, minOf(zs.maxZoomRatio, 4f))
            if (nz != cur) {
                runCatching { c.cameraControl.setZoomRatio(nz) }
                zoom = nz
                track = remapForZoom(tr, nz / cur)
                forceSearchUntil = now + 1000
                policy.reset()
            }
        }
    }

    LaunchedEffect(hasCam, facing) {
        if (!hasCam) return@LaunchedEffect
        try {
            val prov = ctx.cameraProvider()
            val sel = CameraSelector.Builder().requireLensFacing(facing).build()
            // Preview, analisis, dan foto dipaksa rasio 4:3 yang sama supaya kotak [ ] persis sama di ketiganya.
            fun rs(w: Int, hh: Int?): ResolutionSelector {
                val b = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                if (hh != null) b.setResolutionStrategy(ResolutionStrategy(android.util.Size(w, hh), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                return b.build()
            }
            fun bind(high: Boolean): Camera {
                val pv = Preview.Builder().setResolutionSelector(rs(1280, if (high) 960 else null)).build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val cap = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setResolutionSelector(rs(2560, if (high) 1920 else null)).build()
                val an = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setResolutionSelector(rs(1280, if (high) 960 else null)).build()
                an.setAnalyzer(exec, analyzer)
                prov.unbindAll()
                val c = prov.bindToLifecycle(lo, sel, pv, cap, an)
                capture = cap; ana = an
                return c
            }
            // bila HP tidak mendukung kombinasi resolusi tinggi, jatuh ke resolusi bawaan (tetap 4:3)
            val c = try { bind(true) } catch (e: IllegalArgumentException) { bind(false) }
            cam = c
            hasFlash = c.cameraInfo.hasFlashUnit()
            val es = c.cameraInfo.exposureState
            if (es.isExposureCompensationSupported) {
                evMin = es.exposureCompensationRange.lower; evMax = es.exposureCompensationRange.upper; evStep = es.exposureCompensationStep.toFloat()
            } else { evMin = 0; evMax = 0; evStep = 0f }
            ev = 0; zoom = 1f; torch = false
            voter.reset(); camErr = false
            track = null; trackScore = 0.0; afState = AfState.IDLE; policy.reset()
        } catch (e: Exception) { camErr = true }
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { ana?.clearAnalyzer(); ProcessCameraProvider.getInstance(ctx).get().unbindAll() }
            exec.shutdown(); runCatching { recog.close() }; runCatching { shutterSound.release() }
        }
    }

    fun close() { vm.camOpen = false; vm.cm = "" }
    BackHandler { if (vm.frame != null) vm.frame = null else close() }

    fun snap() {
        val cap = capture
        if (!hasCam || camErr || cap == null) { vm.toast("Kamera belum siap"); return }
        if (capturing) return
        capturing = true
        shutterFlash = true
        runCatching { shutterSound.play(MediaActionSound.SHUTTER_CLICK) }
        val trSnap = track
        val stSnap = stamps; val fdSnap = frameDim
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        cap.takePicture(exec, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                var bm: Bitmap? = null; var q: Issue? = null; var roi: Roi? = null
                try {
                    bm = scaleDown(rotate(image.toBitmap(), image.imageInfo.rotationDegrees))
                    if (vw > 0) {
                        // bila angka sedang dilacak, area baca mengikuti angka itu (koordinat normal berlaku karena rasio sama)
                        val b0: Bitmap = bm
                        q = qualityOf(cropSafe(b0, if (trSnap != null) roiFor(trSnap, b0.width, b0.height, 0.15f, 0.4f, stSnap, fdSnap.first, fdSnap.second) else boxToFrame(vw, vh, dens, b0.width, b0.height, 0.06f)))
                        roi = if (trSnap != null) roiFor(trSnap, b0.width, b0.height, 0.3f, 0.8f, stSnap, fdSnap.first, fdSnap.second) else boxToFrame(vw, vh, dens, b0.width, b0.height, 0.25f)
                    }
                } catch (e: Throwable) { bm = null }
                image.close()
                val bmF = bm; val qF = q; val roiF = roi
                Handler(Looper.getMainLooper()).post {
                    capturing = false
                    when {
                        bmF == null -> vm.toast("Kamera belum siap")
                        qF?.sev == Sev.BLOCK -> vm.toast(qF.msg + ". Ulangi pemotretan.")
                        else -> { vm.frameSrc = "cam"; vm.frameRoi = roiF; vm.frameWarn = qF; vm.frame = bmF }
                    }
                }
            }
            override fun onError(e: ImageCaptureException) {
                Handler(Looper.getMainLooper()).post { capturing = false; vm.toast("Kamera belum siap") }
            }
        })
    }
    // tombol volume = rana (dipanggil MainActivity)
    val snapNow = rememberUpdatedState({ snap() })
    DisposableEffect(Unit) { vm.shutter = { snapNow.value.invoke() }; onDispose { vm.shutter = null } }
    // pratinjau zoom 1×/2×/4×: kotak pelacak dihitung ulang seperti pada zoom otomatis; menahan zoom otomatis 10 dtk
    fun setZoomPreset(p: Float) {
        val c = cam ?: return
        val cur = c.cameraInfo.zoomState.value?.zoomRatio ?: 1f
        val t0 = SystemClock.elapsedRealtime()
        lastPinchAt = t0
        runCatching { c.cameraControl.setZoomRatio(p) }
        zoom = p
        if (cur > 0f) track = track?.let { remapForZoom(it, p / cur) }
        forceSearchUntil = t0 + 1000
        policy.reset()
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) decodeUri(ctx, uri)?.let { vm.frameSrc = "gal"; vm.frameRoi = null; vm.frameWarn = null; vm.frame = it }
    }

    // status detektor: angka terkunci diperiksa terhadap riwayat unit
    val z = if (u.un() == "km") "KM" else "HM"
    val lockRaw = voter.lock
    val res = if (lockRaw.isEmpty()) null
        else if (train) trainReading(lockRaw)
        else resolveReading(u, lockRaw, TrustedClock.now(), vm.cm == "init", TrustedClock.skewMs(), decimalHint = meterProfile.decimalLikely)
    var dc = Color(0xFFFFB020); val lbl: String
    // label di dekat angka (HM, SMR, KM, ODO, RPM, ...): memastikan yang terbaca memang meter yang dicari
    val tagFit = if (train) null else track?.tag.matches(want)
    val tagName = when (track?.tag?.kind) { Lbl.KM -> "KM"; Lbl.HM -> "HM"; else -> null }
    if (res != null) {
        val cur = if (u.cfg() && vm.cm != "init") calc(u).c else null
        when {
            res.blocked -> { dc = Color(0xFFFF453A); lbl = if (cur != null) "${f1(res.value)} lebih kecil dari ${f1(cur)}" else "Angka tidak valid" }
            tagFit == false -> { dc = Color(0xFFFFB020); lbl = if (tagName != null) "Ini $tagName, bukan $z · ketuk angka $z" else "Mungkin bukan $z · ketuk angka $z" }
            res.warned -> { dc = Color(0xFFFFB020); lbl = "$z terbaca: ${f1(res.value)} · periksa dulu" }
            else -> { dc = Color(0xFF34C759); lbl = "$z terbaca: ${f1(res.value)} ✓" }
        }
    } else if (voter.last.isNotEmpty()) lbl = "${voter.last}…"
    else lbl = if (track != null) (if (tagFit == true) "Label $z terdeteksi · menstabilkan…" else "Menstabilkan angka $z…") else if (train) "Arahkan ke angka meter · jepret, lalu ketik angka benar" else "Arahkan kamera ke angka $z · dicari otomatis"
    val afColor = when (afState) { AfState.LOCKED -> Color(0xFF34C759); AfState.SEARCHING -> Color(0xFFFFB020); AfState.FAILED -> Color(0xFFFF453A); else -> Color(0xFF8A93A3) }
    val afText = afHint(afState, afFails)
    // cincin rana ikut status bacaan: putih = belum ada, hijau = terkunci & valid, kuning = periksa/goyang, merah = ditolak
    val readyOk = res != null && !res.blocked && !res.warned && tagFit != false
    LaunchedEffect(lockRaw, readyOk) { if (readyOk) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    val ringTarget = when {
        res != null && res.blocked -> Color(0xFFFF453A)
        shaky -> Color(0xFFFFB020)
        res == null -> Color.White
        readyOk -> Color(0xFF34C759)
        else -> Color(0xFFFFB020)
    }
    val ringCol by androidx.compose.animation.animateColorAsState(ringTarget, label = "shutterRing")
    val readyOkState = rememberUpdatedState(readyOk); val lockState = rememberUpdatedState(lockRaw); val shakyState = rememberUpdatedState(shaky)

    // jepret otomatis: bacaan valid dengan nilai sama selama 1 dtk dan HP tidak goyang; jeda 3 dtk setelah menembak
    LaunchedEffect(autoShoot) {
        shootGate.reset()
        while (autoShoot) {
            val fire = shootGate.feed(SystemClock.elapsedRealtime(), readyOkState.value && vm.frame == null && !capturing, lockState.value, !shakyState.value)
            if (fire) snapNow.value.invoke()
            delay(150)
        }
    }

    // petunjuk khusus: pantulan (silau) dan layar berkontras rendah atau berpendar (mis. LCD oranye)
    val tip: String? = when {
        dQual?.msg?.contains("silau") == true -> "Miringkan HP ±15° agar pantulan keluar dari angka"
        dQual?.msg?.contains("Kontras rendah") == true -> "Layar berpendar? Ketuk angka untuk kunci AF/AE, lalu geser sudut sedikit"
        meterProfile.id == "drum" && res == null -> "Drum mekanik: pegang HP tegak lurus kaca, sejajar angka, agar desimal 1/10 terbaca"
        else -> null
    }

    Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { vw = it.width; vh = it.height }) {
            AndroidView({ previewView }, Modifier.fillMaxSize())
            // ketuk = fokus + eksposur, cubit = zoom
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(cam) {
                        detectTapGestures { p ->
                            val c = cam ?: return@detectTapGestures
                            runCatching { triggerFocus(c, p.x, p.y, 0.15f, 3) }.onFailure { afState = AfState.FAILED }
                            focusPt = p; focusTick++
                            // ketukan juga memilih angka mana yang dicari (10 dtk): pencarian berikutnya memprioritaskan angka terdekat
                            lastTapAt = SystemClock.elapsedRealtime()
                            aeLock = false
                            val fd = frameDim
                            if (fd.first > 0 && vw > 0) {
                                val n = viewToNorm(p.x, p.y, vw, vh, fd.first, fd.second)
                                prefPt = Triple(n.first, n.second, lastTapAt + 10_000L); track = null; trackScore = 0.0
                            }
                        }
                    }
                    .pointerInput(cam) {
                        detectTransformGestures { _, _, zm, _ ->
                            val c = cam ?: return@detectTransformGestures
                            val zs = c.cameraInfo.zoomState.value
                            val r = ((zs?.zoomRatio ?: 1f) * zm).coerceIn(zs?.minZoomRatio ?: 1f, zs?.maxZoomRatio ?: 1f)
                            lastPinchAt = SystemClock.elapsedRealtime()
                            runCatching { c.cameraControl.setZoomRatio(r); zoom = r }
                        }
                    }
            )
            if (!hasCam || camErr) {
                Column(
                    Modifier.align(Alignment.TopCenter).padding(top = 140.dp, start = 16.dp, end = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text2(
                        if (!hasCam) "Izin kamera belum diberikan. Kamera dibutuhkan untuk memotret meter. Anda juga bisa memakai tombol unggah di kanan atas untuk mengambil foto dari galeri."
                        else "Kamera tidak bisa dibuka. Tutup aplikasi lain yang memakai kamera lalu coba lagi, atau pakai tombol unggah di kanan atas.",
                        Modifier
                    )
                    if (!hasCam) PrimaryBtn(if (permaDenied) "Buka pengaturan HP" else "Izinkan kamera", { if (permaDenied) openAppSettings() else askPerms() })
                }
            }
            // cincin fokus
            Canvas(Modifier.fillMaxSize()) {
                focusPt?.let {
                    val rc = if (afState == AfState.IDLE) Color.White else afColor
                    drawCircle(rc, 34.dp.toPx() * ringAnim.value, it, style = Stroke(2.dp.toPx()))
                    drawCircle(rc, 2.5.dp.toPx(), it)
                }
            }
            // kisi sepertiga (opsional)
            if (grid) Canvas(Modifier.fillMaxSize()) {
                val gc = Color.White.copy(alpha = .28f); val gw = 1.dp.toPx()
                for (k in 1..2) {
                    drawLine(gc, Offset(size.width * k / 3f, 0f), Offset(size.width * k / 3f, size.height), gw)
                    drawLine(gc, Offset(0f, size.height * k / 3f), Offset(size.width, size.height * k / 3f), gw)
                }
            }
            // penunjuk level (Premium): garis tengah ikut kemiringan HP, hijau bila lurus
            if (showLevel && !train && vm.d.allows(Feature.CAM_LEVEL)) Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2f; val cy = size.height / 2f; val len = 60.dp.toPx()
                val rad = Math.toRadians(-roll.toDouble())
                val dx = (len * Math.cos(rad)).toFloat(); val dy = (len * Math.sin(rad)).toFloat()
                val lc = if (Math.abs(roll) < 1.5f) Color(0xFF34C759) else Color(0xFFFFB020)
                drawLine(lc, Offset(cx - dx, cy - dy), Offset(cx + dx, cy + dy), 2.dp.toPx())
            }
            // detektor [ ]
            if (vm.d.det && vw > 0) {
                val tb = track
                val tgt = if (tb != null) trackInView(tb, vw, vh, dens) else boxInView(vw, vh, dens)
                val spec = androidx.compose.animation.core.tween<Float>(220)
                val ax = androidx.compose.animation.core.animateFloatAsState(tgt[0], spec, label = "bx").value
                val ay = androidx.compose.animation.core.animateFloatAsState(tgt[1], spec, label = "by").value
                val aw = androidx.compose.animation.core.animateFloatAsState(tgt[2], spec, label = "bw").value
                val ah = androidx.compose.animation.core.animateFloatAsState(tgt[3], spec, label = "bh").value
                val b = floatArrayOf(ax, ay, aw, ah)
                // peredup di luar kotak: perhatian tertuju ke angka meter
                Canvas(Modifier.fillMaxSize()) {
                    val sc = Color(0x59000000); val bx = b[0]; val by = b[1]; val bw2 = b[2]; val bh2 = b[3]
                    drawRect(sc, Offset.Zero, Size(size.width, by.coerceIn(0f, size.height)))
                    drawRect(sc, Offset(0f, (by + bh2).coerceIn(0f, size.height)), Size(size.width, (size.height - by - bh2).coerceAtLeast(0f)))
                    drawRect(sc, Offset(0f, by.coerceAtLeast(0f)), Size(bx.coerceIn(0f, size.width), bh2))
                    drawRect(sc, Offset((bx + bw2).coerceIn(0f, size.width), by.coerceAtLeast(0f)), Size((size.width - bx - bw2).coerceAtLeast(0f), bh2))
                }
                Box(Modifier.absoluteOffset((b[0] / dens).dp, (b[1] / dens).dp).size((b[2] / dens).dp, (b[3] / dens).dp)) {
                    Canvas(Modifier.fillMaxSize()) {
                        val sw = 4.dp.toPx(); val half = sw / 2; val bw = 26.dp.toPx(); val rad = 10.dp.toPx(); val W = size.width; val H = size.height
                        val l = Path().apply {
                            moveTo(bw, half); lineTo(rad + half, half); arcTo(Rect(half, half, half + 2 * rad, half + 2 * rad), 270f, -90f, false)
                            lineTo(half, H - half - rad); arcTo(Rect(half, H - half - 2 * rad, half + 2 * rad, H - half), 180f, -90f, false); lineTo(bw, H - half)
                        }
                        val r = Path().apply {
                            moveTo(W - bw, half); lineTo(W - rad - half, half); arcTo(Rect(W - half - 2 * rad, half, W - half, half + 2 * rad), 270f, 90f, false)
                            lineTo(W - half, H - half - rad); arcTo(Rect(W - half - 2 * rad, H - half - 2 * rad, W - half, H - half), 0f, 90f, false); lineTo(W - bw, H - half)
                        }
                        drawPath(l, dc, style = Stroke(sw)); drawPath(r, dc, style = Stroke(sw))
                    }
                    Box(Modifier.align(Alignment.TopCenter).wrapContentSize(Alignment.TopCenter, unbounded = true).offset(y = (-34).dp)) {
                        Row(
                            Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xC70C1016)).padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (tb != null) Dot(afColor)   // titik fokus: kuning = mencari, hijau = terkunci, merah = gagal
                            Tx(lbl, 12.5f, 700, dc, maxLines = 1)
                        }
                    }
                    val sub = listOfNotNull(if (shaky) "Tahan HP lebih stabil" else null, dQual?.msg, tip, afText.ifEmpty { null }).joinToString("\n")
                    if (sub.isNotEmpty()) {
                        Box(Modifier.align(Alignment.BottomCenter).wrapContentSize(Alignment.BottomCenter, unbounded = true).offset(y = 34.dp)) {
                            Box(Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xC70C1016)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                                Tx(sub, 11.5f, 600, if (dQual?.sev == Sev.BLOCK) Color(0xFFFF453A) else Color(0xFFFFB020), maxLines = 3, align = TextAlign.Center)
                            }
                        }
                    }
                }
            }
            // watermark langsung (di atas peredup & kotak, di bawah peringatan servis)
            if (!train) Canvas(Modifier.fillMaxSize().padding(top = 88.dp, bottom = 14.dp)) {
                val t = now
                drawIntoCanvas { drawWatermark(it.nativeCanvas, size.width, size.height, vm.d, u, m, t, geo, true) }
            }
            // peringatan servis
            if (!train && u.cfg() && m.st != "ok") {
                Box(
                    Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 96.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (m.st == "er") Color(0xEBFF453A) else Color(0xEBFFD60A)).padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Tx(if (m.st == "er") "STOP — lakukan servis berkala" else "Mendekati servis · sisa ${f1(m.rem)} ${u.un()}", 12f, 700,
                        if (m.st == "er") Color.White else Color(0xFF111111), Modifier.fillMaxWidth(), ls = .3f, align = TextAlign.Center)
                }
            }
            // kilat layar saat jepret
            val flashA by androidx.compose.animation.core.animateFloatAsState(
                if (shutterFlash) .55f else 0f, androidx.compose.animation.core.tween(if (shutterFlash) 20 else 180), label = "flash"
            )
            if (flashA > 0.01f) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flashA)))
            // bar atas
            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    GBtn("gauge") { close(); vm.go(if (train) "train" else "home") }
                    if (train) Box(Modifier.clip(RoundedCornerShape(50)).background(hm().ac).padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Tx("MODE LATIH · ${vm.trainer.samples.size} sampel", 13f, 700, hm().on, maxLines = 1)
                    } else Box(Modifier.clip(RoundedCornerShape(50)).background(Color(0xA6000000)).border(1.dp, Color(0x73FFB020), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Tx(u.name.ifEmpty { "Unit belum diatur" }, 13f, 400, Color.White, maxLines = 1)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GBtn("up") { pick.launch("image/*") }
                        GBtn("flip") { facing = if (facing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(Color(0xA6000000)).clickable { askPerms() }.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        when (gpsState) {
                            "on" -> { Dot(Color(0xFF34C759)); Tx(if (TrustedClock.synced) "GPS aktif · waktu GPS" else "GPS aktif", 13f, 400, Color.White) }
                            "off" -> { Dot(Color(0xFFFF453A)); Tx("GPS mati · ketuk untuk coba lagi", 13f, 400, Color.White) }
                            else -> Tx("GPS: mencari…", 13f, 400, Color.White)
                        }
                    }
                    if (hasCam && !camErr) ToolChip("Zoom oto", autoZoom) { autoZoom = !autoZoom }
                    if (hasCam && !camErr) ToolChip("Kisi", grid) { grid = !grid }
                    if (hasCam && !camErr && vm.d.allows(Feature.CAM_LEVEL)) ToolChip("Level", showLevel) { showLevel = !showLevel }
                    if (hasCam && !camErr && vm.d.allows(Feature.CAM_FOCUS_LOCK)) ToolChip("Kunci AF/AE", aeLock) { toggleAeLock() }
                    if (hasCam && !camErr && !train) ToolChip("Jepret otomatis", autoShoot) { autoShoot = !autoShoot }
                }
                // batang ketajaman: makin penuh makin tajam; merah/kuning/hijau mengikuti ambang kualitas foto
                if (hasCam && !camErr && !train) {
                    val sh = (lastSharp ?: 0.0)
                    val frac = sharpFraction(sh)
                    val barCol = when {
                        sh < SHARP_BLOCK -> Color(0xFFFF453A)
                        sh < SHARP_WARN -> Color(0xFFFFB020)
                        else -> Color(0xFF34C759)
                    }
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(Color(0xA6000000)).padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Tx("Ketajaman", 11f, 600, Color.White, maxLines = 1)
                        Box(Modifier.width(120.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x55FFFFFF))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(barCol))
                        }
                    }
                }
                if (train) Tx(
                    "Variasikan jarak, sudut & cahaya", 11.5f, 600, Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xA6000000)).padding(horizontal = 12.dp, vertical = 6.dp), maxLines = 1
                )
                if (hasCam && !camErr) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val zst = cam?.cameraInfo?.zoomState?.value
                        val presets = zoomPresets(zst?.minZoomRatio ?: 1f, zst?.maxZoomRatio ?: 1f)
                        presets.forEach { p -> ToolChip(fmtZ(p), onPreset(zoom, p)) { setZoomPreset(p) } }
                        if (zoom > 1.05f && presets.none { onPreset(zoom, it) }) Tx(String.format(Locale.US, "%.1f×", zoom), 12.5f, 600, Color.White)
                        if (hasFlash) ToolChip(if (torch) "Senter: nyala" else "Senter", torch) { torch = !torch; cam?.cameraControl?.enableTorch(torch) }
                    }
                    if (evMax > evMin) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            ToolChip("EV −") { if (ev > evMin) { ev--; cam?.cameraControl?.setExposureCompensationIndex(ev) } }
                            // ketuk angka = kembalikan ke 0
                            Tx(
                                String.format(Locale.US, "%+.1f", ev * evStep), 12.5f, 600, Color.White,
                                modifier = Modifier.clickable { ev = 0; cam?.cameraControl?.setExposureCompensationIndex(0) }.padding(horizontal = 4.dp, vertical = 6.dp)
                            )
                            ToolChip("EV +") { if (ev < evMax) { ev++; cam?.cameraControl?.setExposureCompensationIndex(ev) } }
                        }
                    }
                }
            }
        }
        // kontrol bawah
        Row(Modifier.fillMaxWidth().background(Color.Black).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            if (train) CtlBtn("chk", "Selesai") { close(); vm.go("train") } else CtlBtn("img", "Galeri") { close(); vm.go("gal") }
            Box(
                Modifier.size(78.dp).alpha(if (capturing) .5f else 1f).clip(CircleShape).background(Color.White.copy(alpha = .18f)).border(4.dp, ringCol, CircleShape).clickable { snap() },
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(60.dp).clip(CircleShape).background(Color.White))
            }
            if (train) SampleCount(vm.trainer.samples.size, vm.trainer.samples.count { !it.auto }) else CtlBtn("set", "Atur") { close(); vm.go("set") }
        }
    }
    vm.frame?.let { f -> ReviewSheet(vm, f, geo, if (res != null && !res.blocked) lockRaw else "", onClose = { close() }) }
}

@Composable
private fun Text2(t: String, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xCC000000)).border(1.dp, Color(0x66FFB020), RoundedCornerShape(14.dp)).padding(14.dp)) {
        Tx(t, 13f, 400, Color.White, align = TextAlign.Center, lh = 1.5f)
    }
}

@Composable
private fun ToolChip(text: String, on: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (on) hm().ac else Color(0xA6000000)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) { Tx(text, 12.5f, 600, if (on) hm().on else Color.White, maxLines = 1) }
}

@Composable
private fun GBtn(icon: String, onClick: () -> Unit) {
    Box(Modifier.size(42.dp).clip(CircleShape).background(Color(0xA6000000)).clickable(onClick = onClick), contentAlignment = Alignment.Center) { HIcon(icon, 20.dp, Color.White) }
}

@Composable
private fun SampleCount(total: Int, manual: Int) {
    Column(Modifier.padding(8.dp).widthIn(min = 64.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Tx(total.toString(), 20f, 700, hm().ac)
        Tx("sampel · $manual manual", 11f, 400, Color(0xFF9AA3B2), maxLines = 1)
    }
}

@Composable
private fun CtlBtn(icon: String, label: String, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(8.dp).widthIn(min = 64.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HIcon(icon, 24.dp, hm().ac); Tx(label, 12f, 400, Color(0xFF9AA3B2))
    }
}
