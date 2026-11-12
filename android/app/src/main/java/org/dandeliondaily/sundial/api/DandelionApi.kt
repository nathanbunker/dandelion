package org.dandeliondaily.sundial.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/** The server answered with an error. `code` is Dandelion's `errorCode` (device_pending, device_revoked…). */
class ApiException(val status: Int, val code: String, override val message: String) : Exception(message) {
    val isRevoked get() = status == 401
    val isPending get() = code == "device_pending"
    /** Already completed or cancelled (often on the desktop). */
    val isNotOpen get() = code == "not_open"
}

/**
 * Talks to Dandelion's `/api/v1`. Plain HttpURLConnection and org.json: the app makes a handful of
 * small calls, so it doesn't need Retrofit. Network failures surface as IOException (the offline strip).
 */
class DandelionApi(private val baseUrl: String, private val deviceKey: String?) {

    suspend fun signIn(username: String, password: String, deviceName: String): String {
        val body = JSONObject()
            .put("username", username)
            .put("password", password)
            .put("deviceName", deviceName)
        return call("POST", "v1/devices/sign-in", body).getString("deviceKey")
    }

    suspend fun device(): DeviceInfo = Json.device(call("GET", "v1/devices/me"))

    /** Forgets this phone on the server. Used by "Sign out of this phone". */
    suspend fun signOut() {
        call("DELETE", "v1/devices/me")
    }

    suspend fun me(): Me = Json.me(call("GET", "v1/me"))

    suspend fun day(date: String): Day =
        Json.day(call("GET", "v1/personal/day?date=" + URLEncoder.encode(date, "UTF-8")))

    suspend fun action(id: Int): Action = Json.action(call("GET", "v1/personal/actions/$id"))

    // ---- Writes. Each carries an Idempotency-Key; a dropped connection is retried once with the same
    // key, so the server applies it at most once. ----

    suspend fun complete(id: Int): Changed = Json.changed(write("v1/personal/actions/$id/complete"))
    suspend fun cancel(id: Int): Changed = Json.changed(write("v1/personal/actions/$id/cancel"))

    suspend fun move(id: Int, date: String, part: Part?): Changed {
        val body = JSONObject().put("date", date)
        if (part != null) body.put("part", part.id)
        return Json.changed(write("v1/personal/actions/$id/move", body))
    }

    suspend fun addNote(id: Int, text: String): Action =
        Json.action(write("v1/personal/actions/$id/notes", JSONObject().put("text", text)).getJSONObject("action"))

    suspend fun undo(token: String): Action? {
        val json = write("v1/personal/undo/" + URLEncoder.encode(token, "UTF-8"))
        return json.optJSONObject("action")?.let { Json.action(it) }
    }

    private suspend fun write(path: String, body: JSONObject? = null): JSONObject {
        val key = UUID.randomUUID().toString()
        return try {
            call("POST", path, body, key)
        } catch (e: IOException) {
            call("POST", path, body, key)
        }
    }

    private suspend fun call(method: String, path: String, body: JSONObject? = null, idempotencyKey: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val url = URL(apiRoot(baseUrl) + path)
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("Accept", "application/json")
                deviceKey?.let { conn.setRequestProperty("X-Api-Key", it) }
                idempotencyKey?.let { conn.setRequestProperty("Idempotency-Key", it) }
                if (body != null || method == "POST") {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.outputStream.use { it.write((body ?: JSONObject()).toString().toByteArray(Charsets.UTF_8)) }
                }
                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (status !in 200..299) throw error(status, text)
                if (text.isBlank()) JSONObject() else JSONObject(text)
            } finally {
                conn.disconnect()
            }
        }

    companion object {
        const val PRODUCTION = "https://dandelion-daily.org/"
        /** The phone's localhost is the dev machine after `adb reverse tcp:8089 tcp:8089`. */
        const val LOCAL = "http://localhost:8089/dandelion/"

        fun apiRoot(baseUrl: String) = baseUrl.trimEnd('/') + "/api/"

        internal fun error(status: Int, text: String): ApiException {
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                null
            }
            val code = json?.optString("errorCode")?.takeIf { it.isNotBlank() } ?: "http_$status"
            val message = json?.optString("message")?.takeIf { it.isNotBlank() } ?: "Dandelion answered $status."
            return ApiException(status, code, message)
        }
    }
}

/** True when the failure means "couldn't reach Dandelion" rather than "Dandelion said no". */
fun Throwable.isNetwork() = this is IOException
