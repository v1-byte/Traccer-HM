package com.frostbyte.hmtracker

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.frostbyte.hmtracker.ui.*
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val vm: AppVm by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HmTheme(vm.d.mode, vm.d.acc, vm.d.fs) { AppRoot(vm) } }
    }

    // Tombol volume = rana saat kamera terbuka dan belum ada foto yang sedang ditinjau
    private fun volumeShutter(code: Int) = (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) && vm.camOpen && vm.frame == null && vm.shutter != null

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (volumeShutter(keyCode)) { if (event.repeatCount == 0) vm.shutter?.invoke(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (volumeShutter(keyCode)) true else super.onKeyUp(keyCode, event)
}

@Composable
fun AppRoot(vm: AppVm) {
    val h = hm(); val view = LocalView.current
    SideEffect {
        val w = (view.context as Activity).window
        WindowCompat.getInsetsController(w, view).apply { isAppearanceLightStatusBars = !h.dark; isAppearanceLightNavigationBars = !h.dark }
    }
    var splash by remember { mutableStateOf(true) }
    Box(Modifier.fillMaxSize().background(h.bg)) {
        when {
            splash -> Splash { splash = false }
            !vm.logged -> LoginScreen(vm)
            else -> {
                MainShell(vm)
                if (vm.camOpen) CameraScreen(vm)
                vm.viewPhoto?.let { PhotoDetailSheet(vm, it) }
                if (vm.stopAlert) StopSheet(vm)
            }
        }
        Toast(vm)
    }
}

@Composable
private fun Toast(vm: AppVm) {
    val m = vm.toastMsg
    LaunchedEffect(vm.toastId) { if (vm.toastId > 0) { delay(2600); vm.toastMsg = null } }
    if (m != null) Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 112.dp), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier.widthIn(max = 340.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF222222))
                .border(1.dp, Color(0x66FFB020), RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 9.dp)
        ) { Tx(m, 13f, 400, Color.White, align = TextAlign.Center) }
    }
}

@Composable
private fun MainShell(vm: AppVm) {
    val h = hm(); val sc = rememberScrollState()
    LaunchedEffect(vm.tab) { sc.scrollTo(0) }
    val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Box(
        Modifier.fillMaxSize().drawBehind {
            drawRect(Brush.radialGradient(listOf(h.ac.copy(alpha = if (h.dark) .14f else .10f), Color.Transparent), center = Offset(size.width * .9f, 0f), radius = size.width * 1.05f))
        }
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(sc)
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = if (imeOpen) 24.dp else 124.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (vm.tab) { "gal" -> GalleryScreen(vm); "set" -> SettingsScreen(vm); "train" -> TrainingScreen(vm); else -> HomeScreen(vm) }
        }
        if (!imeOpen) FloatingNav(vm, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun FloatingNav(vm: AppVm, modifier: Modifier) {
    val h = hm(); val shape = RoundedCornerShape(30.dp)
    val bd: Brush = if (h.dark) Brush.verticalGradient(listOf(Color.White.copy(alpha = .14f), Color.White.copy(alpha = .03f))) else SolidColor(h.ln)
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(start = 14.dp, end = 14.dp, bottom = 10.dp).height(92.dp)) {
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(64.dp)
                .shadow(18.dp, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(shape).background(h.card.copy(alpha = .97f)).border(1.dp, bd, shape).padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically
        ) {
            NavItem(vm, "home", "gauge", "Status"); NavItem(vm, "gal", "img", "Galeri")
            Spacer(Modifier.width(76.dp))
            NavItem(vm, "set", "set", "Atur")
        }
        CamButton(Modifier.align(Alignment.TopCenter)) { vm.openCam() }
    }
}

@Composable
private fun CamButton(modifier: Modifier, onClick: () -> Unit) {
    val h = hm()
    val ring by rememberInfiniteTransition(label = "cam").animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "ring")
    Box(modifier.size(76.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(64.dp).graphicsLayer { scaleX = 1f + .25f * ring; scaleY = 1f + .25f * ring; alpha = (1f - ring) * .45f }.clip(CircleShape).background(h.ac))
        Box(
            Modifier.size(64.dp).press(onClick = onClick).glow(h.ac.copy(alpha = .7f), 14.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(h.ac2, h.ac))).border(4.dp, h.bg, CircleShape),
            contentAlignment = Alignment.Center
        ) { HIcon("cam", 26.dp, h.on) }
    }
}

@Composable
private fun NavItem(vm: AppVm, tab: String, icon: String, label: String) {
    val h = hm(); val on = vm.tab == tab
    val c by animateColorAsState(if (on) h.ac else h.sub, label = "nc")
    val bg by animateColorAsState(if (on) h.ac.copy(alpha = .15f) else Color.Transparent, label = "nb")
    Column(
        Modifier.widthIn(min = 60.dp).press { vm.go(tab) }.clip(RoundedCornerShape(16.dp)).background(bg).padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)
    ) { HIcon(icon, 22.dp, c); Tx(label, 10f, if (on) 700 else 400, c) }
}
