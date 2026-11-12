package org.dandeliondaily.sundial.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** What this phone remembers about its sign-in. */
data class Saved(val serverUrl: String, val deviceKey: String, val username: String, val deviceName: String)

/**
 * Keeps the device key, encrypted with an AES key that never leaves the Android Keystore. The rest
 * (server, username, device name) is plain preferences. Nothing here is backed up (allowBackup=false).
 */
class DeviceStore(context: Context) {
    private val prefs = context.getSharedPreferences("sundial_device", Context.MODE_PRIVATE)

    fun load(): Saved? {
        val server = prefs.getString(SERVER, null) ?: return null
        val sealed = prefs.getString(KEY, null) ?: return null
        val key = try {
            open(sealed)
        } catch (e: Exception) {
            // The Keystore key is gone (restored or wiped): sign in again.
            clear()
            return null
        }
        return Saved(server, key, prefs.getString(USERNAME, "") ?: "", prefs.getString(NAME, "") ?: "")
    }

    fun save(saved: Saved) {
        prefs.edit()
            .putString(SERVER, saved.serverUrl)
            .putString(KEY, seal(saved.deviceKey))
            .putString(USERNAME, saved.username)
            .putString(NAME, saved.deviceName)
            .apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).remove(SERVER).apply()
    }

    /** Remembered between sign-ins so the form is pre-filled. */
    fun lastUsername(): String = prefs.getString(USERNAME, "") ?: ""
    fun lastServer(): String? = prefs.getString(LAST_SERVER, null)
    fun rememberServer(url: String) = prefs.edit().putString(LAST_SERVER, url).apply()

    private fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return b64(iv) + ":" + b64(data)
    }

    private fun open(sealed: String): String {
        val (iv, data) = sealed.split(":", limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(data), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private companion object {
        const val ALIAS = "sundial_device_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val SERVER = "server"
        const val LAST_SERVER = "last_server"
        const val KEY = "device_key"
        const val USERNAME = "username"
        const val NAME = "device_name"
    }
}
