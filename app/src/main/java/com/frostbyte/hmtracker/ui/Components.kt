package com.frostbyte.hmtracker.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.composed
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.graphicsLayer
import com.frostbyte.hmtracker.f1
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun Tx(
    text: String, sz: Float = 14f, w: Int = 400, c: Color = hm().tx, modifier: Modifier = Modifier,
    ls: Float = 0f, align: TextAlign? = null, maxLines: Int = Int.MAX_VALUE, lh: Float? = null
) = Text(
    text, modifier, color = c, fontSize = sz.sp, fontWeight = FontWeight(w), letterSpacing = ls.sp,
    textAlign = align, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    lineHeight = if (lh != null) (sz * lh).sp else TextStyle.Default.lineHeight
)

@Composable fun Ct(text: String, sz: Float = 15f) = Tx(text, sz, 700, hm().ac)
@Composable fun Mut(text: String, modifier: Modifier = Modifier, c: Color = hm().sub) = Tx(text, 12.5f, 400, c, modifier, lh = 1.45f)

/** Efek tekan: mengecil sedikit lalu kembali (pengganti ripple). */
fun Modifier.press(enabled: Boolean = true, haptic: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pr by src.collectIsPressedAsState()
    val hf = LocalHapticFeedback.current
    val s by animateFloatAsState(if (pr && enabled) .95f else 1f, spring(dampingRatio = .6f, stiffness = 500f), label = "press")
    this.graphicsLayer { scaleX = s; scaleY = s }.clickable(src, null, enabled = enabled) {
        if (haptic) hf.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onClick()
    }
}

/** Kilau kaca tombol: garis terang di tepi atas yang memudar ke bawah (kesan permukaan melengkung). */
fun sheenBrush(top: Float = .5f): Brush =
    Brush.verticalGradient(listOf(Color.White.copy(alpha = top), Color.White.copy(alpha = .05f), Color.Black.copy(alpha = .08f)))

/** Pantulan cahaya di separuh atas tombol berisi warna. */
fun Modifier.gloss(alpha: Float = .22f): Modifier = this.drawBehind {
    drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = alpha), Color.Transparent), endY = size.height * .55f))
}

/** Cahaya berwarna di sekeliling bentuk (aktif di Android 9+; di bawahnya jadi bayangan biasa). */
fun Modifier.glow(color: Color, radius: Dp = 16.dp, shape: Shape = CircleShape): Modifier =
    this.shadow(radius, shape, clip = false, ambientColor = color, spotColor = color)

/** Muncul bertahap (fade + geser naik); [i] menentukan jeda antar-blok. */
@Composable
fun Reveal(i: Int = 0, content: @Composable () -> Unit) {
    var on by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(i * 70L); on = true }
    AnimatedVisibility(on, Modifier.fillMaxWidth(), enter = fadeIn(tween(420)) + slideInVertically(tween(420)) { it / 10 }) { content() }
}

/** Angka yang menghitung naik dari 0 saat tampil dan beranimasi saat nilainya berubah. */
@Composable
fun AnimNum(v: Double, sz: Float, w: Int = 800, c: Color = hm().tx, suffix: String = "", modifier: Modifier = Modifier) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(v) { a.animateTo(v.toFloat(), tween(900, easing = FastOutSlowInEasing)) }
    Tx(f1(a.value.toDouble()) + suffix, sz, w, c, modifier, maxLines = 1)
}

@Composable
fun HCard(modifier: Modifier = Modifier, pad: PaddingValues = PaddingValues(16.dp), borderColor: Color = hm().ln, content: @Composable ColumnScope.() -> Unit) {
    val h = hm(); val sh = RoundedCornerShape(20.dp)
    val bd: Brush = when {
        borderColor != h.ln -> SolidColor(borderColor)
        h.dark -> Brush.verticalGradient(listOf(Color.White.copy(alpha = .13f), Color.White.copy(alpha = .03f)))
        else -> SolidColor(h.ln)
    }
    Column(
        modifier.fillMaxWidth().clip(sh).background(Brush.verticalGradient(listOf(h.cardHi, h.card)))
            .border(1.dp, bd, sh).padding(pad),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content
    )
}

@Composable
fun PrimaryBtn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: String? = null, bg: Color = hm().ac, fg: Color = hm().on, enabled: Boolean = true) {
    val h = hm(); val sh = RoundedCornerShape(16.dp)
    val fill: Brush = if (bg == h.ac) Brush.linearGradient(listOf(h.ac2, h.ac)) else Brush.verticalGradient(listOf(lerp(bg, Color.White, .18f), bg))
    Row(
        modifier.fillMaxWidth().height(54.dp).press(enabled = enabled, onClick = onClick)
            .then(if (enabled) Modifier.glow(bg.copy(alpha = .55f), 16.dp, sh) else Modifier)
            .clip(sh).background(fill, alpha = if (enabled) 1f else .5f)
            .then(if (enabled) Modifier.gloss() else Modifier)
            .border(1.dp, sheenBrush(if (enabled) .5f else .2f), sh),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) { HIcon(icon, 18.dp, fg); Spacer(Modifier.width(8.dp)) }
        Tx(text, 15.5f, 800, fg, ls = .4f)
    }
}

@Composable
fun OutBtn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: String? = null, fg: Color = hm().tx, border: Color = hm().ln) {
    val h = hm(); val sh = RoundedCornerShape(16.dp)
    val glass = Color(0xFF8A93A3)
    val rim: Brush = if (border == h.ln && h.dark) Brush.verticalGradient(listOf(Color.White.copy(alpha = .24f), Color.White.copy(alpha = .06f))) else SolidColor(border)
    Row(
        modifier.fillMaxWidth().height(50.dp).press(onClick = onClick).clip(sh)
            .background(Brush.verticalGradient(listOf(glass.copy(alpha = .18f), glass.copy(alpha = .06f))))
            .border(1.dp, rim, sh),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) { HIcon(icon, 18.dp, fg); Spacer(Modifier.width(8.dp)) }
        Tx(text, 14f, 700, fg, ls = .3f)
    }
}

/** Tombol teks berbentuk kapsul (pengganti tautan polos seperti "+ Unit", "← Atur"): latar tipis, garis warna, efek tekan. */
@Composable
fun GhostBtn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = hm().ac, sz: Float = 13.5f) {
    val sh = RoundedCornerShape(50)
    Box(
        modifier.heightIn(min = 40.dp).press(onClick = onClick).clip(sh)
            .background(Brush.verticalGradient(listOf(color.copy(alpha = .18f), color.copy(alpha = .08f))))
            .border(1.dp, color.copy(alpha = .38f), sh).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) { Tx(text, sz, 700, color, ls = .2f, maxLines = 1) }
}

/** Tombol untuk AlertDialog: [filled] = berisi warna (aksi utama / berbahaya), selain itu kapsul tipis. */
@Composable
fun DialogBtn(text: String, onClick: () -> Unit, color: Color = hm().ac, filled: Boolean = false, fg: Color = Color.White) {
    if (!filled) { GhostBtn(text, onClick, color = color, sz = 13f); return }
    val sh = RoundedCornerShape(50)
    Box(
        Modifier.heightIn(min = 40.dp).press(onClick = onClick).glow(color.copy(alpha = .45f), 10.dp, sh).clip(sh)
            .background(Brush.verticalGradient(listOf(lerp(color, Color.White, .18f), color))).gloss(.2f)
            .border(1.dp, sheenBrush(.45f), sh).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) { Tx(text, 13f, 800, fg, ls = .2f, maxLines = 1) }
}

@Composable
fun Pill(text: String, bg: Color, fg: Color = Color(0xFF111111), sz: Float = 10f) {
    val sh = RoundedCornerShape(50)
    Box(
        Modifier.clip(sh).background(Brush.verticalGradient(listOf(lerp(bg, Color.White, .22f), bg)))
            .border(1.dp, Color.White.copy(alpha = .28f), sh).padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Tx(text, sz, 800, fg, ls = .6f)
    }
}

@Composable fun Dot(c: Color, size: Dp = 8.dp) = Box(Modifier.size(size).clip(CircleShape).background(c))

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, ph: String = "", numeric: Boolean = false, modifier: Modifier = Modifier, secret: Boolean = false) {
    val h = hm()
    Box(modifier.fillMaxWidth().height(54.dp)) {
        Box(Modifier.matchParentSize().border(1.dp, h.sub, RoundedCornerShape(6.dp)), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = TextStyle(color = h.tx, fontSize = 16.sp), cursorBrush = SolidColor(h.ac),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                decorationBox = { inner ->
                    Box { if (value.isEmpty()) Text(ph, color = h.sub.copy(alpha = .6f), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); inner() }
                }
            )
        }
        Text(label, fontSize = 11.5.sp, color = h.sub, modifier = Modifier.offset(10.dp, (-8).dp).background(h.card).padding(horizontal = 4.dp))
    }
}

@Composable
fun SelectField(label: String, value: String, options: List<String>, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val h = hm(); var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth().height(54.dp)) {
        Row(
            Modifier.matchParentSize().border(1.dp, h.sub, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp)).clickable { open = true }.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Tx(value, 16f, 400, h.tx, Modifier.weight(1f), maxLines = 1); Tx("▾", 14f, 400, h.sub)
        }
        Text(label, fontSize = 11.5.sp, color = h.sub, modifier = Modifier.offset(10.dp, (-8).dp).background(h.card).padding(horizontal = 4.dp))
        DropdownMenu(open, { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onSelect(o) }) }
        }
    }
}

@Composable
fun Toggle(on: Boolean, onClick: () -> Unit) {
    val h = hm(); val hf = LocalHapticFeedback.current
    val sh = RoundedCornerShape(15.dp)
    val x by animateDpAsState(if (on) 21.dp else 3.dp, spring(dampingRatio = .65f, stiffness = 500f), label = "tg")
    val track: Brush = if (on) Brush.linearGradient(listOf(h.ac2, h.ac)) else SolidColor(h.ln)
    Box(
        Modifier.size(48.dp, 30.dp)
            .then(if (on) Modifier.glow(h.ac.copy(alpha = .45f), 8.dp, sh) else Modifier)
            .clip(sh).background(track).border(1.dp, if (on) sheenBrush(.4f) else SolidColor(h.ln), sh)
            .clickable(remember { MutableInteractionSource() }, null) { hf.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }
    ) { Box(Modifier.offset(x, 3.dp).size(24.dp).shadow(3.dp, CircleShape).clip(CircleShape).background(Color.White)) }
}

@Composable
fun ToggleW(on: Boolean, onLabel: String, offLabel: String, onClick: () -> Unit) {
    Column(Modifier.width(58.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Toggle(on, onClick); Tx(if (on) onLabel else offLabel, 10f, 400, hm().sub)
    }
}

@Composable
fun Seg(items: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    val sh = RoundedCornerShape(12.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (k, l) ->
            val on = k == selected
            Box(
                Modifier.weight(1f).height(40.dp).press { onSelect(k) }
                    .then(if (on) Modifier.glow(hm().ac.copy(alpha = .35f), 8.dp, sh) else Modifier)
                    .clip(sh)
                    .background(if (on) Brush.linearGradient(listOf(hm().ac2, hm().ac)) else SolidColor(Color(0xFF8A93A3).copy(alpha = .08f)))
                    .then(if (on) Modifier.gloss(.18f) else Modifier)
                    .border(1.dp, if (on) sheenBrush(.45f) else SolidColor(hm().ln), sh),
                contentAlignment = Alignment.Center
            ) { Tx(l, 13f, if (on) 800 else 500, if (on) hm().on else hm().tx) }
        }
    }
}

/** Dialog bawah (bottom sheet) bergaya web: scrim gelap + kartu dengan sudut atas membulat. */
@Composable
fun Sheet(onDismiss: () -> Unit, topBorder: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = .6f))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(.92f).wrapContentHeight(Alignment.Bottom)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(hm().card)
                .then(if (topBorder != null) Modifier.drawBehind { drawRect(topBorder, size = Size(size.width, 3.dp.toPx())) } else Modifier)
                .clickable(remember { MutableInteractionSource() }, null) {}
                .navigationBarsPadding().imePadding()
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(42.dp, 4.dp).clip(RoundedCornerShape(50)).background(hm().sub.copy(alpha = .45f)))
            content()
        }
    }
}
