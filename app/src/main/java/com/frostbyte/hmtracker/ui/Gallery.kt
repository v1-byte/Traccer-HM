package com.frostbyte.hmtracker.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.frostbyte.hmtracker.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PhotoThumb(vm: AppVm, p: Photo) {
    val h = hm(); val sh = RoundedCornerShape(18.dp)
    val bm by produceState<ImageBitmap?>(null, p.t) { value = withContext(Dispatchers.IO) { vm.store.bitmap(p.t, 4)?.asImageBitmap() } }
    val al by animateFloatAsState(if (bm != null) 1f else 0f, tween(350), label = "ia")
    Box(Modifier.fillMaxWidth().aspectRatio(1f).press { vm.viewPhoto = p }.clip(sh).background(h.card).border(1.dp, h.ln, sh)) {
        bm?.let { Image(it, null, Modifier.fillMaxSize().alpha(al), contentScale = ContentScale.Crop) }
        if (p.hm > 0) Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { Pill(f1(p.hm), h.ac, h.on, 10f) }
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .8f))))
                .padding(start = 12.dp, end = 12.dp, top = 22.dp, bottom = 12.dp)
        ) {
            Tx(p.u.ifEmpty { "Unit" }, 13f, 700, Color.White, maxLines = 1)
            Tx(dt(p.t), 10.5f, 400, Color.White.copy(alpha = .8f), maxLines = 1, lh = 1.35f)
        }
    }
}

@Composable
fun GalleryScreen(vm: AppVm) {
    val h = hm(); val ph = vm.photos
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column { Tx("Galeri Foto", 18f, 700, h.ac); Mut("${ph.size} foto tersimpan di perangkat") }
        Box(
            Modifier.height(42.dp).press { vm.openCam() }.glow(h.ac.copy(alpha = .4f), 10.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(h.ac2, h.ac))).padding(horizontal = 18.dp), contentAlignment = Alignment.Center
        ) { Tx("+ Foto", 14f, 700, h.on) }
    }
    if (ph.isEmpty()) {
        HCard {
            Column(Modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(72.dp).glow(h.ac.copy(alpha = .25f), 18.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(h.ac.copy(alpha = .12f)).border(1.dp, h.ac.copy(alpha = .4f), RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) { HIcon("img", 30.dp, h.ac) }
                Spacer(Modifier.height(14.dp))
                Tx("Belum ada foto", 14f, 700, h.tx, align = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Tx("Tekan tombol kamera di bawah untuk ambil foto HM dengan watermark profesional.", 12.5f, 400, h.sub, align = TextAlign.Center, lh = 1.45f)
            }
        }
    } else {
        ph.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { Box(Modifier.weight(1f)) { PhotoThumb(vm, it) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
