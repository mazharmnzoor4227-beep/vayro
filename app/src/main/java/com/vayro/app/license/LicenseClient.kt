package com.vayro.app.license

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Talks to the Kodex license server (Cloudflare Worker). All calls are best-effort:
 *  a null result always means "network/server unreachable", never "license invalid". */
object LicenseClient {
    private const val BASE = "https://kodex-license.mazharmnzoor4227.workers.dev"

    data class ActivateResult(
        val ok: Boolean,
        val error: String?,
        val token: String?,
        val expiresAt: Long,
        val mode: String
    )

    data class CheckResult(
        val ok: Boolean,
        val status: String,
        val expiresAt: Long,
        val type: String?,
        val mode: String
    )

    suspend fun fetchMode(): String? = withContext(Dispatchers.IO) {
        val obj = get("/api/mode") ?: return@withContext null
        obj.optString("mode", "off")
    }

    suspend fun activate(code: String, device: String): ActivateResult? = withContext(Dispatchers.IO) {
        val body = JSONObject().put("code", code).put("device", device)
        val obj = post("/api/activate", body) ?: return@withContext null
        ActivateResult(
            ok = obj.optBoolean("ok", false),
            error = if (obj.has("error")) obj.optString("error") else null,
            token = if (obj.has("token")) obj.optString("token") else null,
            expiresAt = obj.optLong("expiresAt", 0L),
            mode = obj.optString("mode", "off")
        )
    }

    suspend fun check(device: String, token: String): CheckResult? = withContext(Dispatchers.IO) {
        val body = JSONObject().put("device", device).put("token", token)
        val obj = post("/api/check", body) ?: return@withContext null
        CheckResult(
            ok = obj.optBoolean("ok", false),
            status = obj.optString("status", "unknown"),
            expiresAt = obj.optLong("expiresAt", 0L),
            type = if (obj.has("type")) obj.optString("type") else null,
            mode = obj.optString("mode", "off")
        )
    }

    private fun get(path: String): JSONObject? = runCatching {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        JSONObject(text)
    }.getOrNull()

    private fun post(path: String, body: JSONObject): JSONObject? = runCatching {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        conn.disconnect()
        JSONObject(text)
    }.getOrNull()
}
