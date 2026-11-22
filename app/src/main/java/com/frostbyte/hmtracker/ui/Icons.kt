package com.frostbyte.hmtracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Ikon garis yang sama dengan versi web (viewBox 24x24, stroke 1.8). */
private sealed class Sh {
    class P(val d: String) : Sh()
    class C(val x: Float, val y: Float, val r: Float) : Sh()
    class R(val x: Float, val y: Float, val w: Float, val h: Float, val rx: Float) : Sh()
}

private val ICONS: Map<String, List<Sh>> = mapOf(
    "cam" to listOf(Sh.P("M4 8h3l1.5-2h7L17 8h3v11H4z"), Sh.C(12f, 13f, 3.5f)),
    "share" to listOf(Sh.C(18f, 5f, 3f), Sh.C(6f, 12f, 3f), Sh.C(18f, 19f, 3f), Sh.P("M8.6 13.5l6.8 4M15.4 6.5l-6.8 4")),
    "gauge" to listOf(Sh.P("M4 17a8 8 0 1 1 16 0"), Sh.P("M12 17l4-5")),
    "img" to listOf(Sh.R(3.5f, 4.5f, 17f, 15f, 2f), Sh.C(9f, 10f, 1.5f), Sh.P("M4 17l5-4 4 3 3-2 4 3")),
    "set" to listOf(Sh.C(12f, 12f, 3f), Sh.P("M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M18.4 5.6l-2.1 2.1M7.7 16.3l-2.1 2.1")),
    "flip" to listOf(Sh.P("M4 12a8 8 0 0 1 14-5l2 2M20 12a8 8 0 0 1-14 5l-2-2"), Sh.P("M20 4v5h-5M4 20v-5h5")),
    "up" to listOf(Sh.P("M12 16V5M7 10l5-5 5 5M5 19h14")),
    "pin" to listOf(Sh.P("M12 21s7-6.2 7-11a7 7 0 1 0-14 0c0 4.8 7 11 7 11z"), Sh.C(12f, 10f, 2.5f)),
    "chk" to listOf(Sh.P("M5 12.5l4.5 4.5L19 7.5")),
    "torch" to listOf(Sh.P("M9 3h6v4l-1 2v12h-4V9L9 7z"), Sh.P("M12 12v2")),
    "flash" to listOf(Sh.P("M13 3L5 13.5h6L10 21l8-10.5h-6z")),
    "timer" to listOf(Sh.C(12f, 13.5f, 7f), Sh.P("M12 13.5V9.5M9.5 3h5")),
    "grid" to listOf(Sh.R(4f, 4f, 16f, 16f, 2f), Sh.P("M9.3 4v16M14.7 4v16M4 9.3h16M4 14.7h16")),
    "level" to listOf(Sh.C(12f, 12f, 3f), Sh.P("M3 12h4.5M16.5 12H21")),
    "lock" to listOf(Sh.R(5f, 11f, 14f, 9f, 2f), Sh.P("M8 11V8a4 4 0 0 1 8 0v3")),
    "zoomauto" to listOf(Sh.C(11f, 11f, 6f), Sh.P("M20 20l-4.2-4.2M11 8.5v5M8.5 11h5")),
    "auto" to listOf(Sh.C(12f, 12f, 8.5f), Sh.P("M8.5 15.5L12 7.5l3.5 8M9.8 13h4.4")),
    "sound" to listOf(Sh.P("M4 9.5h3l5-4v13l-5-4H4z"), Sh.P("M15.5 9a4 4 0 0 1 0 6")),
    "sliders" to listOf(Sh.P("M4 7h9M17 7h3M4 17h3M11 17h9"), Sh.C(15f, 7f, 2f), Sh.C(9f, 17f, 2f)),
    "hist" to listOf(Sh.P("M5 19v-6M10 19V6M15 19v-9M20 19v-4"), Sh.P("M3 21h18")),
    "haptic" to listOf(Sh.R(8f, 4f, 8f, 16f, 2f), Sh.P("M4 9v6M20 9v6")),
    "ev" to listOf(Sh.C(12f, 12f, 3.5f), Sh.P("M12 3v2.5M12 18.5V21M3 12h2.5M18.5 12H21M5.6 5.6l1.8 1.8M16.6 16.6l1.8 1.8M18.4 5.6l-1.8 1.8M7.4 16.6l-1.8 1.8")),
    "chev" to listOf(Sh.P("M6 9l6 6 6-6")),
    "frame" to listOf(Sh.P("M4 9V6a2 2 0 0 1 2-2h3M15 4h3a2 2 0 0 1 2 2v3M20 15v3a2 2 0 0 1-2 2h-3M9 20H6a2 2 0 0 1-2-2v-3"), Sh.C(12f, 12f, 1.6f))
)

@Composable
fun HIcon(name: String, size: Dp = 22.dp, tint: Color = hm().tx, modifier: Modifier = Modifier) {
    val shapes = remember(name) { ICONS[name].orEmpty() }
    val paths = remember(name) { shapes.map { if (it is Sh.P) PathParser().parsePathString(it.d).toPath() else null } }
    Canvas(modifier.size(size)) {
        val s = this.size.width / 24f
        scale(s, s, pivot = Offset.Zero) {
            val st = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            shapes.forEachIndexed { i, sh ->
                when (sh) {
                    is Sh.P -> drawPath(paths[i] as Path, tint, style = st)
                    is Sh.C -> drawCircle(tint, sh.r, Offset(sh.x, sh.y), style = st)
                    is Sh.R -> drawRoundRect(tint, Offset(sh.x, sh.y), Size(sh.w, sh.h), CornerRadius(sh.rx), style = st)
                }
            }
        }
    }
}
