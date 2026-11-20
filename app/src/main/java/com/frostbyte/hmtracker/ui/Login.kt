package com.frostbyte.hmtracker.ui

import android.accounts.AccountManager
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frostbyte.hmtracker.AppVm
import com.frostbyte.hmtracker.OtpApi
import com.frostbyte.hmtracker.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val AC = Color(0xFFFFB020)
private val AC2 = Color(0xFFFFC85A)
private val INK = Color(0xFF1A1200)
private val TXT = Color(0xFFF0F3F8)
private val SUBT = Color(0xFF9AA3B2)
private val FLD = Color(0xFF1C222D)
private val LN = Color(0xFF8B95A8).copy(alpha = .28f)

@Composable
fun Splash(onDone: () -> Unit) {
    var go by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { go = true; delay(1800); onDone() }
    val a1 by animateFloatAsState(if (go) 1f else 0f, tween(500, 100), label = "a1")
    val a2 by animateFloatAsState(if (go) 1f else 0f, tween(500, 500), label = "a2")
    val a3 by animateFloatAsState(if (go) 1f else 0f, tween(500, 900), label = "a3")
    val bar by animateFloatAsState(if (go) 1f else 0f, tween(1100, 900), label = "bar")
    Column(
        Modifier.fillMaxSize().background(Color(0xFF0A0D12))
            .drawBehind {
                drawRect(Brush.radialGradient(listOf(AC.copy(alpha = .24f * a1), Color.Transparent), center = Offset(size.width / 2f, size.height * .42f), radius = size.width * .95f))
            }
            .clickable(remember { MutableInteractionSource() }, null) { onDone() },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Image(
            painterResource(R.drawable.logo), null,
            Modifier.size(80.dp).alpha(a1).scale(.7f + .3f * a1)
                .shadow(26.dp, RoundedCornerShape(24.dp), clip = false, ambientColor = AC, spotColor = AC).clip(RoundedCornerShape(24.dp)),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.height(18.dp))
        Tx("HM TRACKER", 19f, 800, Color(0xFFE8ECF2), Modifier.alpha(a2), ls = 4f)
        Spacer(Modifier.height(5.dp))
        Tx("Frostbyte Technologies", 10.5f, 400, Color(0xFF8A93A3), Modifier.alpha(a3), ls = 1f)
        Spacer(Modifier.height(24.dp))
        Box(Modifier.size(96.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF8A93A3).copy(alpha = .22f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(bar).background(Brush.horizontalGradient(listOf(AC, Color(0xFFFFC85A)))))
        }
    }
}

@Composable
private fun LField(label: String, value: String, onChange: (String) -> Unit, ph: String, kb: KeyboardType) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Tx(label, 11f, 600, SUBT, ls = .3f); Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(12.dp)).background(FLD).border(1.dp, LN, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value, onChange, singleLine = true, textStyle = TextStyle(color = TXT, fontSize = 16.sp), cursorBrush = SolidColor(AC),
                keyboardOptions = KeyboardOptions(keyboardType = kb), modifier = Modifier.fillMaxWidth(),
                decorationBox = { i -> Box { if (value.isEmpty()) androidx.compose.material3.Text(ph, color = Color(0xFF6B7380), fontSize = 16.sp, maxLines = 1); i() } }
            )
        }
    }
}

@Composable
private fun GoldBtn(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(top = 8.dp).height(52.dp).alpha(if (enabled) 1f else .5f).clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(AC, AC2))).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Tx(text, 15f, 700, INK) }
}

@Composable
fun LoginScreen(vm: AppVm) {
    var mode by remember { mutableStateOf("wa") }
    var step by remember { mutableIntStateOf(1) }
    var wa by remember { mutableStateOf("") }
    var em by remember { mutableStateOf("") }
    val deviceEmailPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val selected = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)?.trim().orEmpty()
            if (Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(selected)) {
                em = selected.lowercase()
                vm.toast("Email dipilih dari akun perangkat")
            } else vm.toast("Akun terpilih tidak memiliki alamat email")
        }
    }
    var sending by remember { mutableStateOf(false) }
    var wipeFor by remember { mutableStateOf<Pair<String, String>?>(null) }   // (ident, mode) menunggu konfirmasi hapus data lama
    var pendingOtp by remember { mutableStateOf("") }
    var pendingIdent by remember { mutableStateOf("") }
    var requestId by remember { mutableStateOf("") }      // id permintaan OTP dari backend
    var verifying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf("") }
    val otp = remember { mutableStateListOf("", "", "", "", "", "") }
    val focus = remember { List(6) { FocusRequester() } }
    var sendTick by remember { mutableIntStateOf(0) }

    fun normPhone(s: String): String {
        var d = s.filter { it.isDigit() }
        if (d.startsWith("0")) d = "62" + d.drop(1)
        if (d.startsWith("8") && d.length >= 9) d = "62$d"
        return d
    }
    /** Login; bila akun berbeda dari pemilik data, minta konfirmasi dulu karena data lama akan dihapus. */
    fun finishLogin(id: String, m: String) {
        if (vm.needsWipe(id)) wipeFor = id to m
        else { vm.toast(if (m == "em") "Masuk sebagai $id" else "Berhasil masuk"); vm.login(id, m) }
    }
    fun verify() {
        val code = otp.joinToString("")
        if (code.length != 6) { vm.toast("Masukkan 6 digit OTP"); return }
        if (!OtpApi.enabled) {
            if (code != pendingOtp) { vm.toast("OTP salah"); return }
            finishLogin(pendingIdent, mode); return
        }
        if (verifying) return
        verifying = true
        scope.launch {
            val r = OtpApi.verify(requestId, code)
            verifying = false
            if (r.ok) finishLogin(pendingIdent, mode)
            else { vm.toast(r.msg.ifEmpty { "OTP salah" }); for (i in 0..5) otp[i] = ""; runCatching { focus[0].requestFocus() } }
        }
    }
    /** Kirim OTP: lewat backend bila dikonfigurasi, jika tidak mode demo lokal. */
    fun startSend(id: String, lbl: String, channel: String) {
        pendingIdent = id; label = lbl; sending = true
        if (!OtpApi.enabled) { pendingOtp = (100000 + (Math.random() * 900000).toInt()).toString(); sendTick++; return }
        scope.launch {
            val r = OtpApi.send(channel, id)
            sending = false
            if (r.ok) {
                requestId = r.requestId; for (i in 0..5) otp[i] = ""; step = 2
                vm.toast("OTP dikirim ke $lbl")
                delay(100); runCatching { focus[0].requestFocus() }
            } else vm.toast(r.msg)
        }
    }
    fun send() {
        if (mode == "em") {
            val id = em.trim().lowercase()
            if (id.isEmpty()) { vm.toast("Pilih atau isi email dulu"); return }
            if (!Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(id)) { vm.toast("Format email tidak valid"); return }
            // Email login sengaja tidak memakai OTP agar dapat digunakan tanpa backend email.
            finishLogin(id, "em"); return
        }
        if (wa.isBlank()) { vm.toast("Isi nomor WhatsApp dulu"); return }
        val id = normPhone(wa)
        if (id.length < 10 || id.length > 15) { vm.toast("Nomor WhatsApp tidak valid"); return }
        startSend(id, "WhatsApp +$id", "wa")
    }
    fun pickDeviceEmail() {
        val intent = AccountManager.newChooseAccountIntent(
            null, null, null, true, "Pilih akun email di perangkat", null, null, null
        )
        runCatching { deviceEmailPicker.launch(intent) }
            .onFailure { vm.toast("Pemilih akun tidak tersedia; email masih bisa dimasukkan manual") }
    }
    // Mode demo: OTP dibuat di perangkat. Ganti blok ini dengan panggilan API backend OTP WhatsApp Anda.
    LaunchedEffect(sendTick) {
        if (sendTick == 0) return@LaunchedEffect
        delay(800); sending = false; step = 2
        for (i in 0..5) otp[i] = ""
        vm.toast("Demo OTP: $pendingOtp (simulasi ${if (mode == "em") "email" else "WhatsApp"})")
        delay(100); runCatching { focus[0].requestFocus() }
    }

    wipeFor?.let { (id, m) ->
        AlertDialog(
            onDismissRequest = { wipeFor = null },
            title = { Text("Ganti akun?") },
            text = { Text("Perangkat ini masih menyimpan data HM akun lain. Masuk sebagai $id akan MENGHAPUS data lama (unit, riwayat HM, foto, jejak audit). Pastikan sudah backup. Lanjutkan?") },
            confirmButton = { TextButton({ wipeFor = null; vm.login(id, m); vm.toast("Masuk sebagai $id") }) { Text("Hapus & masuk") } },
            dismissButton = { TextButton({ wipeFor = null }) { Text("Batal") } }
        )
    }

    Box(
        Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF0B0E13), Color(0xFF121820), Color(0xFF0E131A)), Offset0, OffsetN))
            .systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                Modifier.widthIn(max = 380.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xFF161B24))
                    .border(1.dp, Color(0xFF8B95A8).copy(alpha = .22f), RoundedCornerShape(22.dp)).padding(start = 22.dp, end = 22.dp, top = 28.dp, bottom = 24.dp)
            ) {
                Image(painterResource(R.drawable.logo), null, Modifier.size(56.dp).align(Alignment.CenterHorizontally).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop)
                Spacer(Modifier.height(14.dp))
                Tx("HM Tracker", 20f, 800, TXT, Modifier.fillMaxWidth(), ls = .3f, align = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Tx(
                        if (mode == "wa") "Masuk untuk melanjutkan\nOTP akan dikirim ke WhatsApp Anda" else "Masuk dengan email\nTidak memerlukan OTP",
                    12.5f, 400, SUBT, Modifier.fillMaxWidth(), align = TextAlign.Center, lh = 1.45f
                )
                Spacer(Modifier.height(22.dp))
                if (step == 1) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(12.dp)).background(FLD).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("wa" to "WhatsApp", "em" to "Email").forEach { (k, l) ->
                            Box(
                                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(10.dp)).background(if (mode == k) AC else Color.Transparent).clickable { mode = k },
                                contentAlignment = Alignment.Center
                            ) { Tx(l, 13f, 600, if (mode == k) INK else SUBT) }
                        }
                    }
                    if (mode == "wa") LField("Nomor WhatsApp", wa, { wa = it }, "08xxxxxxxxxx", KeyboardType.Phone)
                    else {
                        OutBtn("Pilih akun email di perangkat", { pickDeviceEmail() }, fg = AC, border = AC.copy(alpha = .45f))
                        LField("Email", em, { em = it }, "email terpilih atau ketik manual", KeyboardType.Email)
                    }
                    GoldBtn(
                        if (sending) "Mengirim..." else if (mode == "wa") "Kirim OTP ke WhatsApp" else "Masuk dengan Email",
                        enabled = !sending
                    ) { send() }
                    Tx("Dengan masuk, Anda menyetujui penyimpanan data lokal di perangkat ini.", 12f, 400, SUBT, Modifier.fillMaxWidth().padding(top = 12.dp), align = TextAlign.Center, lh = 1.4f)
                } else {
                    Tx(
                        "OTP dikirim ke $label", 12.5f, 400, SUBT, Modifier.fillMaxWidth().padding(bottom = 8.dp), align = TextAlign.Center
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                        for (i in 0..5) {
                            var focused by remember { mutableStateOf(false) }
                            Box(
                                Modifier.size(48.dp, 54.dp).clip(RoundedCornerShape(12.dp)).background(FLD)
                                    .border(1.dp, if (focused) AC else LN, RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                BasicTextField(
                                    otp[i], { v ->
                                        val dgt = v.filter { it.isDigit() }
                                        otp[i] = dgt.takeLast(1)
                                        if (dgt.isNotEmpty() && i < 5) focus[i + 1].requestFocus()
                                        if (otp.joinToString("").length == 6) verify()
                                    },
                                    singleLine = true, textStyle = TextStyle(color = TXT, fontSize = 22.sp, textAlign = TextAlign.Center, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                                    cursorBrush = SolidColor(AC), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                    modifier = Modifier.fillMaxWidth().focusRequester(focus[i]).onFocusChangedCompat { focused = it }
                                        .onPreviewKeyEvent { e ->
                                            if (e.type == KeyEventType.KeyDown && e.key == Key.Backspace && otp[i].isEmpty() && i > 0) { focus[i - 1].requestFocus(); true } else false
                                        }
                                )
                            }
                        }
                    }
                    GoldBtn("Verifikasi OTP") { verify() }
                    Box(
                        Modifier.fillMaxWidth().padding(top = 10.dp).height(46.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, Color(0xFF8B95A8).copy(alpha = .3f), RoundedCornerShape(14.dp))
                            .clickable { step = 1; pendingOtp = "" },
                        contentAlignment = Alignment.Center
                    ) { Tx("Ganti nomor / email", 14f, 600, AC) }
                    if (!OtpApi.enabled) Tx("Mode demo — OTP juga muncul di notifikasi. Setel HM_OTP_API_URL saat build untuk produksi.", 12f, 400, SUBT, Modifier.fillMaxWidth().padding(top = 12.dp), align = TextAlign.Center, lh = 1.4f)
                }
            }
            Spacer(Modifier.height(20.dp))
            Tx("Frostbyte Technologies · Secure Login", 11f, 400, Color(0xFF6B7380))
        }
    }
}

private val Offset0 = androidx.compose.ui.geometry.Offset(0f, 0f)
private val OffsetN = androidx.compose.ui.geometry.Offset(600f, 2000f)

private fun Modifier.onFocusChangedCompat(f: (Boolean) -> Unit): Modifier = onFocusChanged { f(it.isFocused) }
