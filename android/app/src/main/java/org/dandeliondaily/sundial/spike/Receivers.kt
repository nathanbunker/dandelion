package org.dandeliondaily.sundial.spike

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun locked(ctx: Context) = ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked

/** Taps on the card. Each one logs whether the phone was locked when it ran. */
class CardReceiver : BroadcastReceiver() {
    companion object {
        const val DONE = "done"
        const val UNDO = "undo"
        const val REFRESH = "refresh"
        const val CAPTURE = "capture"
        const val ALL_TOMORROW = "all_tomorrow"
        const val DISMISSED = "dismissed"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val isLocked = locked(ctx)
        val id = intent.getStringExtra(NowCard.EXTRA_ID)
        val text = id?.let { i -> Spike.items(ctx).find { it.id == i }?.text } ?: id
        when (intent.action) {
            DONE -> {
                Spike.setDone(ctx, id!!, true)
                EventLog.add(ctx, "✔ ran: \"$text\" · locked=$isLocked")
                NowCard.post(ctx, alert = false, reason = "done")
            }
            UNDO -> {
                Spike.setDone(ctx, id!!, false)
                EventLog.add(ctx, "Undo ran: \"$text\" · locked=$isLocked")
                NowCard.post(ctx, alert = false, reason = "undo")
            }
            ALL_TOMORROW -> {
                val n = Spike.moveAllOpenToTomorrow(ctx)
                EventLog.add(ctx, "All to tomorrow ran: $n items · locked=$isLocked")
                NowCard.post(ctx, alert = false, reason = "all to tomorrow")
            }
            REFRESH -> {
                EventLog.add(ctx, "↻ ran · locked=$isLocked")
                NowCard.post(ctx, alert = false, reason = "refresh")
            }
            CAPTURE -> {
                val typed = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(NowCard.KEY_CAPTURE)?.toString()
                if (!typed.isNullOrBlank()) Spike.capture(ctx, typed)
                EventLog.add(ctx, "Capture ran: \"$typed\" · locked=$isLocked")
                NowCard.post(ctx, alert = false, reason = "capture")
            }
            DISMISSED -> {
                Spike.setCardOn(ctx, false)
                EventLog.add(ctx, "card swiped away · locked=$isLocked")
            }
        }
    }
}

/** Exact alarms: a silent part-of-day switch, a check, and the overnight chain. */
object Alarms {
    const val SWITCH = "switch"
    const val CHECK = "check"
    const val CHAIN = "chain"
    const val CHAIN_MINUTES = 30L

    fun fmt(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))

    private fun pending(ctx: Context, kind: String, at: Long): PendingIntent {
        val intent = Intent(ctx, AlarmReceiver::class.java)
            .setAction(kind)
            .setData(Uri.parse("sundial-alarm://$kind"))
            .putExtra("at", at)
        return PendingIntent.getBroadcast(
            ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun canExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun schedule(ctx: Context, kind: String, delayMs: Long) {
        val at = System.currentTimeMillis() + delayMs
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx, kind, at)
        if (canExact(ctx)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            EventLog.add(ctx, "scheduled exact $kind for ${fmt(at)}")
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            EventLog.add(ctx, "exact alarms NOT allowed: scheduled inexact $kind for ${fmt(at)}")
        }
    }

    fun cancel(ctx: Context, kind: String) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, kind, 0))
        EventLog.add(ctx, "cancelled $kind")
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val kind = intent.action ?: return
        val at = intent.getLongExtra("at", 0)
        val lateSec = (System.currentTimeMillis() - at) / 1000
        val dozing = ctx.getSystemService(PowerManager::class.java).isDeviceIdleMode
        EventLog.add(
            ctx,
            "alarm $kind fired · due ${Alarms.fmt(at)} · ${lateSec}s late · locked=${locked(ctx)} · dozing=$dozing",
        )
        when (kind) {
            Alarms.SWITCH -> {
                val part = Spike.nextPart(ctx)
                if (Spike.cardOn(ctx)) NowCard.post(ctx, alert = false, reason = "silent switch to $part")
            }
            Alarms.CHECK -> {
                val open = Spike.openItems(ctx)
                if (open.isEmpty()) {
                    EventLog.add(ctx, "check: all clear, no alert")
                } else {
                    Spike.setCardOn(ctx, true)
                    NowCard.post(ctx, alert = true, reason = "check, ${open.size} open")
                }
            }
            Alarms.CHAIN -> {
                if (Spike.chainOn(ctx)) {
                    Alarms.schedule(ctx, Alarms.CHAIN, Alarms.CHAIN_MINUTES * 60_000)
                    if (Spike.cardOn(ctx)) NowCard.post(ctx, alert = false, reason = "overnight refresh")
                }
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        EventLog.add(ctx, "boot/update: ${intent.action}")
        if (Spike.cardOn(ctx)) {
            NowCard.post(ctx, alert = false, reason = "after ${intent.action?.substringAfterLast('.')}")
        }
        if (Spike.chainOn(ctx)) Alarms.schedule(ctx, Alarms.CHAIN, Alarms.CHAIN_MINUTES * 60_000)
    }
}
