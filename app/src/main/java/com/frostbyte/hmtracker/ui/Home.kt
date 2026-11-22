package com.frostbyte.hmtracker.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.frostbyte.hmtracker.*
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.cos
import kotlin.math.sin

private val LB = mapOf("ok" to "LANCAR", "wa" to "PERINGATAN", "er" to "WAJIB SERVIS")

/** Bagikan teks lewat menu berbagi Android (WhatsApp, email, Drive, dll). */
private fun shareText(ctx: android.content.Context, text: String, mime: String, title: String) {
    val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType(mime).putExtra(android.content.Intent.EXTRA_TEXT, text)
    ctx.startActivity(android.content.Intent.createChooser(i, title).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun short(x: Double) = if (x >= 100) String.format(ID, "%.0f", x) else String.format(ID, "%.1f", x)

/** Gauge busur 270° dengan gradien, cahaya berdenyut, penanda ujung, dan skala garis. */
@Composable
private fun Gauge(pct: Double, col: Color, modifier: Modifier, inner: @Composable BoxScope.() -> Unit) {
    val h = hm()
    val p = remember { Animatable(0f) }
    LaunchedEffect(pct) { p.animateTo((pct / 100.0).toFloat().coerceIn(0f, 1f), tween(1200, easing = FastOutSlowInEasing)) }
    val pulse by rememberInfiniteTransition(label = "gp").animateFloat(
        .5f, 1f, infiniteRepeatable(tween(1700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "gpa"
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = 14.dp.toPx()
            val r = size.minDimension / 2 - 22.dp.toPx()
            val c = this.center
            val tl = Offset(c.x - r, c.y - r); val sz = Size(r * 2, r * 2)
            val v = p.value
            // skala garis
            val n = 40
            for (i in 0..n) {
                val f = i / n.toFloat(); val ang = Math.toRadians((135f + 270f * f).toDouble())
                val big = i % 5 == 0
                val r1 = r + sw / 2 + 6.dp.toPx(); val r2 = r1 + (if (big) 8.dp.toPx() else 4.dp.toPx())
                val lit = f <= v
                drawLine(
                    if (lit) col.copy(alpha = if (big) 1f else .7f) else h.track,
                    Offset(c.x + cos(ang).toFloat() * r1, c.y + sin(ang).toFloat() * r1),
                    Offset(c.x + cos(ang).toFloat() * r2, c.y + sin(ang).toFloat() * r2),
                    strokeWidth = if (big) 2.dp.toPx() else 1.2.dp.toPx(), cap = StrokeCap.Round
                )
            }
            // jalur
            drawArc(h.track, 135f, 270f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
            if (v > 0.001f) {
                // cahaya
                drawArc(col.copy(alpha = .20f * pulse), 135f, 270f * v, false, tl, sz, style = Stroke(sw + 16.dp.toPx(), cap = StrokeCap.Round))
                // busur utama bergradien
                rotate(135f, c) {
                    drawArc(
                        Brush.sweepGradient(0f to col.copy(alpha = .4f), 0.75f to col, 1f to col, center = c),
                        0f, 270f * v, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round)
                    )
                }
                // penanda ujung
                val ang = Math.toRadians((135f + 270f * v).toDouble())
                val pos = Offset(c.x + cos(ang).toFloat() * r, c.y + sin(ang).toFloat() * r)
                drawCircle(col.copy(alpha = .35f), 10.dp.toPx(), pos)
                drawCircle(Color.White, 5.dp.toPx(), pos)
            }
        }
        inner()
    }
}

@Composable
private fun Tile(label: String, v: Double, suffix: String, hi: Boolean, modifier: Modifier) {
    val h = hm(); val sh = RoundedCornerShape(16.dp)
    val bd: Brush = if (hi) Brush.verticalGradient(listOf(h.ac.copy(alpha = .6f), h.ac.copy(alpha = .1f)))
    else if (h.dark) Brush.verticalGradient(listOf(Color.White.copy(alpha = .12f), Color.White.copy(alpha = .03f)))
    else Brush.verticalGradient(listOf(h.ln, h.ln))
    Column(
        modifier.clip(sh).background(Brush.verticalGradient(listOf(if (hi) h.ac.copy(alpha = .12f) else h.cardHi, h.card)))
            .border(1.dp, bd, sh).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Tx(label, 9f, 700, if (hi) h.ac else h.sub, ls = .8f, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            AnimNum(v, 18f, 800, h.tx, modifier = Modifier.weight(1f, false))
            Tx(suffix, 10f, 600, h.sub, Modifier.padding(bottom = 2.dp), maxLines = 1)
        }
    }
}

@Composable
fun HomeScreen(vm: AppVm) {
    val h = hm(); val d = vm.d; val u = d.unit; val c = calc(u); val z = u.un()
    val zs = if (u.sat == "km") "km" else "h"
    val interval = u.interval.takeIf { it > 0 } ?: 200.0
    var arm by remember { mutableStateOf("") }
    val nm = d.profile.name.trim()
    val ini = (nm.ifBlank { "HM" }).split(Regex("\\s+")).map { it.first() }.joinToString("").take(2).uppercase()
    val col = h.st(c.st)

    if (c.st == "er" && u.cfg()) Reveal(0) {
        val blink by rememberInfiniteTransition(label = "bl").animateFloat(.55f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "bla")
        HCard(borderColor = h.er.copy(alpha = blink)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Dot(h.er, 10.dp); Tx("STOP, lakukan servis berkala sekarang.", 14f, 700, h.er)
            }
        }
    }

    // header
    Reveal(0) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Tx("HM TRACKER", 10f, 800, h.ac, ls = 2.4f)
                Tx("Halo, " + nm.split(" ").firstOrNull { it.isNotBlank() }.orEmpty().ifEmpty { "Operator" }, 20f, 800, h.tx, Modifier.padding(top = 2.dp), maxLines = 1)
                Mut(SimpleDateFormat("EEEE, dd MMMM yyyy", ID).format(Date()))
            }
            Box(
                Modifier.size(44.dp).glow(h.ac.copy(alpha = .5f), 12.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(h.ac2, h.ac))),
                contentAlignment = Alignment.Center
            ) { Tx(ini, 14f, 800, h.on) }
        }
    }

    // pilih unit
    Reveal(1) {
        var open by remember { mutableStateOf(false) }
        Box {
            Row(
                Modifier.height(42.dp).widthIn(max = 280.dp).clip(RoundedCornerShape(14.dp)).background(h.card)
                    .border(1.dp, h.ln, RoundedCornerShape(14.dp)).press { open = true }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Dot(col); Tx(u.name.ifEmpty { "Unit belum diatur" }, 14f, 700, h.tx, maxLines = 1, modifier = Modifier.weight(1f, false)); Tx("▾", 12f, 400, h.sub)
            }
            DropdownMenu(open, { open = false }) {
                d.units.forEachIndexed { i, x -> DropdownMenuItem(text = { Text(x.name.ifEmpty { "Unit belum diatur" }) }, onClick = { open = false; vm.upd { it.copy(active = i) }; vm.rev++ }) }
            }
        }
    }

    // kartu status (hero)
    Reveal(2) {
        val shape = RoundedCornerShape(26.dp)
        Column(
            Modifier.fillMaxWidth().clip(shape)
                .background(Brush.verticalGradient(listOf(col.copy(alpha = if (h.dark) .18f else .14f), h.card)))
                .border(1.dp, Brush.verticalGradient(listOf(col.copy(alpha = .5f), h.ln)), shape)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Pill(LB[c.st]!!, col)
                Mut(listOf(u.type, u.loc).filter { it.isNotBlank() }.joinToString(" · "))
            }
            Gauge(c.pct, col, Modifier.size(250.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Tx("SISA SERVIS", 10f, 700, h.sub, ls = 2f)
                    AnimNum(maxOf(0.0, c.rem), 40f, 800, h.tx, modifier = Modifier.padding(vertical = 2.dp))
                    Tx(z, 13f, 700, col)
                    Tx("${Math.round(c.pct)}% terpakai", 11f, 400, h.sub, Modifier.padding(top = 6.dp))
                }
            }
            Tx(
                if (c.rem <= 0) "Servis sekarang" else if (c.est != null) "Perkiraan servis " + SimpleDateFormat("dd MMM yyyy", ID).format(Date(c.est)) else "Perkiraan servis: butuh data pemakaian",
                12.5f, 500, h.sub, align = TextAlign.Center
            )
        }
    }

    // tile ringkas
    Reveal(3) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile("HM SAAT INI", c.c, zs, true, Modifier.weight(1f))
            Tile("TERPAKAI", c.used, zs, false, Modifier.weight(1f))
            Tile("INTERVAL", interval, zs, false, Modifier.weight(1f))
        }
    }

    // grafik
    Reveal(4) {
        HCard {
            val a = diffs(u)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Tx("Grafik pemakaian", 14f, 700)
                Mut(if (a.isEmpty()) "7 pembacaan terakhir" else "rata-rata " + short(a.average()) + " $zs")
            }
            if (a.isEmpty()) Mut("Belum ada pemakaian. Catat HM berikutnya untuk melihat grafik.")
            else {
                val mx = (a.maxOrNull() ?: 1.0).takeIf { it > 0 } ?: 1.0
                var shown by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { shown = true }
                Row(Modifier.fillMaxWidth().height(104.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    a.forEachIndexed { i, x ->
                        val frac by animateFloatAsState(
                            if (shown) (x / mx).toFloat().coerceIn(.04f, 1f) else 0f,
                            tween(800, delayMillis = i * 70, easing = FastOutSlowInEasing), label = "bar$i"
                        )
                        val last = i == a.lastIndex
                        Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                            Tx(short(x), 9f, if (last) 800 else 500, if (last) h.ac else h.sub, maxLines = 1)
                            Spacer(Modifier.height(3.dp))
                            Box(
                                Modifier.fillMaxWidth().height((78f * frac).dp)
                                    .clip(RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp, bottomStart = 3.dp, bottomEnd = 3.dp))
                                    .background(
                                        if (last) Brush.verticalGradient(listOf(h.ac2, h.ac))
                                        else Brush.verticalGradient(listOf(Color(0xFF8A93A3).copy(alpha = .5f), Color(0xFF8A93A3).copy(alpha = .18f)))
                                    )
                            )
                        }
                    }
                }
            }
        }
    }

    // analitik: prediksi & laju (Premium), tren, perbandingan unit, ekspor & laporan (Platinum)
    Reveal(5) {
        val ctx = LocalContext.current
        val now = TrustedClock.now()
        val fc = if (u.cfg()) forecast(u, now) else null
        HCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Tx("Analitik", 14f, 700)
                Mut("Paket " + tierOf(d.tier).label)
            }
            when {
                !u.cfg() -> Mut("Catat HM minimal dua kali untuk melihat analitik.")
                !d.allows(Feature.CALC_FORECAST) -> Mut("Prediksi servis & laju pemakaian tersedia di paket Premium.")
                else -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Mut("Laju pemakaian")
                            Tx(fc?.rate?.let { f1(it) + " $zs/hari" } ?: "belum cukup data", 16f, 700)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Mut("Perkiraan servis")
                            Tx(fc?.dueAt?.let { dt(it) } ?: "—", 13f, 700)
                        }
                    }
                    Mut("Keyakinan prediksi: " + (fc?.confidence ?: "rendah"))
                }
            }
            if (u.cfg() && d.allows(Feature.CALC_TREND)) {
                val (tl, tc) = when (fc?.trend) {
                    Trend.NAIK -> "Pemakaian naik dibanding rata-rata" to h.wa
                    Trend.TURUN -> "Pemakaian turun dibanding rata-rata" to h.ok
                    Trend.STABIL -> "Pemakaian stabil" to h.sub
                    else -> "Tren: data belum cukup" to h.sub
                }
                Mut(tl, c = tc)
            }
            if (d.allows(Feature.CALC_COMPARE) && d.units.size > 1) {
                Tx("Perbandingan unit (paling mendesak dulu)", 13f, 700, modifier = Modifier.padding(top = 4.dp))
                compareUnits(d.units).forEach { r ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Tx(r.name, 13f, 600, modifier = Modifier.weight(1f), maxLines = 1)
                        Tx("sisa " + f1(maxOf(0.0, r.rem)) + " $zs", 12.5f, 600, when (r.st) { "er" -> h.er; "wa" -> h.wa; else -> h.ok }, maxLines = 1)
                    }
                }
            }
            if (d.allows(Feature.CALC_EXPORT) || d.allows(Feature.CALC_REPORT)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (d.allows(Feature.CALC_EXPORT)) OutBtn("Ekspor CSV", { shareText(ctx, csvOf(d), "text/csv", "Ekspor HM Tracker") }, modifier = Modifier.weight(1f))
                    if (d.allows(Feature.CALC_REPORT) && u.cfg()) OutBtn("Bagikan laporan", { shareText(ctx, reportOf(u, now), "text/plain", "Laporan HM Tracker") }, modifier = Modifier.weight(1f))
                }
            } else Mut("Ekspor CSV tersedia di paket Premium, laporan siap bagikan di paket Platinum.")
        }
    }

    // riwayat (garis waktu)
    Reveal(5) {
        HCard(pad = PaddingValues(horizontal = 16.dp, vertical = 14.dp), modifier = Modifier) {
            val r = u.readings
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Tx("Riwayat", 14f, 700); Mut("${r.size} pembacaan")
            }
            if (r.isEmpty()) Mut("Belum ada pembacaan.")
            val first = maxOf(0, r.size - 15)
            Column {
                for (i in r.lastIndex downTo first) {
                    val svc = r[i].svc
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.width(14.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Spacer(Modifier.height(16.dp))
                            if (svc) Dot(h.ac, 12.dp) else Dot(h.sub.copy(alpha = .6f), 8.dp)
                            if (i > first) Box(Modifier.width(2.dp).weight(1f).background(h.ln))
                        }
                        Row(Modifier.weight(1f).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Tx(dt(r[i].t), 13f)
                                if (svc) Tx("TITIK SERVIS", 10f, 800, h.ac, ls = .8f)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Tx(f1(r[i].hm) + " $zs", 14f, 700)
                                Mut(if (svc) "–" else "+" + f1(maxOf(0.0, r[i].hm - r[i - 1].hm)) + " $zs", c = if (svc) h.ac else h.ok)
                            }
                            Box(Modifier.defaultMinSize(minWidth = 40.dp, minHeight = 36.dp).press {
                                if (arm != "r$i") arm = "r$i" else {
                                    arm = ""
                                    if (u.readings.size < 2) vm.toast("Minimal satu pembacaan") else vm.delReading(i)
                                }
                            }.clip(RoundedCornerShape(10.dp)).background(if (arm == "r$i") h.er.copy(alpha = .14f) else h.sub.copy(alpha = .10f)).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) { Tx(if (arm == "r$i") "Hapus?" else "✕", 14f, 700, if (arm == "r$i") h.er else h.sub) }
                        }
                    }
                }
            }
        }
    }
    if (u.readings.isEmpty()) Mut("Belum ada HM. Ambil foto meter atau isi HM di Workspace; pembacaan pertama menjadi titik servis awal.")
}
