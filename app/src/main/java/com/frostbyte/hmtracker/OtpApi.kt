package com.frostbyte.hmtracker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Klien backend OTP. Kontrak API (JSON, HTTPS) — lihat server/otp-server/README.md:
 *   POST /otp/send   {"channel":"email"|"wa","ident":"..."} -> {"ok":true,"requestId":"..."}
 *   POST /otp/verify {"requestId":"...","code":"123456"}      -> {"ok":true}
 * Galat: status 4xx/5xx dengan {"ok":false,"error":"pesan"}.
 * Bila HM_OTP_API_URL kosong saat build, [enabled] = false dan layar login memakai mode demo lokal.
 */
object OtpApi {
    class Result(val ok: Boolean, val requestId: String = "", val msg: String = "")

    val enabled: Boolean get() = BuildConfig.OTP_API_URL.isNotBlank()

    suspend fun send(channel: String, ident: String): Result =
        call("/otp/send", JSONObject().put("channel", channel).put("ident", ident))

    suspend fun verify(requestId: String, code: String): Result =
        call("/otp/verify", JSONObject().put("requestId", requestId).put("code", code))

    private suspend fun call(path: String, body: JSONObject): Result = withContext(Dispatchers.IO) {
        try {
            val c = URL(BuildConfig.OTP_API_URL.trimEnd('/') + path).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 10_000; c.readTimeout = 15_000
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = c.responseCode
                val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                val j = runCatching { JSONObject(txt) }.getOrNull()
                if (code in 200..299 && j?.optBoolean("ok") == true) Result(true, j.optString("requestId"))
                else Result(false, msg = j?.optString("error").orEmpty().ifEmpty { "Gagal ($code)" })
            } finally { c.disconnect() }
        } catch (e: Exception) { Result(false, msg = "Tidak bisa terhubung ke server OTP") }
    }
}
