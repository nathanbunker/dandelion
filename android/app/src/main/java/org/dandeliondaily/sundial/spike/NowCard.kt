package org.dandeliondaily.sundial.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import org.dandeliondaily.sundial.R

/** The throwaway Now card: one ongoing notification, custom rows or the plain fallback. */
object NowCard {
    const val NOTIFICATION_ID = 1
    const val CH_NOW = "now"
    const val CH_NOW_TOP = "now_top"
    const val CH_CHECKS = "checks"
    const val KEY_CAPTURE = "capture_text"
    const val EXTRA_ID = "id"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val now = NotificationChannel(CH_NOW, "Now card", NotificationManager.IMPORTANCE_LOW).apply {
            description = "The card for the current part of the day. Silent."
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        }
        // Same card, but at default importance with no sound or vibration, so it isn't filed under
        // "Silent" at the bottom of the shade (and may show in full on Samsung's lock screen).
        val nowTop = NotificationChannel(CH_NOW_TOP, "Now card (top)", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "The card for the current part of the day, kept near the top. No sound."
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        val checks = NotificationChannel(CH_CHECKS, "Checks", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "The check an hour before a part of the day ends, when items are still open."
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannels(listOf(now, nowTop, checks))
    }

    fun post(ctx: Context, alert: Boolean, reason: String) {
        ensureChannels(ctx)
        val part = Spike.part(ctx)
        val items = Spike.items(ctx)
        val done = Spike.done(ctx)
        val open = items.filter { it.id !in done }
        val title = if (open.isEmpty()) "$part · all clear" else "$part · ${open.size} left"
        val next = open.firstOrNull()?.let { "next: ${it.text}" } ?: "Nothing left in $part"
        val style = Spike.style(ctx)

        val channel = when {
            alert -> CH_CHECKS
            Spike.placement(ctx) == "top" -> CH_NOW_TOP
            else -> CH_NOW
        }
        val b = Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_sundial)
            .setContentTitle("Sundial · $title")
            .setContentText(next)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(!alert)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setColor(0xFFE07A00.toInt())
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(openApp(ctx))
            .setDeleteIntent(receiver(ctx, CardReceiver.DISMISSED, null))

        if (style == "custom") {
            b.setStyle(Notification.DecoratedCustomViewStyle())
            b.setCustomContentView(collapsed(ctx, title, next))
            b.setCustomBigContentView(expanded(ctx, title, items, done))
            b.addAction(captureAction(ctx))
            b.addAction(action(ctx, "↻ Refresh", CardReceiver.REFRESH, null))
            b.addAction(Notification.Action.Builder(icon(ctx), "Open", openApp(ctx)).build())
        } else {
            val inbox = Notification.InboxStyle().setBigContentTitle("Sundial · $title")
            open.take(5).forEach { inbox.addLine(label(it)) }
            if (open.size > 5) inbox.setSummaryText("+${open.size - 5} more")
            b.setStyle(inbox)
            open.firstOrNull()?.let {
                b.addAction(action(ctx, "✔ ${it.text.take(16)}", CardReceiver.DONE, it.id))
            }
            if (open.isNotEmpty()) b.addAction(action(ctx, "All to tomorrow", CardReceiver.ALL_TOMORROW, null))
            b.addAction(captureAction(ctx))
        }

        ctx.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, b.build())
        EventLog.add(ctx, "card posted ($reason): $title · channel=$channel · style=$style · rows=${Spike.rowLimit(ctx)}")
    }

    private fun label(item: Spike.Item) = (item.fromEarlier?.let { "from $it · " } ?: "") + item.text

    private fun collapsed(ctx: Context, title: String, next: String): RemoteViews =
        RemoteViews(ctx.packageName, R.layout.card_collapsed).apply {
            setTextViewText(R.id.title, title)
            setTextViewText(R.id.line, next)
        }

    private fun expanded(ctx: Context, title: String, items: List<Spike.Item>, done: Set<String>): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.card_expanded)
        rv.setTextViewText(R.id.title, title)
        rv.removeAllViews(R.id.rows)
        val shown = items.take(Spike.rowLimit(ctx))
        for (item in shown) {
            val row = RemoteViews(ctx.packageName, R.layout.card_row)
            val isDone = item.id in done
            row.setTextViewText(R.id.row_text, if (isDone) "✓ ${item.text}" else label(item))
            row.setTextViewText(R.id.row_project, item.project)
            if (isDone) {
                row.setTextViewText(R.id.row_button, "Undo")
                row.setOnClickPendingIntent(R.id.row_button, receiver(ctx, CardReceiver.UNDO, item.id))
            } else {
                row.setTextViewText(R.id.row_button, "✔")
                row.setOnClickPendingIntent(R.id.row_button, receiver(ctx, CardReceiver.DONE, item.id))
            }
            row.setOnClickPendingIntent(R.id.row_text, openApp(ctx))
            rv.addView(R.id.rows, row)
        }
        val more = items.size - shown.size
        rv.setTextViewText(R.id.more, "+$more more · Open")
        rv.setViewVisibility(R.id.more, if (more > 0) View.VISIBLE else View.GONE)
        return rv
    }

    private fun icon(ctx: Context) = Icon.createWithResource(ctx, R.drawable.ic_stat_sundial)

    private fun action(ctx: Context, title: String, kind: String, id: String?) =
        Notification.Action.Builder(icon(ctx), title, receiver(ctx, kind, id)).build()

    private fun captureAction(ctx: Context): Notification.Action {
        val input = RemoteInput.Builder(KEY_CAPTURE).setLabel("Capture to Inbox").build()
        return Notification.Action.Builder(
            icon(ctx), "Capture", receiver(ctx, CardReceiver.CAPTURE, null, mutable = true),
        ).addRemoteInput(input).build()
    }

    private fun receiver(ctx: Context, kind: String, id: String?, mutable: Boolean = false): PendingIntent {
        val intent = Intent(ctx, CardReceiver::class.java)
            .setAction(kind)
            .setData(Uri.parse("sundial://$kind/${Uri.encode(id ?: "")}"))
        if (id != null) intent.putExtra(EXTRA_ID, id)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(ctx, 0, intent, flags)
    }

    private fun openApp(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
