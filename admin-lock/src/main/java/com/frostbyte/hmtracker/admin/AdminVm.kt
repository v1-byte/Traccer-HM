package com.frostbyte.hmtracker.admin

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder

class AdminVm(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("adm", 0)
    private val api = AdminApi(prefs.getString("url", null) ?: BuildConfig.LICENSE_API_URL)

    var url by mutableStateOf(api.baseUrl)
    var email by mutableStateOf(prefs.getString("email", "") ?: "")
    var code by mutableStateOf("")
    var requestId by mutableStateOf<String?>(null)
    var loggedIn by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)

    var tab by mutableIntStateOf(0)
    var query by mutableStateOf("")
    var users by mutableStateOf<List<JSONObject>>(emptyList())
    var editing by mutableStateOf<JSONObject?>(null)          // balasan detail: {user, devices}
    var vouchers by mutableStateOf<List<JSONObject>>(emptyList())
    var newVoucher by mutableStateOf<JSONObject?>(null)
    var log by mutableStateOf<List<JSONObject>>(emptyList())
    var tiers by mutableStateOf<Map<String, List<String>>>(emptyMap())
    var features by mutableStateOf<List<String>>(emptyList())
    var maxDevices by mutableIntStateOf(0)
    var minVersion by mutableStateOf("0")

    private fun go(block: suspend () -> Unit) {
        viewModelScope.launch { busy = true; try { block() } finally { busy = false } }
    }

    private suspend fun req(method: String, path: String, body: JSONObject? = null): JSONObject? {
        val r = api.call(method, path, body)
        if (r.ok) return r.json
        message = r.msg
        if (r.code == 401) { loggedIn = false; api.token = null; requestId = null }
        return null
    }

    // ---- masuk ----
    fun sendOtp() = go {
        api.baseUrl = url
        val j = req("POST", "/admin/login/send", JSONObject().put("email", email.trim().lowercase())) ?: return@go
        prefs.edit().putString("url", url.trim()).putString("email", email.trim().lowercase()).apply()
        requestId = j.optString("requestId"); code = ""
        message = "Jika email terdaftar sebagai admin, kode OTP dikirim."
    }

    fun verifyOtp() = go {
        val id = requestId ?: return@go
        val j = req("POST", "/admin/login/verify", JSONObject().put("requestId", id).put("code", code.trim())) ?: return@go
        api.token = j.optString("token"); loggedIn = true; code = ""
        loadMeta(); loadUsers()
    }

    fun logout() = go {
        req("POST", "/admin/logout")
        api.token = null; loggedIn = false; requestId = null; editing = null; users = emptyList()
    }

    fun changeEmail() { requestId = null; code = "" }

    private suspend fun loadMeta() {
        req("GET", "/admin/tiers")?.let { j ->
            val t = j.getJSONObject("tiers")
            tiers = t.keys().asSequence().associateWith { t.getJSONArray(it).strs() }
            features = j.getJSONArray("features").strs(); maxDevices = j.optInt("maxDevices")
        }
        req("GET", "/admin/config")?.let { minVersion = it.optInt("minVersionCode").toString() }
    }

    // ---- pengguna ----
    fun loadUsers() = go {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        req("GET", "/admin/users?q=$q")?.let { users = it.getJSONArray("users").objs() }
    }

    fun openUser(ident: String) = go {
        req("GET", "/admin/users/" + URLEncoder.encode(ident, "UTF-8"))?.let { editing = it }
    }

    /** Membuat akun baru (atau membuka yang ada) tanpa mengubah apa pun. */
    fun addUser(ident: String) = go {
        req("PATCH", "/admin/users/" + URLEncoder.encode(ident.trim().lowercase(), "UTF-8"), JSONObject())?.let { editing = it }
        refreshUsers()
    }

    fun saveUser(ident: String, fields: JSONObject) = go {
        req("PATCH", "/admin/users/" + URLEncoder.encode(ident, "UTF-8"), fields)?.let { editing = it; message = "Tersimpan"; refreshUsers() }
    }

    fun setBlocked(ident: String, blocked: Boolean) = go {
        val a = if (blocked) "block" else "unblock"
        req("POST", "/admin/users/" + URLEncoder.encode(ident, "UTF-8") + "/" + a)?.let {
            editing = it; message = if (blocked) "Akun dikunci" else "Kunci dibuka"; refreshUsers()
        }
    }

    fun revokeDevice(ident: String, deviceId: String) = go {
        req("DELETE", "/admin/users/" + URLEncoder.encode(ident, "UTF-8") + "/devices/" + URLEncoder.encode(deviceId, "UTF-8"))
        req("GET", "/admin/users/" + URLEncoder.encode(ident, "UTF-8"))?.let { editing = it; message = "Perangkat dicabut"; refreshUsers() }
    }

    fun closeEditor() { editing = null }

    private suspend fun refreshUsers() {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        req("GET", "/admin/users?q=$q")?.let { users = it.getJSONArray("users").objs() }
    }

    // ---- voucher ----
    fun loadVouchers() = go { req("GET", "/admin/vouchers")?.let { vouchers = it.getJSONArray("vouchers").objs() } }

    fun createVoucher(tier: String, days: Int, maxUse: Int, expiresInDays: Int?) = go {
        val b = JSONObject().put("tier", tier).put("days", days).put("maxUse", maxUse)
        if (expiresInDays != null) b.put("expiresInDays", expiresInDays)
        req("POST", "/admin/vouchers", b)?.let { newVoucher = it.getJSONObject("voucher") }
        req("GET", "/admin/vouchers")?.let { vouchers = it.getJSONArray("vouchers").objs() }
    }

    fun revokeVoucher(code: String) = go {
        req("POST", "/admin/vouchers/" + URLEncoder.encode(code, "UTF-8") + "/revoke")
        req("GET", "/admin/vouchers")?.let { vouchers = it.getJSONArray("vouchers").objs() }
    }

    // ---- log & pengaturan ----
    fun loadLog() = go { req("GET", "/admin/log?limit=200")?.let { log = it.getJSONArray("log").objs() } }

    fun saveMinVersion() = go {
        val n = minVersion.trim().toIntOrNull()
        if (n == null || n < 0) { message = "Isi angka versi (versionCode)"; return@go }
        req("PATCH", "/admin/config", JSONObject().put("minVersionCode", n))?.let { message = "Versi minimum disimpan" }
    }

}
