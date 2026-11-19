package com.frostbyte.hmtracker.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

class HmColors(
    val bg: Color, val card: Color, val tx: Color, val sub: Color, val ln: Color,
    val ac: Color, val ac2: Color, val on: Color, val dark: Boolean
) {
    val cardHi: Color get() = if (dark) Color(0xFF1C222D) else Color(0xFFF6F7FA)   // ujung atas gradien kartu
    val track: Color get() = Color(0xFF8A93A3).copy(alpha = .22f)
    val ok = Color(0xFF34C759)
    val wa = Color(0xFFFFB800)
    val er = Color(0xFFFF453A)
    fun st(s: String) = when (s) { "er" -> er; "wa" -> wa; else -> ok }
}

val LocalHm = compositionLocalOf<HmColors> { error("no theme") }

@Composable
@ReadOnlyComposable
fun hm() = LocalHm.current

fun hex(s: String) = Color(android.graphics.Color.parseColor(s))

@Composable
fun HmTheme(mode: String, acc: String, fs: String, content: @Composable () -> Unit) {
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val ac = hex(acc)
    val def = acc.equals("#FFB020", true)
    val c = if (dark) HmColors(
        Color(0xFF0A0D12), Color(0xFF131820), Color(0xFFEEF1F6), Color(0xFF8A93A3),
        Color(0xFF8A93A3).copy(alpha = .22f), ac, if (def) Color(0xFFFFC85A) else ac,
        if (def) Color(0xFF1A1200) else Color.White, true
    ) else HmColors(
        Color(0xFFEEF0F4), Color.White, Color(0xFF14171C), Color(0xFF626874),
        Color(0xFF626874).copy(alpha = .25f), ac, if (def) Color(0xFFFFC85A) else ac,
        if (def) Color(0xFF1A1200) else Color.White, false
    )
    val scheme = if (dark) darkColorScheme(primary = c.ac, onPrimary = c.on, surface = c.card, onSurface = c.tx, background = c.bg, onBackground = c.tx)
    else lightColorScheme(primary = c.ac, onPrimary = c.on, surface = c.card, onSurface = c.tx, background = c.bg, onBackground = c.tx)
    val f = when (fs) { "sm" -> 14f / 16f; "lg" -> 18f / 16f; else -> 1f }
    val d = LocalDensity.current
    CompositionLocalProvider(
        LocalHm provides c,
        LocalDensity provides Density(d.density, d.fontScale * f)
    ) { MaterialTheme(colorScheme = scheme, content = content) }
}
