package org.dandeliondaily.sundial.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** The server answered with an error. `code` is Dandelion's `errorCode` (device_pending, device_revoked…). */
class ApiException(val status: Int, val code: String, override val message: String) : Exception(message) {
    val isRevoked get() = status == 401
    val isPending get() = code == "device_pending"
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

    private suspend fun call(method: String, path: String, body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val url = URL(apiRoot(baseUrl) + path)
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("Accept", "application/json")
                deviceKey?.let { conn.setRequestProperty("X-Api-Key", it) }
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
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
