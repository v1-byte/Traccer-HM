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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
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
    androidx.core.location.LocationManagerCompat.getCurrentLocation(lm, prov, null as android.os.CancellationSignal?, ContextCompat.getMainExecutor(ctx)) { loc ->
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
    // pengaturan kamera yang diingat antar-sesi (jepret otomatis sengaja tidak disimpan)
    val sp = remember { ctx.getSharedPreferences("cam_prefs", Context.MODE_PRIVATE) }
    val saved = remember { CamPrefs.decode(sp.getString("v", null)) }
    var hapticOn by remember { mutableStateOf(saved.haptic) }
    fun buzz(t: HapticFeedbackType = HapticFeedbackType.TextHandleMove) { if (hapticOn) haptic.performHapticFeedback(t) }
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
    var manualFrame by remember { mutableStateOf(ReadFrame.decode(sp.getString("frame", null))) }   // bingkai baca manual (null = angka dicari otomatis)
    var pointLocked by remember { mutableStateOf(false) }                     // satu titik dipertahankan sampai pengguna mengetuk titik baru
    // fokus & zoom otomatis
    var trackScore by remember { mutableDoubleStateOf(0.0) }                   // skor pencarian kotak yang sedang dilacak (histeresis)
    var forceSearchUntil by remember { mutableLongStateOf(0L) }                // setelah zoom otomatis: cari ulang penuh sampai waktu ini
    var lastPinchAt by remember { mutableLongStateOf(0L) }                     // cubit manual menahan zoom otomatis 10 dtk
    var autoZoom by remember { mutableStateOf(saved.autoZoom) }                         // cegah kamera mengubah framing tanpa diminta
    var afState by remember { mutableStateOf(AfState.IDLE) }
    var afFails by remember { mutableIntStateOf(0) }
    var lastSharp by remember { mutableStateOf<Double?>(null) }                // ketajaman area angka terakhir
    val policy = remember { FocusPolicy() }
    // alat bantu profesional: kisi, deteksi goyang, kilat layar + bunyi rana, animasi cincin fokus
    var gridMode by remember { mutableIntStateOf(saved.grid) }                // 0 mati, 1 sepertiga, 2 sepertiga + silang tengah
    var showHist by remember { mutableStateOf(saved.hist) }                    // histogram kecerahan langsung
    var hist by remember { mutableStateOf(FloatArray(0)) }
    var adv by remember { mutableStateOf<ExposureAdvice?>(null) }              // saran eksposur dari histogram
    var advHold by remember { mutableIntStateOf(0) }                           // saran baru tampil bila bertahan ≥ 2 siklus (anti-kedip)
    var evUi by remember { mutableStateOf(false) }                             // penggeser EV di samping cincin fokus
    var evUiTick by remember { mutableIntStateOf(0) }
    var evAnchor by remember { mutableStateOf(Offset.Zero) }
    var shaky by remember { mutableStateOf(false) }
    var roll by remember { mutableFloatStateOf(0f) }                           // kemiringan HP (derajat), 0 = tegak
    var showLevel by remember { mutableStateOf(saved.level) }                         // penunjuk level (Premium)
    var aeLock by remember { mutableStateOf(false) }                           // AF/AE terkunci (Platinum)
    var autoShoot by remember { mutableStateOf(false) }                        // jepret otomatis saat angka stabil & valid
    val shootGate = remember { AutoShootGate() }
    var shootProg by remember { mutableFloatStateOf(0f) }                      // kemajuan tahan-stabil sebelum jepret otomatis (0..1)
    var stamps by remember { mutableStateOf<List<Roi>>(emptyList()) }          // kotak stempel tanggal/jam di frame (piksel frame analisis)
    var shutterFlash by remember { mutableStateOf(false) }
    val ringAnim = remember { androidx.compose.animation.core.Animatable(1f) }
    val shutterSound = remember { MediaActionSound() }
    // fitur kamera tambahan: panel Pro, mode kilat foto, timer, bunyi rana
    var showPro by remember { mutableStateOf(false) }
    var flashMode by remember { mutableIntStateOf(saved.flash) }
    var timerSec by remember { mutableIntStateOf(saved.timer) }                          // 0 = tanpa timer, lainnya 3/5/10 detik
    var countdown by remember { mutableIntStateOf(0) }                         // hitung mundur yang sedang berjalan
    var timerStart by remember { mutableIntStateOf(0) }
    var soundOn by remember { mutableStateOf(saved.sound) }

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
    LaunchedEffect(manualFrame) { delay(400); sp.edit().putString("frame", manualFrame?.encode() ?: "").apply() }
    LaunchedEffect(focusTick) {
        if (focusTick > 0) { ringAnim.snapTo(1.45f); ringAnim.animateTo(1f, androidx.compose.animation.core.tween(240)) }
    }
    LaunchedEffect(shutterFlash) { if (shutterFlash) { delay(90); shutterFlash = false } }
    LaunchedEffect(gridMode, showHist, showLevel, soundOn, hapticOn, timerSec, flashMode, autoZoom) {
        sp.edit().putString("v", CamPrefs(gridMode, showHist, showLevel, soundOn, hapticOn, timerSec, flashMode, autoZoom).encode()).apply()
    }
    LaunchedEffect(evUiTick) { if (evUiTick > 0) { evUi = true; delay(3000); evUi = false } }
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
            // histogram kecil (96×72) untuk grafik langsung dan saran eksposur; saran baru tampil bila bertahan 2 siklus
            runCatching {
                val sm = Bitmap.createScaledBitmap(full, 96, 72, false)
                val px = IntArray(96 * 72); sm.getPixels(px, 0, 96, 0, 0, 96, 72)
                if (sm !== full) sm.recycle()
                val h = lumaHistogram(px, 32); val a = exposureAdvice(h)
                advHold = if (a.evSteps != 0 && a.evSteps * (adv?.evSteps ?: 0) > 0) advHold + 1 else if (a.evSteps != 0) 1 else 0
                adv = a; hist = h
            }
            cycleN++
            fun readRoi(roi: Roi, tracked: Boolean) {
                try {
                    val crop0 = cropSafe(full, roi)
                    val crop = if (meterProfile.id == "drum") drumCounted(crop0) else crop0   // drum 1/10 (merah) tidak ikut dibaca
                    val st = statsOf(crop); dQual = judgeQuality(st); lastSharp = st.sharp
                    fun finish(c0: String) {
                        val c = if (meterProfile.id == "drum") drumDigitsOnly(c0) else c0   // drum yang dihitung tidak punya titik
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
            val mf = manualFrame
            if (mf != null) { readRoi(rectToFrame(mf.toView(vw, vh), vw, vh, fw, fh, 0.04f), false); return@Analyzer }   // bingkai manual: tanpa pencarian angka
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
                            hit?.let { trackOf(it.l, it.t, it.r, it.b, fw, fh, it.tag) }, hit?.score ?: 0.0,
                            force = forced, switchRatio = if (pointLocked) Double.MAX_VALUE else 1.4
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
    fun triggerFocus(c: Camera, x: Float, y: Float, size: Float, cancelSec: Long, lockPoint: Boolean = false) {
        val pt = previewView.meteringPointFactory.createPoint(x, y, size)
        val builder = FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB)
        if (lockPoint) builder.disableAutoCancel() else builder.setAutoCancelDuration(cancelSec, TimeUnit.SECONDS)
        val act = builder.build()
        if (!c.cameraInfo.isFocusMeteringSupported(act)) { afState = AfState.IDLE; return }   // lensa fokus-tetap (mis. kamera depan)
        afState = AfState.SEARCHING
        val fut = c.cameraControl.startFocusAndMetering(act)
        fut.addListener({
            val ok = runCatching { fut.get().isFocusSuccessful }.getOrDefault(false)
            policy.onResult(ok); afFails = policy.failures
            afState = if (ok) AfState.LOCKED else AfState.FAILED
            if (lockPoint) pointLocked = ok
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
        buzz()
    }

    // Kompensasi eksposur (indeks langkah kamera); dipakai penggeser, tombol saran, dan atur ulang
    fun setEv(i: Int) { ev = i.coerceIn(evMin, evMax); runCatching { cam?.cameraControl?.setExposureCompensationIndex(ev) } }
    fun evFromFrac(f: Float): Int {
        val i = Math.round(evMin + f.coerceIn(0f, 1f) * (evMax - evMin))
        return if (evStep > 0f && Math.abs(i * evStep) < 0.12f) 0 else i          // magnet ke 0
    }
    val evStop = if (evStep > 0f) Math.round(1f / evStep).coerceAtLeast(1) else 1  // jumlah indeks per 1 EV
    fun evText(i: Int) = if (i == 0) "0.0" else String.format(Locale.US, "%+.1f", i * evStep)
    // tekan lama = kunci AF/AE di titik itu (Platinum); ketuk layar berikutnya melepas
    fun lockAt(p: Offset) {
        val c = cam ?: return
        if (!vm.d.allows(Feature.CAM_FOCUS_LOCK)) { vm.toast("Kunci AF/AE tersedia di paket Platinum"); return }
        val pt = previewView.meteringPointFactory.createPoint(p.x, p.y, 0.15f)
        val act = FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE).disableAutoCancel().build()
        runCatching { c.cameraControl.startFocusAndMetering(act) }
        focusPt = p; focusTick++; aeLock = true; pointLocked = false; afState = AfState.LOCKED
        vm.toast("AF/AE terkunci · ketuk layar untuk melepas")
        buzz(HapticFeedbackType.LongPress)
    }
    // Hidupkan/matikan bingkai baca manual. Pencarian otomatis (track) dilepas; fokus dipicu ulang oleh efek di bawah.
    fun setManualFrame(f: ReadFrame?) {
        manualFrame = f
        track = null; trackScore = 0.0; pointLocked = false; afState = AfState.IDLE; policy.reset()
        vm.toast(if (f != null) "Bingkai manual aktif · seret untuk geser, tarik sudut untuk ubah ukuran" else "Bingkai manual mati · angka dicari otomatis")
    }
    fun resetPro() {
        gridMode = 0; showHist = false; showLevel = true; soundOn = true; hapticOn = true; timerSec = 0
        flashMode = ImageCapture.FLASH_MODE_OFF; autoZoom = false; autoShoot = false; setEv(0)
        if (manualFrame != null) setManualFrame(null)
        if (aeLock) { runCatching { cam?.cameraControl?.cancelFocusAndMetering() }; aeLock = false; afState = AfState.IDLE }
        vm.toast("Pengaturan kamera dikembalikan")
    }

    // Bingkai manual: fokus + eksposur dikunci pada satu titik di tengah bingkai. Dipicu 0,6 dtk setelah bingkai berhenti
    // digeser/diubah ukurannya, lalu diulang bila foto mulai buram (paling cepat tiap 4 dtk).
    LaunchedEffect(cam, manualFrame, vw > 0) {
        val c = cam ?: return@LaunchedEffect
        val f = manualFrame ?: return@LaunchedEffect
        if (vw == 0) return@LaunchedEffect
        delay(600)
        var last = 0L
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val sharp = lastSharp
            if (!aeLock && (last == 0L || (now - last > 4000 && sharp != null && sharp < SHARP_WARN))) {
                last = now
                val b = f.toView(vw, vh)
                val cx = b[0] + b[2] / 2f; val cy = b[1] + b[3] / 2f
                runCatching { triggerFocus(c, cx, cy, f.focusSize, 3, lockPoint = true) }.onFailure { pointLocked = false; afState = AfState.FAILED }
                focusPt = Offset(cx, cy); focusTick++
            }
            delay(500)
        }
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
            if (!aeLock && !pointLocked && vw > 0 && policy.shouldTrigger(now, tr.cx, tr.cy, lastSharp, lastTapAt + 4000)) {
                policy.markTriggered(now, tr.cx, tr.cy)
                val b = trackInView(tr, vw, vh, dens)
                val cx = b[0] + b[2] / 2f; val cy = b[1] + b[3] / 2f
                runCatching { triggerFocus(c, cx, cy, (tr.b - tr.t).coerceIn(0.1f, 0.22f), 3, lockPoint = true) }.onFailure {
                    pointLocked = false; afState = AfState.FAILED
                }
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
            track = null; trackScore = 0.0; pointLocked = false; afState = AfState.IDLE; policy.reset()
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
        if (soundOn) runCatching { shutterSound.play(MediaActionSound.SHUTTER_CLICK) }
        val trSnap = track
        val mfSnap = manualFrame
        val stSnap = stamps; val fdSnap = frameDim
        buzz(HapticFeedbackType.LongPress)
        cap.takePicture(exec, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                var bm: Bitmap? = null; var q: Issue? = null; var roi: Roi? = null
                try {
                    bm = scaleDown(rotate(image.toBitmap(), image.imageInfo.rotationDegrees))
                    if (vw > 0) {
                        // bila angka sedang dilacak, area baca mengikuti angka itu (koordinat normal berlaku karena rasio sama)
                        val b0: Bitmap = bm
                        q = qualityOf(cropSafe(b0, if (mfSnap != null) rectToFrame(mfSnap.toView(vw, vh), vw, vh, b0.width, b0.height, 0.04f) else if (trSnap != null) roiFor(trSnap, b0.width, b0.height, 0.15f, 0.4f, stSnap, fdSnap.first, fdSnap.second) else boxToFrame(vw, vh, dens, b0.width, b0.height, 0.06f)))
                        roi = if (mfSnap != null) rectToFrame(mfSnap.toView(vw, vh), vw, vh, b0.width, b0.height, 0.15f) else if (trSnap != null) roiFor(trSnap, b0.width, b0.height, 0.3f, 0.8f, stSnap, fdSnap.first, fdSnap.second) else boxToFrame(vw, vh, dens, b0.width, b0.height, 0.25f)
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
    // tombol rana: bila timer aktif mulai hitung mundur; ketuk lagi saat hitung mundur = batal
    fun onShutter() {
        if (countdown > 0) { countdown = 0; timerStart = 0; return }
        if (timerSec > 0 && !capturing && hasCam && !camErr && capture != null) timerStart++ else snap()
    }
    val shutterNow = rememberUpdatedState({ onShutter() })
    DisposableEffect(Unit) { vm.shutter = { shutterNow.value.invoke() }; onDispose { vm.shutter = null } }
    LaunchedEffect(timerStart) {
        if (timerStart > 0) {
            var i = timerSec
            while (i > 0) { countdown = i; buzz(); delay(1000); i-- }
            countdown = 0
            snapNow.value.invoke()
        }
    }
    // mode kilat untuk foto (terpisah dari senter)
    LaunchedEffect(flashMode, capture) { capture?.flashMode = flashMode }
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
            res.blocked -> { dc = Color(0xFFFF453A); lbl = if (cur != null) "Tolak ${f1(res.value)} · di bawah ${f1(cur)}" else "Angka tidak valid" }
            tagFit == false -> { dc = Color(0xFFFFB020); lbl = if (tagName != null) "Ini $tagName, bukan $z · ketuk angka $z" else "Mungkin bukan $z · ketuk angka $z" }
            res.warned -> { dc = Color(0xFFFFB020); lbl = "Cek angka $z: ${f1(res.value)} · ketuk rana lalu setujui" }
            else -> { dc = Color(0xFF34C759); lbl = "Setujui angka $z: ${f1(res.value)} ✓" }
        }
    } else if (voter.last.isNotEmpty()) lbl = "${voter.last}…"
    else lbl = if (manualFrame != null) "Bingkai manual · seret untuk geser, tarik sudut untuk ubah ukuran" else if (track != null) (if (tagFit == true) "Label $z terdeteksi · menstabilkan…" else "Menstabilkan angka $z…") else if (train) "Arahkan ke angka meter · jepret, lalu ketik angka benar" else "Arahkan kamera ke angka $z · dicari otomatis"
    val afColor = when (afState) { AfState.LOCKED -> Color(0xFF34C759); AfState.SEARCHING -> Color(0xFFFFB020); AfState.FAILED -> Color(0xFFFF453A); else -> Color(0xFF8A93A3) }
    val afText = afHint(afState, afFails)
    // cincin rana ikut status bacaan: putih = belum ada, hijau = terkunci & valid, kuning = periksa/goyang, merah = ditolak
    val readyOk = res != null && !res.blocked && !res.warned && tagFit != false
    LaunchedEffect(lockRaw, readyOk) { if (readyOk) buzz() }
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
            val tNow = SystemClock.elapsedRealtime()
            val fire = shootGate.feed(tNow, readyOkState.value && vm.frame == null && !capturing, lockState.value, !shakyState.value)
            shootProg = if (fire) 0f else shootGate.progress(tNow)
            if (fire) snapNow.value.invoke()
            delay(150)
        }
        shootProg = 0f
    }

    // petunjuk khusus: pantulan (silau) dan layar berkontras rendah atau berpendar (mis. LCD oranye)
    val tip: String? = when {
        dQual?.msg?.contains("silau") == true -> "Miringkan HP ±15° agar pantulan keluar dari angka"
        dQual?.msg?.contains("Kontras rendah") == true -> "Layar berpendar? Ketuk angka untuk kunci AF/AE, lalu geser sudut sedikit"
        meterProfile.id == "drum" && res == null -> "Drum mekanik: bingkai drum gelap di kiri (digit HM); drum merah 1/10 tidak wajib masuk"
        else -> null
    }

    val advShow = adv?.takeIf { advHold >= 2 && it.evSteps != 0 && evMax > evMin && !train }
    val hasChips = advShow != null || aeLock || autoShoot || timerSec > 0 || ev != 0 || autoZoom || flashMode != ImageCapture.FLASH_MODE_OFF || manualFrame != null
    val modeN = activeModeCount(aeLock, autoShoot, timerSec, ev, flashMode != ImageCapture.FLASH_MODE_OFF)   // lencana di tombol Pro
    val buzzTap: () -> Unit = remember { { buzz() } }

    CompositionLocalProvider(LocalCamBuzz provides buzzTap) {
    Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { vw = it.width; vh = it.height }) {
            AndroidView({ previewView }, Modifier.fillMaxSize())
            // ketuk = fokus + eksposur, cubit = zoom
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(cam) {
                        detectTapGestures(onLongPress = { p -> lockAt(p) }) { p ->
                            val c = cam ?: return@detectTapGestures
                            runCatching { triggerFocus(c, p.x, p.y, 0.15f, 3) }.onFailure { afState = AfState.FAILED }
                            focusPt = p; focusTick++; evAnchor = p; evUiTick++
                            // ketukan juga memilih angka mana yang dicari (10 dtk): pencarian berikutnya memprioritaskan angka terdekat
                            lastTapAt = SystemClock.elapsedRealtime()
                            aeLock = false; pointLocked = false
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
            // kisi bidik: sepertiga, atau sepertiga + silang tengah untuk meluruskan angka
            if (gridMode > 0) Canvas(Modifier.fillMaxSize()) {
                val gc = Color.White.copy(alpha = .28f); val gw = 1.dp.toPx()
                for (k in 1..2) {
                    drawLine(gc, Offset(size.width * k / 3f, 0f), Offset(size.width * k / 3f, size.height), gw)
                    drawLine(gc, Offset(0f, size.height * k / 3f), Offset(size.width, size.height * k / 3f), gw)
                }
                if (gridMode == 2) {
                    val cc = Color.White.copy(alpha = .6f); val cx = size.width / 2f; val cy = size.height / 2f; val a = 9.dp.toPx()
                    drawLine(cc, Offset(cx - a, cy), Offset(cx + a, cy), gw * 1.5f); drawLine(cc, Offset(cx, cy - a), Offset(cx, cy + a), gw * 1.5f)
                }
            }
            // penunjuk level (Premium): garis tengah ikut kemiringan HP, hijau bila lurus; derajat tampil bila miring
            if (showLevel && !train && vm.d.allows(Feature.CAM_LEVEL)) {
                Canvas(Modifier.fillMaxSize()) {
                    val cx = size.width / 2f; val cy = size.height / 2f; val len = 60.dp.toPx()
                    val rad = Math.toRadians(-roll.toDouble())
                    val dx = (len * Math.cos(rad)).toFloat(); val dy = (len * Math.sin(rad)).toFloat()
                    val level = Math.abs(roll) < 1.5f
                    val lc = if (level) Color(0xFF34C759) else Color(0xFFFFB020)
                    // penanda tetap di kiri-kanan (garis acuan horizon); garis berwarna bergerak mengikuti HP
                    val ref = Color.White.copy(alpha = .45f); val g1 = len + 10.dp.toPx(); val g2 = len + 24.dp.toPx()
                    drawLine(ref, Offset(cx - g2, cy), Offset(cx - g1, cy), 2.dp.toPx(), StrokeCap.Round)
                    drawLine(ref, Offset(cx + g1, cy), Offset(cx + g2, cy), 2.dp.toPx(), StrokeCap.Round)
                    drawLine(lc, Offset(cx - dx, cy - dy), Offset(cx + dx, cy + dy), 2.dp.toPx(), StrokeCap.Round)
                    if (level) drawCircle(lc, 3.dp.toPx(), Offset(cx, cy))
                }
                if (Math.abs(roll) >= 1.5f) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Tx(
                        rollLabel(roll), 11f, 700, Color(0xFFFFB020),
                        Modifier.offset(y = 22.dp).clip(RoundedCornerShape(50)).background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 2.dp), maxLines = 1
                    )
                }
            }
            // detektor [ ]
            if (vm.d.det && vw > 0) {
                val tb = track
                val mfNow = manualFrame
                val tgt = if (mfNow != null) mfNow.toView(vw, vh) else if (tb != null) trackInView(tb, vw, vh, dens) else boxInView(vw, vh, dens)
                val spec = if (mfNow != null) androidx.compose.animation.core.snap<Float>() else androidx.compose.animation.core.tween<Float>(220)
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
                    val sub = listOfNotNull(
                        if (pointLocked && res != null) "Titik fokus terkunci · ketuk rana untuk foto, lalu setujui angka" else null,
                        if (shaky) "Tahan HP lebih stabil" else null, dQual?.msg, tip, afText.ifEmpty { null }
                    ).joinToString("\n")
                    if (sub.isNotEmpty()) {
                        Box(Modifier.align(Alignment.BottomCenter).wrapContentSize(Alignment.BottomCenter, unbounded = true).offset(y = 34.dp)) {
                            Box(Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xC70C1016)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                                Tx(sub, 11.5f, 600, if (dQual?.sev == Sev.BLOCK) Color(0xFFFF453A) else Color(0xFFFFB020), maxLines = 3, align = TextAlign.Center)
                            }
                        }
                    }
                }
                manualFrame?.let { mf ->
                    FrameEditor(mf, vw, vh, dens, { manualFrame = it }) {
                        val c = cam
                        if (c != null) {
                            val b = mf.toView(vw, vh); val cx = b[0] + b[2] / 2f; val cy = b[1] + b[3] / 2f
                            runCatching { triggerFocus(c, cx, cy, mf.focusSize, 3, lockPoint = true) }.onFailure { pointLocked = false; afState = AfState.FAILED }
                            focusPt = Offset(cx, cy); focusTick++; buzz()
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
            if (!train && !showPro && u.cfg() && m.st != "ok") {
                Box(
                    Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = if (hasChips) 104.dp else 64.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
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
            // hitung mundur timer
            if (countdown > 0) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tx(countdown.toString(), 110f, 700, Color.White.copy(alpha = .92f))
                    Tx("Ketuk tombol rana untuk batal", 12f, 600, Color.White,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xA6000000)).padding(horizontal = 12.dp, vertical = 6.dp), maxLines = 1)
                }
            }
            // penggeser eksposur di samping cincin fokus (muncul 3 dtk setelah ketuk): seret naik/turun, magnet di 0
            if (evUi && evMax > evMin && vw > 0) Column(
                Modifier.offset {
                    IntOffset(
                        (evAnchor.x + 46.dp.toPx()).coerceIn(8.dp.toPx(), (vw - 44.dp.toPx()).coerceAtLeast(8.dp.toPx())).toInt(),
                        (evAnchor.y - 70.dp.toPx()).coerceIn(90.dp.toPx(), (vh - 230.dp.toPx()).coerceAtLeast(90.dp.toPx())).toInt()
                    )
                }.width(36.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                HIcon("ev", 20.dp, hm().ac)
                ValueBar(
                    (ev - evMin).toFloat() / (evMax - evMin), (0 - evMin).toFloat() / (evMax - evMin), true,
                    Modifier.width(36.dp).height(112.dp)
                ) { f -> setEv(evFromFrac(f)); evUiTick++ }
                Tx(evText(ev), 11f, 700, hm().ac, maxLines = 1)
            }
            // bar atas: satu baris kendali utama + satu baris status (GPS & ketajaman)
            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GBtn("gauge", if (train) "Kembali ke pusat latih" else "Kembali ke beranda") { close(); vm.go(if (train) "train" else "home") }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (train) Box(Modifier.clip(RoundedCornerShape(50)).background(hm().ac).padding(horizontal = 14.dp, vertical = 8.dp)) {
                            Tx("MODE LATIH · ${vm.trainer.samples.size} sampel", 13f, 700, hm().on, maxLines = 1)
                        } else Box(Modifier.clip(RoundedCornerShape(50)).background(Color(0xA6000000)).border(1.dp, Color(0x73FFB020), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 8.dp)) {
                            Tx(u.name.ifEmpty { "Unit belum diatur" }, 13f, 400, Color.White, maxLines = 1)
                        }
                    }
                    GBtn("up", "Unggah foto dari galeri") { pick.launch("image/*") }
                    GBtn("flip", "Balik kamera depan/belakang") { facing = if (facing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.press { askPerms() }.clip(RoundedCornerShape(50)).background(Color(0xA6000000)).padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        when (gpsState) {
                            "on" -> { Dot(Color(0xFF34C759)); Tx(if (TrustedClock.synced) "GPS · waktu GPS" else "GPS aktif", 11.5f, 500, Color.White, maxLines = 1) }
                            "off" -> { Dot(Color(0xFFFF453A)); Tx("GPS mati · ketuk", 11.5f, 500, Color.White, maxLines = 1) }
                            else -> { Dot(Color(0xFFFFB020)); Tx("Mencari GPS…", 11.5f, 500, Color.White, maxLines = 1) }
                        }
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
                            Tx("Tajam", 11.5f, 500, Color.White, maxLines = 1)
                            Box(Modifier.width(64.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x55FFFFFF))) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(barCol))
                            }
                        }
                    }
                }
                if (train) Tx(
                    "Variasikan jarak, sudut & cahaya", 11.5f, 600, Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xA6000000)).padding(horizontal = 12.dp, vertical = 6.dp), maxLines = 1
                )
                if (showHist && hist.isNotEmpty() && hasCam && !camErr) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { HistView(hist) }
            }
            // rel kanan: satu kapsul kaca berisi pintasan harian (ikon + status kecil)
            if (hasCam && !camErr) Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).clip(RoundedCornerShape(28.dp)).background(Color(0x99000000))
                    .border(1.dp, CamUi.rim, RoundedCornerShape(28.dp)).padding(vertical = 6.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (hasFlash) {
                    QuickBtn("torch", if (torch) "Nyala" else "Senter", torch, "Senter") { torch = !torch; cam?.cameraControl?.enableTorch(torch) }
                    QuickBtn(
                        "flash",
                        when (flashMode) { ImageCapture.FLASH_MODE_AUTO -> "Oto"; ImageCapture.FLASH_MODE_ON -> "Nyala"; else -> "Mati" },
                        flashMode != ImageCapture.FLASH_MODE_OFF, "Kilat foto"
                    ) {
                        flashMode = when (flashMode) {
                            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                            else -> ImageCapture.FLASH_MODE_OFF
                        }
                    }
                    Box(Modifier.width(26.dp).height(1.dp).background(Color(0x26FFFFFF)))
                }
                QuickBtn("timer", if (timerSec == 0) "Timer" else "${timerSec} dtk", timerSec > 0, "Timer") { timerSec = nextTimer(timerSec) }
                QuickBtn("grid", if (gridMode == 0) "Kisi" else gridLabel(gridMode), gridMode > 0, "Kisi bidik") { gridMode = nextGrid(gridMode) }
                QuickBtn("hist", "Histo", showHist, "Histogram") { showHist = !showHist }
            }
            // dok bawah: chip mode aktif / panel Pro + zoom
            if (hasCam && !camErr) Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (showPro) {
                    val allowLock = vm.d.allows(Feature.CAM_FOCUS_LOCK); val allowLevel = vm.d.allows(Feature.CAM_LEVEL)
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xEB0C1016)).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(20.dp)).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x40FFFFFF)))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Tx("Kamera Pro", 15f, 700, Color.White, Modifier.weight(1f), maxLines = 1)
                            ToolChip("Atur ulang") { resetPro() }
                            Box(Modifier.size(40.dp).camTap("Tutup panel Pro") { showPro = false }.clip(CircleShape).background(Color(0x1AFFFFFF)), contentAlignment = Alignment.Center) { HIcon("chev", 18.dp, Color.White) }
                        }
                        Column(Modifier.heightIn(max = 262.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SectionLabel("ASISTEN")
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ProBtn("zoomauto", "Zoom oto", autoZoom, Modifier.weight(1f)) { autoZoom = !autoZoom }
                                if (!train) ProBtn("auto", "Jepret oto", autoShoot, Modifier.weight(1f)) { autoShoot = !autoShoot }
                                ProBtn("lock", "Kunci AF/AE", aeLock, Modifier.weight(1f), locked = !allowLock, tag = Feature.CAM_FOCUS_LOCK.min.label) {
                                    if (allowLock) toggleAeLock() else vm.toast("${Feature.CAM_FOCUS_LOCK.label} · paket ${Feature.CAM_FOCUS_LOCK.min.label}")
                                }
                            }
                            SectionLabel("TAMPILAN")
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ProBtn("hist", "Histogram", showHist, Modifier.weight(1f)) { showHist = !showHist }
                                if (!train) ProBtn("level", "Level", showLevel && allowLevel, Modifier.weight(1f), locked = !allowLevel, tag = Feature.CAM_LEVEL.min.label) {
                                    if (allowLevel) showLevel = !showLevel else vm.toast("${Feature.CAM_LEVEL.label} · paket ${Feature.CAM_LEVEL.min.label}")
                                }
                                ProBtn("sound", "Suara", soundOn, Modifier.weight(1f)) { soundOn = !soundOn }
                                ProBtn("haptic", "Getar", hapticOn, Modifier.weight(1f)) { hapticOn = !hapticOn }
                            }
                            CamSeg(listOf("0" to "Kisi mati", "1" to "Kisi 3×3", "2" to "Kisi 3×3+"), gridMode.toString()) { gridMode = it.toInt() }
                            SectionLabel("ZOOM")
                            val zs = cam?.cameraInfo?.zoomState?.value
                            val zMin = zs?.minZoomRatio ?: 1f; val zMax = zs?.maxZoomRatio ?: 1f
                            if (zMax > zMin + 0.01f) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Tx("Zoom halus", 12.5f, 600, Color.White, Modifier.weight(1f), maxLines = 1)
                                    Tx(
                                        fmtZoom(zoom), 13f, 700, if (zoom > 1.05f) hm().ac else Color.White,
                                        Modifier.press(haptic = false) { if (zMin <= 1f) { buzz(); setZoomPreset(1f) } }.clip(RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp), maxLines = 1
                                    )
                                }
                                ValueBar(
                                    zoomToFrac(zoom, zMin, zMax), if (zMin <= 1f) zoomToFrac(1f, zMin, zMax) else 0f, false,
                                    Modifier.fillMaxWidth().height(34.dp)
                                ) { f ->
                                    val nz = zoomFromFrac(f, zMin, zMax)
                                    if (Math.abs(nz - zoom) > 0.005f) { if (nz == 1f || nz == 2f || nz == 4f) buzz(); setZoomPreset(nz) }
                                }
                            } else Tx("Lensa ini tidak mendukung zoom.", 12f, 400, CamUi.dim)
                            SectionLabel("BINGKAI BACA")
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ProBtn("frame", "Bingkai manual", manualFrame != null, Modifier.weight(1f)) { setManualFrame(if (manualFrame == null) ReadFrame.DEFAULT else null) }
                                Spacer(Modifier.weight(2f))
                            }
                            val mfp = manualFrame
                            if (mfp == null) Tx("Aktifkan untuk membaca satu area saja: fokus dan eksposur dikunci di tengah bingkai, lebar dan tinggi bisa diatur.", 11.5f, 400, CamUi.dim)
                            else {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Tx("Lebar", 12.5f, 600, Color.White, Modifier.weight(1f), maxLines = 1)
                                    Tx("${Math.round(mfp.w * 100)}%", 13f, 700, hm().ac, maxLines = 1)
                                }
                                ValueBar(((mfp.w - ReadFrame.MIN_W) / (ReadFrame.MAX_W - ReadFrame.MIN_W)).coerceIn(0f, 1f), 0f, false, Modifier.fillMaxWidth().height(34.dp)) { f ->
                                    manualFrame = mfp.withSize(ReadFrame.MIN_W + f.coerceIn(0f, 1f) * (ReadFrame.MAX_W - ReadFrame.MIN_W), mfp.h)
                                }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Tx("Tinggi", 12.5f, 600, Color.White, Modifier.weight(1f), maxLines = 1)
                                    Tx("${Math.round(mfp.h * 100)}%", 13f, 700, hm().ac, maxLines = 1)
                                }
                                ValueBar(((mfp.h - ReadFrame.MIN_H) / (ReadFrame.MAX_H - ReadFrame.MIN_H)).coerceIn(0f, 1f), 0f, false, Modifier.fillMaxWidth().height(34.dp)) { f ->
                                    manualFrame = mfp.withSize(mfp.w, ReadFrame.MIN_H + f.coerceIn(0f, 1f) * (ReadFrame.MAX_H - ReadFrame.MIN_H))
                                }
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ToolChip("Baris panjang", "Bingkai baris angka panjang") { manualFrame = ReadFrame.WIDE.copy(cx = mfp.cx, cy = mfp.cy).clamped() }
                                    ToolChip("Kotak LCD", "Bingkai layar kotak") { manualFrame = ReadFrame.BLOCK.copy(cx = mfp.cx, cy = mfp.cy).clamped() }
                                    ToolChip("Tengah", "Pindahkan bingkai ke tengah") { manualFrame = mfp.copy(cx = 0.5f, cy = 0.42f).clamped() }
                                }
                            }
                            SectionLabel("EKSPOSUR")
                            if (evMax > evMin) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Tx("Kompensasi", 12.5f, 600, Color.White, Modifier.weight(1f), maxLines = 1)
                                    Tx(
                                        evText(ev) + " EV", 13f, 700, if (ev == 0) Color.White else hm().ac,
                                        Modifier.press { setEv(0) }.clip(RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp), maxLines = 1
                                    )
                                }
                                ValueBar(
                                    (ev - evMin).toFloat() / (evMax - evMin), (0 - evMin).toFloat() / (evMax - evMin), false,
                                    Modifier.fillMaxWidth().height(34.dp)
                                ) { f -> setEv(evFromFrac(f)) }
                            } else Tx("Kamera ini tidak mendukung kompensasi eksposur.", 12f, 400, Color(0xFF9AA3B2))
                            SectionLabel("PEMOTRETAN")
                            CamSeg(listOf("0" to "Tanpa timer", "3" to "3 dtk", "5" to "5 dtk", "10" to "10 dtk"), timerSec.toString()) { timerSec = it.toInt() }
                            if (hasFlash) CamSeg(
                                listOf(ImageCapture.FLASH_MODE_OFF.toString() to "Kilat mati", ImageCapture.FLASH_MODE_AUTO.toString() to "Kilat oto", ImageCapture.FLASH_MODE_ON.toString() to "Kilat nyala"),
                                flashMode.toString()
                            ) { flashMode = it.toInt() }
                            Tx("Tip: tekan lama layar = kunci AF/AE di titik itu · ketuk layar = fokus & penggeser EV", 11f, 400, Color(0xFF9AA3B2), lh = 1.4f)
                        }
                    }
                } else if (hasChips) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (advShow != null) ModeChip((advShow.msg ?: "") + " · ketuk", Color(0xFFFFB020)) { setEv(clampEv(ev, advShow.evSteps * evStop, evMin, evMax)) }
                        if (aeLock) ModeChip("AF/AE TERKUNCI", Color(0xFF34C759)) { toggleAeLock() }
                        if (autoShoot) ModeChip("JEPRET OTO", hm().ac) { autoShoot = false }
                        if (timerSec > 0) ModeChip("TIMER ${timerSec} DTK", Color.White) { timerSec = 0 }
                        if (ev != 0) ModeChip("EV ${evText(ev)}", Color.White) { setEv(0) }
                        if (manualFrame != null) ModeChip("BINGKAI MANUAL", Color.White) { setManualFrame(null) }
                        if (autoZoom) ModeChip("ZOOM OTO", Color.White) { autoZoom = false }
                        if (flashMode != ImageCapture.FLASH_MODE_OFF) ModeChip(if (flashMode == ImageCapture.FLASH_MODE_ON) "KILAT NYALA" else "KILAT OTO", Color.White) { flashMode = ImageCapture.FLASH_MODE_OFF }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(Color(0xA6000000)).padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically
                    ) {
                        val zst = cam?.cameraInfo?.zoomState?.value
                        val presets = zoomPresets(zst?.minZoomRatio ?: 1f, zst?.maxZoomRatio ?: 1f)
                        presets.forEach { p -> SegChip(fmtZ(p), onPreset(zoom, p)) { setZoomPreset(p) } }
                        if (zoom > 1.05f && presets.none { onPreset(zoom, it) }) SegChip(String.format(Locale.US, "%.1f×", zoom), true) { }
                    }
                    Box {
                        Row(
                            Modifier.height(44.dp).camTap(if (showPro) "Tutup panel Pro" else "Buka panel Pro") { showPro = !showPro }.clip(RoundedCornerShape(50))
                                .background(if (showPro) hm().ac else CamUi.glass).border(1.dp, CamUi.rim, RoundedCornerShape(50)).padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            HIcon("sliders", 18.dp, if (showPro) hm().on else Color.White)
                            Tx("Pro", 13f, 700, if (showPro) hm().on else Color.White, maxLines = 1)
                        }
                        if (modeN > 0 && !showPro) Box(
                            Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp).size(18.dp).clip(CircleShape)
                                .background(hm().ac).border(1.5.dp, Color.Black, CircleShape),
                            contentAlignment = Alignment.Center
                        ) { Tx(modeN.toString(), 10f, 800, hm().on, maxLines = 1) }
                    }
                }
            }
        }
        // kontrol bawah: tiga slot sama lebar supaya tombol rana persis di tengah
        Row(
            Modifier.fillMaxWidth().background(Color.Black).padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (train) CtlBtn("chk", "Selesai") { close(); vm.go("train") } else CtlBtn("img", "Galeri") { close(); vm.go("gal") }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                ShutterButton(ringCol, capturing, countdown, autoShoot, shootProg) { shutterNow.value.invoke() }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (train) SampleCount(vm.trainer.samples.size, vm.trainer.samples.count { !it.auto }) else CtlBtn("set", "Atur") { close(); vm.go("set") }
            }
        }
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

/** Token tampilan kamera: semua tombol memakai bahan kaca, tepi, dan ukuran sentuh yang sama supaya konsisten. */
private object CamUi {
    val glass = Color(0xA6000000)
    val dim = Color(0xFF9AA3B2)
    val warn = Color(0xFFFFB020)
    val tile = Color(0x14FFFFFF)
    val tileLine = Color(0x22FFFFFF)
    val touch = 48.dp                       // ukuran sentuh minimum tiap tombol (pedoman aksesibilitas 48dp)
    /** Tepi kaca: terang di atas, memudar ke bawah. */
    val rim: Brush get() = Brush.verticalGradient(listOf(Color(0x59FFFFFF), Color(0x14FFFFFF)))
}

/** Getar halus tiap ketukan tombol kamera; diisi dari CameraScreen dan mengikuti opsi Getar di panel Pro. */
private val LocalCamBuzz = compositionLocalOf<() -> Unit> { {} }

/** Dasar semua tombol kamera: efek tekan, getar halus, dan semantik tombol + deskripsi untuk pembaca layar. */
@Composable
private fun Modifier.camTap(desc: String, enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val buzz = LocalCamBuzz.current
    return this.semantics { contentDescription = desc; role = Role.Button }.press(enabled, haptic = false) { buzz(); onClick() }
}

@Composable
private fun ToolChip(text: String, desc: String = text, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 36.dp).camTap(desc, onClick = onClick).clip(RoundedCornerShape(50)).background(Color(0x1AFFFFFF))
            .border(1.dp, CamUi.tileLine, RoundedCornerShape(50)).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) { Tx(text, 12.5f, 600, Color.White, maxLines = 1) }
}

@Composable
private fun GBtn(icon: String, desc: String, onClick: () -> Unit) {
    Box(
        Modifier.size(CamUi.touch).camTap(desc, onClick = onClick).clip(CircleShape).background(CamUi.glass).border(1.dp, CamUi.rim, CircleShape),
        contentAlignment = Alignment.Center
    ) { HIcon(icon, 21.dp, Color.White) }
}

@Composable
private fun SampleCount(total: Int, manual: Int) {
    Column(Modifier.padding(8.dp).widthIn(min = 64.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Tx(total.toString(), 20f, 700, hm().ac)
        Tx("sampel · $manual manual", 11f, 400, CamUi.dim, maxLines = 1)
    }
}

@Composable
private fun CtlBtn(icon: String, label: String, onClick: () -> Unit) {
    Column(
        Modifier.widthIn(min = 64.dp).camTap(label, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            Modifier.size(54.dp).clip(CircleShape).background(Color(0xFF1B1F27)).border(1.dp, CamUi.rim, CircleShape),
            contentAlignment = Alignment.Center
        ) { HIcon(icon, 24.dp, hm().ac) }
        Tx(label, 11.5f, 500, CamUi.dim, maxLines = 1)
    }
}

/** Segmen zoom di dalam pil: aktif = warna aksen, tidak aktif = transparan; lebar/tinggi sentuh dijaga. */
@Composable
private fun SegChip(text: String, on: Boolean, onClick: () -> Unit) {
    val buzz = LocalCamBuzz.current
    Box(
        Modifier.defaultMinSize(minWidth = 44.dp, minHeight = 36.dp).press(haptic = false) { if (!on) buzz(); onClick() }.clip(RoundedCornerShape(50))
            .background(if (on) hm().ac else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) { Tx(text, 12.5f, if (on) 700 else 600, if (on) hm().on else Color.White, maxLines = 1) }
}

/** Tombol di kapsul kanan: ikon + keterangan status kecil; aktif = lingkar aksen berlatar tipis (tetap terbaca di bawah matahari). */
@Composable
private fun QuickBtn(icon: String, label: String, on: Boolean, desc: String, onClick: () -> Unit) {
    val ac = hm().ac
    Column(
        Modifier.widthIn(min = CamUi.touch).camTap(desc + if (on) ", aktif" else "", onClick = onClick).padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(if (on) ac.copy(alpha = .22f) else Color.Transparent)
                .then(if (on) Modifier.border(1.5.dp, ac, CircleShape) else Modifier),
            contentAlignment = Alignment.Center
        ) { HIcon(icon, 22.dp, if (on) ac else Color.White) }
        Tx(label, 10f, 600, if (on) ac else Color.White.copy(alpha = .85f), maxLines = 1, align = TextAlign.Center)
    }
}

/** Tombol kotak di panel Pro; [locked] = fitur paket lebih tinggi (redup, lencana paket, ketukan menampilkan info). Titik aksen = aktif. */
@Composable
private fun ProBtn(icon: String, label: String, on: Boolean, modifier: Modifier, locked: Boolean = false, tag: String? = null, onClick: () -> Unit) {
    val ac = hm().ac; val act = on && !locked
    val fg = if (act) ac else Color.White
    val state = if (locked) ", terkunci paket ${tag ?: ""}" else if (on) ", aktif" else ", mati"
    Box(
        modifier.camTap(label + state, onClick = onClick).heightIn(min = 68.dp).alpha(if (locked) .55f else 1f)
            .clip(RoundedCornerShape(14.dp)).background(if (act) ac.copy(alpha = .16f) else CamUi.tile)
            .border(1.dp, if (act) ac.copy(alpha = .65f) else CamUi.tileLine, RoundedCornerShape(14.dp))
    ) {
        Column(
            Modifier.align(Alignment.Center).padding(vertical = 8.dp, horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            HIcon(if (locked) "lock" else icon, 22.dp, fg)
            Tx(label, 10.5f, 600, fg, maxLines = 1, align = TextAlign.Center)
            if (locked && tag != null) Tx(
                tag, 9f, 800, Color(0xFF111111),
                Modifier.clip(RoundedCornerShape(50)).background(CamUi.warn).padding(horizontal = 6.dp, vertical = 1.dp),
                maxLines = 1, align = TextAlign.Center
            )
        }
        if (act) Box(Modifier.align(Alignment.TopEnd).padding(7.dp).size(6.dp).clip(CircleShape).background(ac))
    }
}

/** Tombol rana: cincin berwarna status, bagian dalam mengecil saat ditekan (rasa tombol fisik), hitung mundur / tanda A di tengah. */
@Composable
private fun ShutterButton(ring: Color, busy: Boolean, countdown: Int, auto: Boolean, progress: Float, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val down by src.collectIsPressedAsState()
    val inner by animateFloatAsState(if (down) .86f else 1f, spring(dampingRatio = .55f, stiffness = 600f), label = "shutterIn")
    val outer by animateFloatAsState(if (down) .96f else 1f, spring(dampingRatio = .6f, stiffness = 500f), label = "shutterOut")
    val prog by animateFloatAsState(progress, androidx.compose.animation.core.tween(150, easing = androidx.compose.animation.core.LinearEasing), label = "shutterProg")
    val desc = if (countdown > 0) "Batalkan hitung mundur" else "Ambil foto"
    Box(
        Modifier.size(80.dp).graphicsLayer { scaleX = outer; scaleY = outer }.alpha(if (busy) .5f else 1f)
            .semantics { contentDescription = desc; role = Role.Button }
            .clip(CircleShape).background(Color.White.copy(alpha = .14f)).border(4.dp, ring, CircleShape)
            .clickable(src, null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        // cincin kemajuan jepret otomatis: terisi searah jarum jam selama angka valid, stabil, dan HP tenang
        if (auto && prog > 0.01f) Canvas(Modifier.matchParentSize()) {
            val sw = 4.dp.toPx()
            drawArc(
                Color.White, -90f, 360f * prog.coerceIn(0f, 1f), false,
                topLeft = Offset(sw / 2f, sw / 2f), size = Size(size.width - sw, size.height - sw),
                style = Stroke(sw, cap = StrokeCap.Round)
            )
        }
        Box(
            Modifier.size(62.dp).graphicsLayer { scaleX = inner; scaleY = inner }.clip(CircleShape).background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            if (countdown > 0) Tx(countdown.toString(), 26f, 800, Color(0xFF111111))
            else if (auto) Tx("A", 22f, 800, Color(0xFF111111))
        }
    }
}

/** Judul bagian panel Pro: teks kecil kapital + garis tipis ke kanan. */
@Composable
private fun SectionLabel(t: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tx(t, 10.5f, 700, CamUi.dim, ls = 1f, maxLines = 1)
        Box(Modifier.weight(1f).height(1.dp).background(Color(0x1FFFFFFF)))
    }
}

/** Segmen pilihan tunggal bergaya kamera (teks putih di atas panel gelap), dengan getar halus saat ganti pilihan. */
@Composable
private fun CamSeg(items: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    val buzz = LocalCamBuzz.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x1AFFFFFF)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEach { (k, l) ->
            val on = k == selected
            Box(
                Modifier.weight(1f).heightIn(min = 40.dp).press(haptic = false) { if (!on) buzz(); onSelect(k) }.clip(RoundedCornerShape(10.dp)).background(if (on) hm().ac else Color.Transparent).padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) { Tx(l, 12f, if (on) 700 else 500, if (on) hm().on else Color.White, maxLines = 1, align = TextAlign.Center) }
        }
    }
}

/** Pegangan bingkai baca manual: seret isi untuk menggeser, seret lingkaran di sudut untuk mengubah lebar/tinggi, ketuk untuk fokus ulang. */
@Composable
private fun FrameEditor(frame: ReadFrame, vw: Int, vh: Int, dens: Float, onFrame: (ReadFrame) -> Unit, onRefocus: () -> Unit) {
    val cur = rememberUpdatedState(frame); val send = rememberUpdatedState(onFrame); val refocus = rememberUpdatedState(onRefocus)
    val b = frame.toView(vw, vh)
    val ac = hm().ac
    Box(
        Modifier.absoluteOffset((b[0] / dens).dp, (b[1] / dens).dp).size((b[2] / dens).dp, (b[3] / dens).dp)
            .pointerInput(vw, vh) { detectDragGestures { ch, d -> ch.consume(); send.value(cur.value.moved(d.x / vw, d.y / vh)) } }
            .pointerInput(Unit) { detectTapGestures { refocus.value() } }
    )
    val hit = 44.dp
    for (corner in 0..3) {
        val px = if (corner == 0 || corner == 2) b[0] else b[0] + b[2]
        val py = if (corner == 0 || corner == 1) b[1] else b[1] + b[3]
        Box(
            Modifier.absoluteOffset((px / dens).dp - hit / 2, (py / dens).dp - hit / 2).size(hit)
                .pointerInput(corner, vw, vh) { detectDragGestures { ch, d -> ch.consume(); send.value(cur.value.dragCorner(corner, d.x / vw, d.y / vh)) } },
            contentAlignment = Alignment.Center
        ) { Box(Modifier.size(14.dp).clip(CircleShape).background(ac).border(2.dp, Color.White, CircleShape)) }
    }
}

/** Chip status mode aktif di atas baris zoom; ketuk = matikan mode itu. */
@Composable
private fun ModeChip(text: String, c: Color, onClick: (() -> Unit)? = null) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(Color(0xB3000000)).border(1.dp, c.copy(alpha = .55f), RoundedCornerShape(50))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 10.dp, vertical = 5.dp)
    ) { Tx(text, 11f, 700, c, maxLines = 1) }
}

/** Penggeser nilai (vertikal di samping cincin fokus, horizontal di panel): ketuk atau seret; garis berwarna dari titik nol ke nilai. */
@Composable
private fun ValueBar(frac: Float, zeroFrac: Float, vertical: Boolean, modifier: Modifier, onFrac: (Float) -> Unit) {
    val ac = hm().ac; val cb = rememberUpdatedState(onFrac)
    Canvas(
        modifier
            .pointerInput(vertical) { detectTapGestures { p -> cb.value(if (vertical) 1f - p.y / size.height else p.x / size.width) } }
            .pointerInput(vertical) { detectDragGestures { ch, _ -> ch.consume(); cb.value(if (vertical) 1f - ch.position.y / size.height else ch.position.x / size.width) } }
    ) {
        val r = 8.dp.toPx(); val tw = 3.dp.toPx(); val base = Color.White.copy(alpha = .35f)
        val f = frac.coerceIn(0f, 1f); val z = zeroFrac.coerceIn(0f, 1f)
        if (vertical) {
            val x = size.width / 2f; val top = r; val bot = size.height - r
            fun yAt(v: Float) = bot - (bot - top) * v
            drawLine(base, Offset(x, top), Offset(x, bot), tw, StrokeCap.Round)
            drawLine(ac, Offset(x, yAt(z)), Offset(x, yAt(f)), tw, StrokeCap.Round)
            drawLine(Color.White, Offset(x - 7.dp.toPx(), yAt(z)), Offset(x + 7.dp.toPx(), yAt(z)), 1.5.dp.toPx())
            drawCircle(ac, r, Offset(x, yAt(f)))
        } else {
            val y = size.height / 2f; val l = r; val rr = size.width - r
            fun xAt(v: Float) = l + (rr - l) * v
            drawLine(base, Offset(l, y), Offset(rr, y), tw, StrokeCap.Round)
            drawLine(ac, Offset(xAt(z), y), Offset(xAt(f), y), tw, StrokeCap.Round)
            drawLine(Color.White, Offset(xAt(z), y - 7.dp.toPx()), Offset(xAt(z), y + 7.dp.toPx()), 1.5.dp.toPx())
            drawCircle(ac, r, Offset(xAt(f), y))
        }
    }
}

/** Histogram kecerahan langsung: ujung kanan merah bila banyak piksel terbakar (silau/LCD terlalu terang). */
@Composable
private fun HistView(h: FloatArray) {
    Box(Modifier.size(92.dp, 46.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x99000000)).border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(10.dp)).padding(6.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            if (h.isEmpty()) return@Canvas
            val n = h.size; val bw = size.width / n
            val peak = h.sortedDescending().let { if (it.size > 1) it[1] else it[0] }.coerceAtLeast(0.001f)
            for (i in 0 until n) {
                val bh = (h[i] / peak).coerceIn(0f, 1f) * size.height
                val c = when {
                    i == n - 1 && h[i] > .06f -> Color(0xFFFF453A)
                    i == 0 && h[i] > .3f -> Color(0xFFFFB020)
                    else -> Color.White.copy(alpha = .8f)
                }
                drawRect(c, Offset(i * bw, size.height - bh), Size((bw - 0.5f).coerceAtLeast(0.5f), bh))
            }
        }
    }
}
