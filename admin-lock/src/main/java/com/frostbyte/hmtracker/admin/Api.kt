package com.frostbyte.hmtracker.admin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ApiResult(val ok: Boolean, val json: JSONObject?, val msg: String, val code: Int)

/**
 * Klien API admin server lisensi (lihat server/license-server). Token sesi hanya di memori:
 * menutup aplikasi = harus masuk lagi dengan OTP. PATCH dikirim sebagai POST + X-HTTP-Method-Override.
 */
class AdminApi(var baseUrl: String) {
    var token: String? = null

    suspend fun call(method: String, path: String, body: JSONObject? = null): ApiResult = withContext(Dispatchers.IO) {
        val base = baseUrl.trim().trimEnd('/')
        if (!base.startsWith("https://")) return@withContext ApiResult(false, null, "Alamat server harus diawali https://", 0)
        try {
            val c = URL(base + path).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 10_000; c.readTimeout = 15_000
                val patch = method == "PATCH"
                c.requestMethod = if (patch) "POST" else method
                if (patch) c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
                c.setRequestProperty("Content-Type", "application/json")
                token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
                if (c.requestMethod == "POST") {
                    c.doOutput = true
                    c.outputStream.use { it.write((body ?: JSONObject()).toString().toByteArray()) }
                }
                val code = c.responseCode
                val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                val j = runCatching { JSONObject(txt) }.getOrNull()
                if (code in 200..299 && j?.optBoolean("ok") == true) ApiResult(true, j, "", code)
                else ApiResult(false, j, j?.optString("error").orEmpty().ifEmpty { "Gagal ($code)" }, code)
            } finally { c.disconnect() }
        } catch (e: Exception) {
            ApiResult(false, null, "Tidak bisa terhubung ke server", 0)
        }
    }
}

fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else List(length()) { getJSONObject(it) }
fun JSONArray?.strs(): List<String> = if (this == null) emptyList() else List(length()) { getString(it) }
