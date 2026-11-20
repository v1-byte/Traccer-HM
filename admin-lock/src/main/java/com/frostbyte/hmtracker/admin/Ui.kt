package com.frostbyte.hmtracker.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Amber = Color(0xFFFFB020)
private val Danger = Color(0xFFFF453A)
private val Good = Color(0xFF34C759)
private val Sub = Color(0xFF8A93A3)

@Composable
fun AdmTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Amber, onPrimary = Color(0xFF1A1200), background = Color(0xFF0A0D12), onBackground = Color(0xFFEEF1F6),
            surface = Color(0xFF131820), onSurface = Color(0xFFEEF1F6), error = Danger,
        ),
        content = content,
    )
}

private fun fmt(ms: Long): String = SimpleDateFormat("dd MMM yyyy HH:mm", Locale("id", "ID")).format(Date(ms))
private fun fmtDay(ms: Long): String = SimpleDateFormat("dd MMM yyyy", Locale("id", "ID")).format(Date(ms))
private const val DAY = 86_400_000L

@Composable
private fun H(text: String) = Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Sub, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))

@Composable
fun Root(vm: AdminVm) {
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) { vm.message?.let { snack.showSnackbar(it); vm.message = null } }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = { if (vm.loggedIn && vm.editing == null) BottomBar(vm) },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().statusBarsPadding()) {
            when {
                !vm.loggedIn -> LoginScreen(vm)
                vm.editing != null -> UserEditor(vm, vm.editing!!)
                else -> when (vm.tab) {
                    0 -> UsersTab(vm)
                    1 -> VouchersTab(vm)
                    2 -> LogTab(vm)
                    else -> SettingsTab(vm)
                }
            }
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
}

@Composable
private fun BottomBar(vm: AdminVm) {
    val items = listOf("Akun" to "👤", "Voucher" to "🎟", "Log" to "📜", "Atur" to "⚙")
    NavigationBar {
        items.forEachIndexed { i, (label, ico) ->
            NavigationBarItem(
                selected = vm.tab == i,
                onClick = {
                    vm.tab = i
                    when (i) { 0 -> vm.loadUsers(); 1 -> vm.loadVouchers(); 2 -> vm.loadLog() }
                },
                icon = { Text(ico) }, label = { Text(label) },
            )
        }
    }
}

// ===================== Masuk =====================
@Composable
private fun LoginScreen(vm: AdminVm) {
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
        Text("Adm Lock", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Amber)
        Text("Kunci dan buka paket HM Tracker dari jarak jauh. Hanya untuk admin.", color = Sub, modifier = Modifier.padding(top = 4.dp, bottom = 20.dp))
        OutlinedTextField(
            vm.url, { vm.url = it }, label = { Text("Alamat server (https://…)") }, singleLine = true,
            enabled = vm.requestId == null, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            vm.email, { vm.email = it }, label = { Text("Email admin") }, singleLine = true,
            enabled = vm.requestId == null, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        Spacer(Modifier.height(10.dp))
        if (vm.requestId == null) {
            Button({ vm.sendOtp() }, enabled = !vm.busy && vm.email.isNotBlank() && vm.url.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Kirim kode OTP") }
        } else {
            OutlinedTextField(
                vm.code, { vm.code = it.filter(Char::isDigit).take(6) }, label = { Text("Kode OTP 6 angka") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            )
            Spacer(Modifier.height(10.dp))
            Button({ vm.verifyOtp() }, enabled = !vm.busy && vm.code.length == 6, modifier = Modifier.fillMaxWidth()) { Text("Masuk") }
            TextButton({ vm.changeEmail() }, modifier = Modifier.fillMaxWidth()) { Text("Ganti email / kirim ulang") }
        }
    }
}

// ===================== Akun =====================
@Composable
private fun UsersTab(vm: AdminVm) {
    var adding by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                vm.query, { vm.query = it }, label = { Text("Cari email / nomor") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { vm.loadUsers() }),
            )
            Spacer(Modifier.width(8.dp))
            FilledTonalButton({ adding = true }) { Text("+ Akun") }
        }
        Spacer(Modifier.height(8.dp))
        if (vm.users.isEmpty() && !vm.busy) Text("Belum ada akun. Akun muncul otomatis saat pengguna pertama kali memeriksa lisensi, atau tambahkan manual.", color = Sub)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.users, key = { it.getString("ident") }) { u ->
                val st = u.optString("status")
                Card(Modifier.fillMaxWidth().clickable { vm.openUser(u.getString("ident")) }) {
                    Column(Modifier.padding(14.dp)) {
                        Text(u.getString("ident"), fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                            Text(u.optString("tier").uppercase(), color = Amber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(
                                st.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                color = when (st) { "blocked" -> Danger; "suspended" -> Amber; else -> Good },
                            )
                            Text("${u.optInt("deviceCount")} perangkat", fontSize = 12.sp, color = Sub)
                        }
                        val vu = if (u.isNull("validUntil")) "tanpa batas" else fmtDay(u.getLong("validUntil"))
                        val ls = u.optLong("lastSeen")
                        Text("Berlaku: $vu  ·  Terakhir aktif: ${if (ls > 0) fmt(ls) else "belum pernah"}", fontSize = 12.sp, color = Sub, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
    if (adding) {
        var id by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Tambah akun") },
            text = {
                OutlinedTextField(id, { id = it }, label = { Text("Email atau nomor (628…)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            },
            confirmButton = { TextButton({ adding = false; vm.addUser(id) }, enabled = id.isNotBlank()) { Text("Tambah") } },
            dismissButton = { TextButton({ adding = false }) { Text("Batal") } },
        )
    }
}

@Composable
private fun Chips(options: List<String>, selected: String, onPick: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> FilterChip(selected == o, { onPick(o) }, label = { Text(o) }) }
    }
}

@Composable
private fun UserEditor(vm: AdminVm, detail: JSONObject) {
    BackHandler { vm.closeEditor() }
    val u = detail.getJSONObject("user")
    val ident = u.getString("ident")
    val devices = detail.optJSONArray("devices").objs()
    var tier by remember(detail) { mutableStateOf(u.optString("tier")) }
    var status by remember(detail) { mutableStateOf(u.optString("status")) }
    var until by remember(detail) { mutableStateOf<Long?>(if (u.isNull("validUntil")) null else u.getLong("validUntil")) }
    var add by remember(detail) { mutableStateOf(u.optJSONArray("featuresAdd").strs().toSet()) }
    var rem by remember(detail) { mutableStateOf(u.optJSONArray("featuresRemove").strs().toSet()) }
    var msg by remember(detail) { mutableStateOf(u.optString("msg")) }
    var confirmBlock by remember { mutableStateOf(false) }
    val blocked = u.optString("status") == "blocked"

    fun extend(days: Int) { until = maxOf(System.currentTimeMillis(), until ?: 0L) + days * DAY }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton({ vm.closeEditor() }) { Text("‹ Kembali") }
        }
        Text(ident, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        val ls = u.optLong("lastSeen")
        Text(
            "Dibuat ${fmtDay(u.optLong("createdAt"))}  ·  Aktif terakhir: ${if (ls > 0) fmt(ls) else "belum pernah"}  ·  Versi app: ${u.optInt("appVersion")}",
            fontSize = 12.sp, color = Sub,
        )

        H("PAKET")
        Chips(vm.tiers.keys.toList(), tier) { tier = it }

        H("STATUS AKUN")
        Chips(listOf("active", "suspended", "blocked"), status) { status = it }
        Text(
            when (status) {
                "active" -> "Fitur sesuai paket."
                "suspended" -> "Fitur berbayar dicabut (turun ke Basic), aplikasi tetap jalan."
                else -> "Aplikasi menampilkan layar terkunci."
            }, fontSize = 12.sp, color = Sub, modifier = Modifier.padding(top = 4.dp),
        )

        H("MASA BERLAKU PAKET")
        Text(until?.let { fmtDay(it) } ?: "Tanpa batas", fontWeight = FontWeight.SemiBold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip({ extend(30) }, label = { Text("+30 hari") })
            AssistChip({ extend(90) }, label = { Text("+90 hari") })
            AssistChip({ extend(365) }, label = { Text("+1 tahun") })
            AssistChip({ until = null }, label = { Text("Tanpa batas") })
        }

        H("OVERRIDE FITUR (di luar paket)")
        vm.features.forEach { f ->
            val inTier = vm.tiers[tier].orEmpty().contains(f)
            val state = if (f in add) "+" else if (f in rem) "−" else "paket"
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f, fontSize = 13.sp)
                    Text(if (inTier) "termasuk di paket $tier" else "tidak di paket $tier", fontSize = 11.sp, color = Sub)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(state == "paket", { add = add - f; rem = rem - f }, label = { Text("Paket") })
                    FilterChip(state == "+", { add = add + f; rem = rem - f }, label = { Text("+") })
                    FilterChip(state == "−", { rem = rem + f; add = add - f }, label = { Text("−") })
                }
            }
        }

        H("PESAN KE PENGGUNA")
        OutlinedTextField(msg, { if (it.length <= 200) msg = it }, label = { Text("Tampil di layar terkunci / banner") }, modifier = Modifier.fillMaxWidth(), minLines = 2)

        Spacer(Modifier.height(14.dp))
        Button(
            {
                vm.saveUser(
                    ident,
                    JSONObject().put("tier", tier).put("status", status)
                        .put("validUntil", until ?: JSONObject.NULL)
                        .put("featuresAdd", JSONArray(add.toList())).put("featuresRemove", JSONArray(rem.toList()))
                        .put("msg", msg),
                )
            },
            enabled = !vm.busy, modifier = Modifier.fillMaxWidth(),
        ) { Text("Simpan perubahan") }
        Spacer(Modifier.height(8.dp))
        if (blocked) OutlinedButton({ vm.setBlocked(ident, false) }, modifier = Modifier.fillMaxWidth()) { Text("Buka kunci akun") }
        else OutlinedButton({ confirmBlock = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger)) { Text("Kunci penuh akun") }

        H("PERANGKAT (maks. ${vm.maxDevices})")
        if (devices.isEmpty()) Text("Belum ada perangkat.", color = Sub, fontSize = 13.sp)
        devices.forEach { d ->
            val revoked = d.optBoolean("revoked")
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(d.getString("deviceId").take(18), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Terakhir ${fmt(d.optLong("lastSeen"))}  ·  v${d.optInt("appVersion")}" + if (revoked) "  ·  DICABUT" else "",
                        fontSize = 11.sp, color = if (revoked) Danger else Sub,
                    )
                }
                if (!revoked) TextButton({ vm.revokeDevice(ident, d.getString("deviceId")) }) { Text("Cabut", color = Danger) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmBlock) AlertDialog(
        onDismissRequest = { confirmBlock = false },
        title = { Text("Kunci penuh akun ini?") },
        text = { Text("Aplikasi pengguna akan menampilkan layar terkunci saat berikutnya memeriksa lisensi (atau saat lisensi offline habis).") },
        confirmButton = { TextButton({ confirmBlock = false; vm.setBlocked(ident, true) }) { Text("Kunci", color = Danger) } },
        dismissButton = { TextButton({ confirmBlock = false }) { Text("Batal") } },
    )
}

// ===================== Voucher =====================
@Composable
private fun VouchersTab(vm: AdminVm) {
    var tier by remember { mutableStateOf("premium") }
    var days by remember { mutableStateOf("30") }
    var maxUse by remember { mutableStateOf("1") }
    var exp by remember { mutableStateOf("") }
    val clip = LocalClipboardManager.current
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column {
                Spacer(Modifier.height(12.dp))
                Text("Buat kode aktivasi", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                H("PAKET")
                Chips(vm.tiers.keys.filter { it != "basic" }, tier) { tier = it }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(days, { days = it.filter(Char::isDigit).take(4) }, label = { Text("Durasi (hari)") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(maxUse, { maxUse = it.filter(Char::isDigit).take(4) }, label = { Text("Batas pakai") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                OutlinedTextField(exp, { exp = it.filter(Char::isDigit).take(4) }, label = { Text("Kode kedaluwarsa dalam … hari (kosong = tidak)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Button(
                    { vm.createVoucher(tier, days.toIntOrNull() ?: 0, maxUse.toIntOrNull() ?: 0, exp.toIntOrNull()) },
                    enabled = !vm.busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text("Buat kode") }
                H("DAFTAR KODE")
            }
        }
        items(vm.vouchers, key = { it.getString("code") }) { v ->
            val dead = v.optBoolean("revoked")
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(v.getString("code"), fontWeight = FontWeight.SemiBold, color = if (dead) Sub else MaterialTheme.colorScheme.onSurface)
                        Text(
                            "${v.optString("tier")} · ${v.optInt("days")} hari · dipakai ${v.optInt("used")}/${v.optInt("maxUse")}" + if (dead) " · DICABUT" else "",
                            fontSize = 12.sp, color = if (dead) Danger else Sub,
                        )
                    }
                    if (!dead) TextButton({ clip.setText(AnnotatedString(v.getString("code"))) }) { Text("Salin") }
                    if (!dead) TextButton({ vm.revokeVoucher(v.getString("code")) }) { Text("Cabut", color = Danger) }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
    vm.newVoucher?.let { v ->
        AlertDialog(
            onDismissRequest = { vm.newVoucher = null },
            title = { Text("Kode dibuat") },
            text = { Text(v.getString("code"), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Amber) },
            confirmButton = { TextButton({ clip.setText(AnnotatedString(v.getString("code"))); vm.newVoucher = null }) { Text("Salin & tutup") } },
            dismissButton = { TextButton({ vm.newVoucher = null }) { Text("Tutup") } },
        )
    }
}

// ===================== Log =====================
@Composable
private fun LogTab(vm: AdminVm) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Spacer(Modifier.height(12.dp)); Text("Log admin", fontWeight = FontWeight.Bold, fontSize = 18.sp); Text("Hanya bisa bertambah; tiap entri dirantai hash di server.", fontSize = 12.sp, color = Sub) }
        items(vm.log, key = { it.getString("h") }) { e ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${e.optString("action")}  →  ${e.optString("target").ifEmpty { "—" }}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text("${fmt(e.optLong("ts"))}  ·  ${e.optString("admin")}", fontSize = 11.sp, color = Sub)
                    val d = e.optJSONObject("detail")?.toString().orEmpty()
                    if (d.length > 2) Text(d.take(300), fontSize = 11.sp, color = Sub, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

// ===================== Pengaturan =====================
@Composable
private fun SettingsTab(vm: AdminVm) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Spacer(Modifier.height(12.dp))
        Text("Pengaturan", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        H("SERVER")
        Text(vm.url, fontSize = 13.sp)

        H("VERSI APK MINIMUM (versionCode)")
        Text("Aplikasi pengguna dengan versi lebih rendah diminta update. 0 = tidak dipaksa.", fontSize = 12.sp, color = Sub)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(vm.minVersion, { vm.minVersion = it.filter(Char::isDigit).take(6) }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Button({ vm.saveMinVersion() }, enabled = !vm.busy) { Text("Simpan") }
        }

        H("ISI PAKET (diatur di server)")
        vm.tiers.forEach { (t, fs) ->
            Text(t.uppercase(), color = Amber, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            Text(if (fs.isEmpty()) "Fitur inti saja" else fs.joinToString(", "), fontSize = 11.sp, color = Sub)
        }

        Spacer(Modifier.height(24.dp))
        OutlinedButton({ vm.logout() }, modifier = Modifier.fillMaxWidth()) { Text("Keluar") }
    }
}
