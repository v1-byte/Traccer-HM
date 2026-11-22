package com.frostbyte.hmtracker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.frostbyte.hmtracker.AppData
import com.frostbyte.hmtracker.R
import com.frostbyte.hmtracker.WM_TEMPLATES
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.delay

/*
 * Tutorial awal: berjalan sendiri (bar progres gaya "stories" + peragaan jari yang mengetuk tombol), tampil sekali setelah
 * instal, bisa dilewati kapan saja lewat tombol "Lewati", dan bisa diputar ulang dari Atur > Tutorial Penggunaan.
 * Warna dipatok gelap seperti layar Splash/Login supaya selalu terbaca, apa pun tema aplikasi.
 */

private val TBG = Color(0xFF0A0D12)
private val TAC = Color(0xFFFFB020)
private val TAC2 = Color(0xFFFFC85A)
private val TINK = Color(0xFF1A1200)
private val TTX = Color(0xFFF0F3F8)
private val TSUB = Color(0xFF9AA3B2)
private val TCARD = Color(0xFF131820)
private val TLN = Color(0xFF8B95A8).copy(alpha = .28f)
private val TOK = Color(0xFF34C759)
private val TWA = Color(0xFFFFB800)
private val TER = Color(0xFFFF453A)

/** [ms] = lama langkah ini sebelum pindah sendiri ke langkah berikutnya. */
private class Step(val title: String, val body: String, val ms: Int = 4800)

private val STEPS = listOf(
    Step("Selamat datang di HM Tracker", "Catat jam meter (HM) atau odometer alat berat lewat foto ber-watermark. Panduan singkat ini berjalan sendiri — ketuk Lewati kapan saja."),
    Step("Ketuk tombol kamera", "Tombol bulat di tengah bar bawah membuka kamera. Tombol volume HP juga berfungsi sebagai rana saat kamera terbuka."),
    Step("Kamera mencari angkanya sendiri", "Arahkan ke angka meter. Kamera menandainya dengan [ ] lalu memfokuskannya. Bila ada beberapa angka, ketuk angka yang dimaksud."),
    Step("Cek, lalu simpan", "Angka terisi otomatis dan tetap bisa diedit. Setelah dikonfirmasi, foto ber-watermark tersimpan di HP (Pictures/HM Tracker) dan di Galeri aplikasi.", 5200),
    Step("Pilih gaya watermark", "Di tab Atur tersedia ${WM_TEMPLATES.size} template watermark. Ukuran, kepekatan, dan isinya juga bisa diatur.", 9000),
    Step("Pantau jadwal servis", "Tab Status menampilkan sisa interval servis: LANCAR, PERINGATAN, lalu WAJIB SERVIS, lengkap dengan perkiraan tanggal servis."),
    Step("Siap dipakai!", "Tutorial ini bisa diputar ulang kapan saja dari tab Atur.", 4000)
)

/** Bagian 0..1 dari rentang [a]..[b] pada waktu loop [t]. */
private fun sg(t: Float, a: Float, b: Float): Float = ((t - a) / (b - a)).coerceIn(0f, 1f)
private fun ease(x: Float): Float = x * x * (3f - 2f * x)

/** Waktu loop 0..1 yang berulang tanpa henti selama [ms]. */
@Composable
private fun loop(ms: Int, mode: RepeatMode = RepeatMode.Restart): Float {
    val t by rememberInfiniteTransition(label = "tut").animateFloat(
        0f, 1f, infiniteRepeatable(tween(ms, easing = LinearEasing), mode), label = "tt"
    )
    return t
}

@Composable
fun Tutorial(onDone: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    val last = STEPS.lastIndex
    val prog = remember { Animatable(0f) }
    // jalan sendiri: bar terisi, lalu pindah ke langkah berikutnya; langkah terakhir menunggu tombol Mulai
    LaunchedEffect(page) {
        prog.snapTo(0f)
        prog.animateTo(1f, tween(STEPS[page].ms, easing = LinearEasing))
        if (page < last) page += 1
    }
    BackHandler { if (page > 0) { page -= 1 } else { onDone() } }

    Column(
        Modifier.fillMaxSize().background(TBG)
            .drawBehind {
                drawRect(Brush.radialGradient(listOf(TAC.copy(alpha = .16f), Color.Transparent), center = Offset(size.width / 2f, size.height * .30f), radius = size.width))
            }
            .statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        // bar progres per langkah
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            STEPS.forEachIndexed { i, _ ->
                val f = if (i < page) 1f else if (i == page) prog.value else 0f
                Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = .16f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(TAC))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Tx("${page + 1} / ${STEPS.size}", 12f, 600, TSUB, ls = .5f)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.press { onDone() }.clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = .08f))
                    .border(1.dp, TLN, RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 10.dp)
            ) { Tx("Lewati", 13f, 700, TTX) }
        }

        // peragaan
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
            Crossfade(page, label = "demo") { p -> Demo(p) }
        }

        // judul + penjelasan
        Crossfade(page, label = "txt") { p ->
            Column(Modifier.fillMaxWidth().heightIn(min = 124.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Tx(STEPS[p].title, 22f, 800, TTX, Modifier.fillMaxWidth(), align = TextAlign.Center)
                Tx(STEPS[p].body, 14f, 400, TSUB, Modifier.fillMaxWidth(), align = TextAlign.Center, lh = 1.5f)
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (page > 0) OutBtn("Kembali", { page -= 1 }, Modifier.weight(1f), fg = TTX, border = TLN)
            PrimaryBtn(
                if (page == last) "Mulai Sekarang" else "Lanjut",
                { if (page == last) onDone() else { page += 1 } },
                Modifier.weight(if (page > 0) 1.6f else 1f)
            )
        }
    }
}

@Composable
private fun Demo(p: Int) {
    when (p) {
        0 -> DemoWelcome()
        1 -> DemoCamera()
        2 -> DemoDetect()
        3 -> DemoSave()
        4 -> DemoTemplates()
        5 -> DemoStatus()
        else -> DemoDone()
    }
}

@Composable
private fun DemoFrame(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF1C222D), TCARD)))
            .border(1.dp, TLN, RoundedCornerShape(24.dp))
    ) { content() }
}

/**
 * Penunjuk ketukan: bulatan putih + riak yang melebar. [dx],[dy] = jarak dari target (dp; 0 = tepat di target),
 * [pp] = progres ketukan 0..1 (0 = diam), [vis] = keterlihatan 0..1. Letakkan di dalam Box target (tanpa clip).
 */
@Composable
private fun Finger(dx: Float, dy: Float, pp: Float, vis: Float = 1f) {
    val press = sin(pp * PI.toFloat())
    Box(Modifier.offset(dx.dp, dy.dp).alpha(vis), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(44.dp)
                .graphicsLayer { val s = 1f + 1.3f * pp; scaleX = s; scaleY = s; alpha = if (pp > 0f) (1f - pp) * .6f else 0f }
                .clip(CircleShape).background(Color.White)
        )
        Box(
            Modifier.size(30.dp).scale(1f - .22f * press).clip(CircleShape)
                .background(Color.White.copy(alpha = .85f)).border(2.dp, TAC, CircleShape)
        )
    }
}

@Composable
private fun OkChip(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(20.dp)).background(TOK.copy(alpha = .16f)).border(1.dp, TOK.copy(alpha = .5f), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) { HIcon("chk", 14.dp, TOK); Tx(text, 12f, 600, TTX) }
}

@Composable
private fun NavMock(icon: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        HIcon(icon, 20.dp, TSUB); Tx(label, 9.5f, 500, TSUB)
    }
}

/** Cincin denyut di sekeliling isi (dipakai langkah pembuka dan penutup). */
@Composable
private fun Pulses(t: Float, size: Int = 110) {
    for (i in 0..2) {
        val f = (t + i / 3f) % 1f
        Box(
            Modifier.size(size.dp)
                .graphicsLayer { val s = .8f + 1.1f * f; scaleX = s; scaleY = s; alpha = (1f - f) * .35f }
                .clip(CircleShape).border(2.dp, TAC, CircleShape)
        )
    }
}

@Composable
private fun DemoWelcome() {
    val t = loop(2600)
    DemoFrame {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(110.dp), contentAlignment = Alignment.Center) {
                Pulses(t)
                Image(painterResource(R.drawable.logo), null, Modifier.size(88.dp).clip(RoundedCornerShape(24.dp)), contentScale = ContentScale.Crop)
            }
            Spacer(Modifier.height(18.dp))
            Tx("HM TRACKER", 19f, 800, TTX, ls = 4f)
            Spacer(Modifier.height(4.dp))
            Tx("Frostbyte Technologies", 10.5f, 400, TSUB, ls = 1f)
        }
    }
}

@Composable
private fun DemoCamera() {
    val t = loop(3400)
    val mv = ease(sg(t, .05f, .45f))
    val pp = sg(t, .5f, .75f)
    val opened = sg(t, .72f, .84f) * (1f - sg(t, .94f, 1f))
    val vis = sg(t, 0f, .08f) * (1f - sg(t, .94f, 1f))
    DemoFrame {
        // bar navigasi tiruan
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp).width(280.dp).height(58.dp)
                .clip(RoundedCornerShape(29.dp)).background(Color(0xFF1A2029)).border(1.dp, TLN, RoundedCornerShape(29.dp)).padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically
        ) {
            NavMock("gauge", "Status"); NavMock("img", "Galeri"); Spacer(Modifier.width(64.dp)); NavMock("set", "Atur")
        }
        // tombol kamera + jari yang mengetuk sendiri
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 38.dp).size(64.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(64.dp).graphicsLayer { val s = 1f + .12f * sin(pp * PI.toFloat()); scaleX = s; scaleY = s }
                    .clip(CircleShape).background(Brush.linearGradient(listOf(TAC2, TAC))),
                contentAlignment = Alignment.Center
            ) { HIcon("cam", 26.dp, TINK) }
            Finger(dx = 110f * (1f - mv), dy = 70f * (1f - mv), pp = pp, vis = vis)
        }
        OkChip("Kamera terbuka", Modifier.align(Alignment.TopCenter).padding(top = 24.dp).alpha(opened))
    }
}

@Composable
private fun DemoDetect() {
    val t = loop(3800)
    val zoom = ease(sg(t, .15f, .55f))
    val scan = sg(t, .1f, .6f)
    val done = sg(t, .64f, .74f)
    DemoFrame {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(width = 260.dp, height = 120.dp), contentAlignment = Alignment.Center) {
                // layar meter tiruan
                Box(
                    Modifier.size(width = 190.dp, height = 62.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF05080B))
                        .border(1.dp, Color(0xFF2A323D), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) { Tx("0 1 0 3 7 0", 26f, 800, TAC, ls = 2f) }
                // kurung [ ] yang menyempit + garis pindai
                Canvas(Modifier.matchParentSize()) {
                    val cx = size.width / 2f; val cy = size.height / 2f
                    val inf = (1f - zoom) * 26.dp.toPx()
                    val hw = 103.dp.toPx() + inf; val hh = 39.dp.toPx() + inf
                    val arm = 16.dp.toPx(); val sw = 3.dp.toPx()
                    val c = TAC.copy(alpha = .55f + .45f * zoom)
                    fun corner(sx: Float, sy: Float) {
                        val px = cx + sx * hw; val py = cy + sy * hh
                        drawLine(c, Offset(px, py), Offset(px - sx * arm, py), sw, StrokeCap.Round)
                        drawLine(c, Offset(px, py), Offset(px, py - sy * arm), sw, StrokeCap.Round)
                    }
                    corner(-1f, -1f); corner(1f, -1f); corner(-1f, 1f); corner(1f, 1f)
                    if (scan > 0f && scan < 1f) {
                        val x = cx - hw + 2f * hw * scan
                        drawLine(TAC.copy(alpha = .8f), Offset(x, cy - hh + 6.dp.toPx()), Offset(x, cy + hh - 6.dp.toPx()), 2.dp.toPx())
                    }
                }
            }
            OkChip("Terbaca: 1.037,0 jam", Modifier.alpha(done))
        }
    }
}

@Composable
private fun DemoSave() {
    val t = loop(4400)
    val typed = sg(t, .05f, .35f)
    val txt = "1.037,0".take((typed * 7f).toInt().coerceIn(0, 7))
    val mv = ease(sg(t, .4f, .6f))
    val pp = sg(t, .62f, .8f)
    val vis = sg(t, .35f, .42f) * (1f - sg(t, .9f, .97f))
    val toast = sg(t, .78f, .86f) * (1f - sg(t, .95f, 1f))
    DemoFrame {
        Column(Modifier.align(Alignment.Center).width(260.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Tx("HM SAAT INI", 10f, 700, TSUB, ls = 1f)
            Box(
                Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1C222D))
                    .border(1.dp, TLN, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart
            ) { Tx(if (txt.isEmpty()) " " else txt, 20f, 700, TTX) }
            // tombol simpan + jari
            Box(Modifier.fillMaxWidth().height(46.dp), contentAlignment = Alignment.Center) {
                Row(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(TAC2, TAC))),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                ) { HIcon("chk", 18.dp, TINK); Spacer(Modifier.width(8.dp)); Tx("Simpan foto ber-watermark", 14f, 700, TINK) }
                Finger(dx = 70f * (1f - mv), dy = 50f * (1f - mv), pp = pp, vis = vis)
            }
        }
        OkChip("Tersimpan di Pictures/HM Tracker", Modifier.align(Alignment.TopCenter).padding(top = 14.dp).alpha(toast))
    }
}

@Composable
private fun DemoTemplates() {
    var idx by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); idx = (idx + 1) % WM_TEMPLATES.size } }
    val tp = WM_TEMPLATES[idx]
    DemoFrame {
        Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // pratinjau memakai penggambar watermark yang sama dengan kamera (data contoh)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val w = minOf(maxWidth, maxHeight * 0.85f)
                Box(Modifier.width(w)) { WmPreview(AppData(wmTpl = tp.id)) }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tx(tp.label, 14f, 700, TTX)
                if (tp.premium) Pill("PREMIUM", TAC, TINK, 8f)
            }
            Tx("${idx + 1} dari ${WM_TEMPLATES.size}", 11f, 400, TSUB)
        }
    }
}

@Composable
private fun DemoStatus() {
    val t = loop(4200, RepeatMode.Reverse)
    val p = .22f + .75f * ease(t)
    val c = if (p < .70f) TOK else if (p < .88f) TWA else TER
    val lb = if (p < .70f) "LANCAR" else if (p < .88f) "PERINGATAN" else "WAJIB SERVIS"
    val rem = ((1f - p) * 250f).toInt()
    DemoFrame {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val sw = 14.dp.toPx(); val inset = sw / 2f
                    val arc = Size(size.width - sw, size.height - sw)
                    drawArc(Color.White.copy(alpha = .12f), 135f, 270f, false, Offset(inset, inset), arc, style = Stroke(sw, cap = StrokeCap.Round))
                    drawArc(c, 135f, 270f * p, false, Offset(inset, inset), arc, style = Stroke(sw, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Tx("${(p * 100f).toInt()}%", 30f, 800, c)
                    Tx("interval terpakai", 10f, 400, TSUB)
                }
            }
            Box(Modifier.clip(RoundedCornerShape(14.dp)).background(c).padding(horizontal = 18.dp, vertical = 7.dp)) {
                Tx(lb, 12f, 800, Color(0xFF111111), ls = .5f)
            }
            Tx("Sisa $rem jam", 12f, 500, TSUB)
        }
    }
}

@Composable
private fun DemoDone() {
    val t = loop(2400)
    val sc = remember { Animatable(.3f) }
    LaunchedEffect(Unit) { sc.animateTo(1f, spring(dampingRatio = .5f)) }
    DemoFrame {
        Box(Modifier.align(Alignment.Center).size(110.dp), contentAlignment = Alignment.Center) {
            Pulses(t)
            Box(
                Modifier.size(96.dp).scale(sc.value).clip(CircleShape).background(Brush.linearGradient(listOf(TAC2, TAC))),
                contentAlignment = Alignment.Center
            ) { HIcon("chk", 48.dp, TINK) }
        }
    }
}
