package org.dandeliondaily.sundial.card

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.dandeliondaily.sundial.TAG
import org.dandeliondaily.sundial.api.ApiException
import org.dandeliondaily.sundial.api.DandelionApi
import org.dandeliondaily.sundial.auth.DeviceStore
import org.dandeliondaily.sundial.day.CardModel
import org.dandeliondaily.sundial.day.DayLogic
import org.dandeliondaily.sundial.day.DayParts
import org.dandeliondaily.sundial.day.SundialNow
import java.time.LocalDateTime

/**
 * Keeps the Now card current: fetches the Sundial day, stores it, draws the card and schedules the
 * next part-of-day switch. Called by the app, the card's buttons, the switch alarms, the 15-minute
 * worker and boot. One refresh at a time.
 */
object CardEngine {
    val parts = DayParts()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private const val PROJECTS_MAX_AGE_MS = 60 * 60 * 1000L

    fun now(): SundialNow = parts.now(LocalDateTime.now())

    /** The signed-in device's API, or null when signed out. */
    fun api(ctx: Context): DandelionApi? =
        DeviceStore(ctx).load()?.let { DandelionApi(it.serverUrl, it.deviceKey) }

    /** Refresh in the background (from the app, which doesn't wait). */
    fun kick(ctx: Context, reason: String) {
        val app = ctx.applicationContext
        scope.launch { refresh(app, reason) }
    }

    /** Fetch today, store it, re-draw the card, schedule the next switch. Never throws. */
    suspend fun refresh(ctx: Context, reason: String) {
        lock.withLock {
            val store = CardStore(ctx)
            val api = api(ctx)
            if (api == null) {
                NowCard.cancel(ctx)
                Boundaries.cancel(ctx)
                return
            }
            val now = now()
            try {
                withTimeoutOrNull(15_000) {
                    val json = api.dayJson(DayLogic.key(now.date))
                    store.saveDay(json, System.currentTimeMillis())
                    if (System.currentTimeMillis() - store.projectsAt() > PROJECTS_MAX_AGE_MS) {
                        val me = api.me()
                        store.setApiLevel(me.apiLevel)
                        if (me.apiLevel >= 3) store.saveProjects(api.projects(), System.currentTimeMillis())
                    }
                }
            } catch (e: ApiException) {
                if (e.isRevoked) {
                    Log.i(TAG, "card: device revoked; removing card")
                    NowCard.cancel(ctx)
                    Boundaries.cancel(ctx)
                    return
                }
                Log.w(TAG, "card refresh ($reason): ${e.code}")
            } catch (e: Exception) {
                Log.w(TAG, "card refresh ($reason): ${e.javaClass.simpleName} ${e.message}")
            }
            post(ctx, now, reason)
            Boundaries.scheduleNext(ctx, parts)
        }
    }

    /** Draw the card from what is stored, without fetching (after a change, before the refetch). */
    fun post(ctx: Context, now: SundialNow = now(), reason: String) {
        val store = CardStore(ctx)
        if (DeviceStore(ctx).load() == null) {
            NowCard.cancel(ctx)
            return
        }
        if (store.isHidden(now)) {
            Log.i(TAG, "card hidden until the next part of the day ($reason)")
            return
        }
        val state = CardModel.build(now, store.day(), store.receipt(), System.currentTimeMillis(), store.fetchedAt())
        NowCard.post(ctx, state, store)
        Log.i(TAG, "card posted ($reason): ${state.title} · rows=${state.rows.size} more=${state.more}")
    }

    /** Signed out: forget everything the card kept and remove it. */
    fun signedOut(ctx: Context) {
        CardStore(ctx).clear()
        NowCard.cancel(ctx)
        Boundaries.cancel(ctx)
    }
}
