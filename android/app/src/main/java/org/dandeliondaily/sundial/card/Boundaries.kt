package org.dandeliondaily.sundial.card

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.dandeliondaily.sundial.TAG
import org.dandeliondaily.sundial.auth.DeviceStore
import org.dandeliondaily.sundial.day.DayParts
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * The silent part-of-day switches (Wake 4:00, Morning 8:00, Afternoon 12:00, Evening 20:00): one
 * exact alarm for the next switch; each switch schedules the one after. Step 0 measured these on the
 * S23: on time, overnight, while dozing.
 */
object Boundaries {
    fun nextSwitch(parts: DayParts, now: LocalDateTime): LocalDateTime {
        val today = now.toLocalDate()
        val candidates = listOf(parts.wake, parts.morning, parts.afternoon, parts.evening)
            .flatMap { listOf(today.atTime(it), today.plusDays(1).atTime(it)) }
            .filter { it.isAfter(now) }
        return candidates.min()
    }

    fun scheduleNext(ctx: Context, parts: DayParts) {
        val at = nextSwitch(parts, LocalDateTime.now())
        val ms = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        }
        Log.i(TAG, "next part-of-day switch at $at")
        RefreshWorker.ensure(ctx)
    }

    fun cancel(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx))
        WorkManager.getInstance(ctx).cancelUniqueWork(RefreshWorker.NAME)
    }

    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, BoundaryReceiver::class.java).setAction("switch"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** A part of the day starts: the card silently changes (and comes back if it was swiped away). */
class BoundaryReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                CardStore(app).unhide()
                CardStore(app).setReceipt(null)
                CardEngine.refresh(app, "switch to ${CardEngine.now().part.label}")
            } finally {
                pending.finish()
            }
        }
    }
}

/** After a reboot, an app update or a clock change: draw the card from what's stored, then refresh. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        if (DeviceStore(app).load() == null) return
        CardEngine.post(app, reason = intent.action?.substringAfterLast('.') ?: "boot")
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                CardEngine.refresh(app, intent.action?.substringAfterLast('.') ?: "boot")
            } finally {
                pending.finish()
            }
        }
    }
}

/** Every 15 minutes (Android's minimum) with a network: refetch, so desktop changes reach the card. */
class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        CardEngine.refresh(applicationContext, "15-minute refresh")
        return Result.success()
    }

    companion object {
        const val NAME = "card-refresh"

        fun ensure(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
