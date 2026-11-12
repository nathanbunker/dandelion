package org.dandeliondaily.sundial

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.dandeliondaily.sundial.api.Action
import org.dandeliondaily.sundial.api.ApiException
import org.dandeliondaily.sundial.api.DandelionApi
import org.dandeliondaily.sundial.api.Day
import org.dandeliondaily.sundial.api.Me
import org.dandeliondaily.sundial.api.isNetwork
import org.dandeliondaily.sundial.auth.DeviceStore
import org.dandeliondaily.sundial.auth.Saved
import org.dandeliondaily.sundial.day.DayLogic
import org.dandeliondaily.sundial.day.DayParts
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

const val TAG = "Sundial"

sealed interface Screen {
    data object Starting : Screen
    data class SignIn(val server: String, val username: String, val busy: Boolean = false, val error: String? = null) : Screen
    data class Pending(val deviceName: String, val server: String, val offline: Boolean = false) : Screen
    data class NeedsUpdate(val serverLevel: Int) : Screen
    data class Ready(val me: Me) : Screen
}

data class DayState(
    val date: LocalDate? = null,
    val day: Day? = null,
    val loading: Boolean = false,
    /** Can't reach Dandelion; the app keeps showing what it last had and retries. */
    val offline: Boolean = false,
    val error: String? = null,
)

data class DetailState(val id: Int, val action: Action? = null, val loading: Boolean = true, val error: String? = null)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = DeviceStore(app)
    val parts = DayParts()

    private val _screen = MutableStateFlow<Screen>(Screen.Starting)
    val screen: StateFlow<Screen> = _screen

    private val _day = MutableStateFlow(DayState())
    val day: StateFlow<DayState> = _day

    private val _detail = MutableStateFlow<DetailState?>(null)
    val detail: StateFlow<DetailState?> = _detail

    private var saved: Saved? = null
    private var api: DandelionApi? = null
    private var dayJob: Job? = null
    private var pollJob: Job? = null
    private var retryJob: Job? = null

    init {
        start()
    }

    private fun start() {
        val s = store.load()
        if (s == null) {
            _screen.value = signInScreen()
            return
        }
        useDevice(s)
        checkDevice()
    }

    private fun signInScreen(error: String? = null) = Screen.SignIn(
        server = store.lastServer() ?: if (BuildConfig.ALLOW_LOCAL) DandelionApi.LOCAL else DandelionApi.PRODUCTION,
        username = store.lastUsername(),
        error = error,
    )

    private fun useDevice(s: Saved) {
        saved = s
        api = DandelionApi(s.serverUrl, s.deviceKey)
    }

    fun defaultDeviceName(): String = "Sundial on " + (Build.MODEL ?: "Android")

    fun signIn(server: String, username: String, password: String, deviceName: String) {
        val current = _screen.value as? Screen.SignIn ?: return
        if (username.isBlank() || password.isEmpty()) {
            _screen.value = current.copy(error = "Enter your Dandelion username and password.")
            return
        }
        _screen.value = current.copy(server = server, username = username, busy = true, error = null)
        store.rememberServer(server)
        viewModelScope.launch {
            try {
                val name = deviceName.ifBlank { defaultDeviceName() }
                val key = DandelionApi(server, null).signIn(username.trim(), password, name)
                val s = Saved(server, key, username.trim(), name)
                store.save(s)
                useDevice(s)
                Log.i(TAG, "signed in; waiting for approval")
                checkDevice()
            } catch (e: Exception) {
                val msg = when {
                    e.isNetwork() -> "Can't reach Dandelion at $server." +
                        if (server == DandelionApi.LOCAL) " Is the phone on USB with adb reverse tcp:8089 tcp:8089?" else ""
                    e is ApiException && e.code == "invalid_credentials" -> "That username and password didn't work."
                    e is ApiException && e.code == "too_many_attempts" -> "Too many tries. Wait 15 minutes and try again."
                    else -> e.message ?: "Sign-in failed."
                }
                _screen.value = signInScreen(msg).copy(server = server, username = username)
            }
        }
    }

    /** Asks the server whether this phone is approved, and moves to the right screen. */
    private fun checkDevice() {
        val s = saved ?: return
        viewModelScope.launch {
            try {
                val device = api!!.device()
                if (device.isApproved) {
                    pollJob?.cancel()
                    loadMe()
                } else {
                    _screen.value = Screen.Pending(s.deviceName, s.serverUrl)
                    pollForApproval()
                }
            } catch (e: Exception) {
                handle(e) {
                    // Offline at start: show whatever screen fits and retry.
                    if (_screen.value is Screen.Starting) _screen.value = Screen.Pending(s.deviceName, s.serverUrl, offline = true)
                    scheduleRetry { checkDevice() }
                }
            }
        }
    }

    private fun pollForApproval() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (true) {
                delay(4_000)
                try {
                    if (api!!.device().isApproved) {
                        Log.i(TAG, "device approved")
                        loadMe()
                        return@launch
                    }
                    (_screen.value as? Screen.Pending)?.let { _screen.value = it.copy(offline = false) }
                } catch (e: Exception) {
                    handle(e) { (_screen.value as? Screen.Pending)?.let { _screen.value = it.copy(offline = true) } }
                    if (e is ApiException) return@launch
                }
            }
        }
    }

    private suspend fun loadMe() {
        try {
            val me = api!!.me()
            if (me.apiLevel < MIN_API_LEVEL) {
                _screen.value = Screen.NeedsUpdate(me.apiLevel)
                return
            }
            _screen.value = Screen.Ready(me)
            goToday()
        } catch (e: Exception) {
            handle(e) { scheduleRetry { viewModelScope.launch { loadMe() } } }
        }
    }

    /** Today in Sundial's sense: before Wake (4:00) it is still yesterday's Evening. */
    fun sundialNow() = parts.now(LocalDateTime.now(zone()))

    private fun zone(): ZoneId {
        val me = (_screen.value as? Screen.Ready)?.me ?: return ZoneId.systemDefault()
        return try {
            ZoneId.of(me.timeZone)
        } catch (e: Exception) {
            ZoneId.systemDefault()
        }
    }

    fun goToday() = show(sundialNow().date)

    private var lastNow: LocalDate? = null

    /** Opening the app refreshes; if the Sundial day rolled over while it was away, jump to the new today. */
    fun onResume() {
        if (_screen.value !is Screen.Ready && _screen.value !is Screen.Pending) return
        val now = sundialNow().date
        val was = lastNow
        lastNow = now
        if (was == null) return // first resume: start() is already loading
        if (was != now && _day.value.date == was) show(now) else refresh()
    }
    fun step(days: Long) = _day.value.date?.let { show(it.plusDays(days)) }

    fun show(date: LocalDate) {
        _day.update { if (it.date == date) it.copy(loading = true) else DayState(date = date, loading = true, offline = it.offline) }
        loadDay(date)
    }

    fun refresh() {
        val ready = _screen.value
        if (ready is Screen.Pending) {
            checkDevice()
            return
        }
        _day.value.date?.let { show(it) } ?: goToday()
        _detail.value?.let { openAction(it.id) }
    }

    private fun loadDay(date: LocalDate) {
        dayJob?.cancel()
        dayJob = viewModelScope.launch {
            try {
                val day = api!!.day(DayLogic.key(date))
                if (_day.value.date == date) {
                    _day.value = DayState(date = date, day = day)
                }
            } catch (e: Exception) {
                handle(e) {
                    _day.update { it.copy(loading = false, offline = e.isNetwork(), error = if (e.isNetwork()) null else e.message) }
                    if (e.isNetwork()) scheduleRetry { loadDay(date) }
                }
            }
        }
    }

    fun openAction(id: Int) {
        _detail.value = DetailState(id, action = _detail.value?.takeIf { it.id == id }?.action)
        viewModelScope.launch {
            try {
                val a = api!!.action(id)
                if (_detail.value?.id == id) _detail.value = DetailState(id, a, loading = false)
            } catch (e: Exception) {
                handle(e) {
                    val msg = when {
                        e.isNetwork() -> "Can't reach Dandelion."
                        e is ApiException && e.status == 404 -> "This action is gone (done, moved or no longer personal)."
                        else -> e.message
                    }
                    if (_detail.value?.id == id) _detail.update { it?.copy(loading = false, error = msg) }
                }
            }
        }
    }

    fun closeAction() {
        _detail.value = null
    }

    fun signOut() {
        val a = api
        viewModelScope.launch {
            try {
                a?.signOut()
            } catch (e: Exception) {
                Log.w(TAG, "sign-out on the server failed: ${e.message}")
            }
        }
        forget(null)
    }

    /** Revoked, removed, or signed out: drop the key and go back to sign-in. */
    private fun forget(message: String?) {
        pollJob?.cancel()
        retryJob?.cancel()
        store.clear()
        saved = null
        api = null
        _day.value = DayState()
        _detail.value = null
        _screen.value = signInScreen(message)
    }

    /** Common handling: a revoked key signs out; anything else goes to the caller. */
    private inline fun handle(e: Exception, otherwise: () -> Unit) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.w(TAG, "call failed: ${e.javaClass.simpleName} ${e.message}")
        if (e is ApiException && e.isRevoked) {
            forget("This phone was signed out of Dandelion. Sign in again to use it.")
        } else {
            otherwise()
        }
    }

    private fun scheduleRetry(again: () -> Unit) {
        if (retryJob?.isActive == true) return
        retryJob = viewModelScope.launch {
            delay(15_000)
            again()
        }
    }

    companion object {
        /** The server API level this app needs (GET /me apiLevel). */
        const val MIN_API_LEVEL = 1
    }
}
