package org.dandeliondaily.sundial.card

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
import org.dandeliondaily.sundial.MainActivity
import org.dandeliondaily.sundial.R
import org.dandeliondaily.sundial.day.CardRow
import org.dandeliondaily.sundial.day.CardState

/**
 * Draws the Now card: one ongoing notification with custom rows (what Nathan chose in step 0) on a
 * default-importance channel with no sound, so it sits near the top of the shade and shows on the
 * lock screen (with Lock screen → Notifications set to Details). It never makes a sound: switching
 * parts of the day is silent (plan change 9). Reminders come in step 6, on their own channel.
 */
object NowCard {
    const val NOTIFICATION_ID = 1
    const val CHANNEL = "card"
    const val KEY_CAPTURE = "capture_text"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, "Now card", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "What to do in the current part of the day. Silent."
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
        // The step 0 spike's channels are gone.
        for (old in listOf("now", "now_top", "checks")) nm.deleteNotificationChannel(old)
    }

    fun cancel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    fun post(ctx: Context, state: CardState, store: CardStore) {
        ensureChannel(ctx)
        val title = "Sundial · " + state.title
        val line = listOfNotNull(state.asOf, state.receipt?.text ?: state.line).joinToString(" · ")
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sundial)
            .setContentTitle(title)
            .setContentText(line)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .setColor(0xFFE07A00.toInt())
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(openApp(ctx))
            .setDeleteIntent(receiver(ctx, CardReceiver.DISMISSED))
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(collapsed(ctx, state.title, line))
            .setCustomBigContentView(expanded(ctx, state))

        val project = store.captureProject()
        if (store.apiLevel() >= 3 && project != null) b.addAction(captureAction(ctx, project.name))
        if (state.movableLeftovers.size > 1 && store.apiLevel() >= 4) {
            b.addAction(action(ctx, "All to tomorrow", receiver(ctx, CardReceiver.ALL_TOMORROW)))
        }
        b.addAction(action(ctx, "↻", receiver(ctx, CardReceiver.REFRESH)))
        ctx.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, b.build())
    }

    private fun collapsed(ctx: Context, title: String, line: String) =
        RemoteViews(ctx.packageName, R.layout.card_collapsed).apply {
            setTextViewText(R.id.title, title)
            setTextViewText(R.id.line, line)
        }

    private fun expanded(ctx: Context, state: CardState): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.card_expanded)
        rv.setTextViewText(R.id.title, listOfNotNull(state.title, state.asOf).joinToString(" · "))
        rv.removeAllViews(R.id.rows)

        state.receipt?.let { r ->
            val row = RemoteViews(ctx.packageName, R.layout.card_row)
            row.setTextViewText(R.id.row_text, r.text)
            row.setTextViewText(R.id.row_project, "")
            when {
                r.undoToken != null -> {
                    row.setTextViewText(R.id.row_button, "Undo")
                    row.setOnClickPendingIntent(R.id.row_button, receiver(ctx, CardReceiver.UNDO, token = r.undoToken))
                }
                r.actionId != null -> {
                    row.setTextViewText(R.id.row_button, "Edit")
                    row.setOnClickPendingIntent(R.id.row_button, openAction(ctx, r.actionId))
                }
                else -> row.setViewVisibility(R.id.row_button, View.GONE)
            }
            rv.addView(R.id.rows, row)
        }

        for (r in state.rows) {
            r.header?.let { h ->
                val header = RemoteViews(ctx.packageName, R.layout.card_header)
                header.setTextViewText(R.id.header_text, h)
                rv.addView(R.id.rows, header)
            }
            rv.addView(R.id.rows, row(ctx, r))
        }
        if (state.rows.isEmpty() && state.receipt == null) {
            val header = RemoteViews(ctx.packageName, R.layout.card_header)
            header.setTextViewText(R.id.header_text, state.line)
            rv.addView(R.id.rows, header)
        }
        rv.setTextViewText(R.id.more, "+${state.more} more · Open")
        rv.setViewVisibility(R.id.more, if (state.more > 0) View.VISIBLE else View.GONE)
        rv.setOnClickPendingIntent(R.id.more, openApp(ctx))
        return rv
    }

    private fun row(ctx: Context, r: CardRow): RemoteViews {
        val a = r.action
        val row = RemoteViews(ctx.packageName, R.layout.card_row)
        row.setTextViewText(R.id.row_text, a.displayText)
        row.setTextViewText(R.id.row_project, a.projectIcon ?: a.projectName?.take(10) ?: "")
        row.setOnClickPendingIntent(R.id.row_text, openAction(ctx, a.id))
        row.setTextViewText(R.id.row_button, "✔")
        row.setOnClickPendingIntent(R.id.row_button, receiver(ctx, CardReceiver.DONE, id = a.id, text = a.displayText))
        when {
            r.canMove -> {
                row.setViewVisibility(R.id.row_button2, View.VISIBLE)
                row.setTextViewText(R.id.row_button2, "→")
                row.setOnClickPendingIntent(R.id.row_button2, receiver(ctx, CardReceiver.TOMORROW, id = a.id, text = a.displayText))
            }
            r.canSkip -> {
                row.setViewVisibility(R.id.row_button2, View.VISIBLE)
                row.setTextViewText(R.id.row_button2, "✖")
                row.setOnClickPendingIntent(R.id.row_button2, receiver(ctx, CardReceiver.SKIP, id = a.id, text = a.displayText))
            }
        }
        return row
    }

    private fun icon(ctx: Context) = Icon.createWithResource(ctx, R.drawable.ic_stat_sundial)

    private fun action(ctx: Context, title: String, pi: PendingIntent) =
        Notification.Action.Builder(icon(ctx), title, pi).build()

    private fun captureAction(ctx: Context, projectName: String): Notification.Action {
        val input = RemoteInput.Builder(KEY_CAPTURE).setLabel("Capture to $projectName (or project: …)").build()
        return Notification.Action.Builder(icon(ctx), "Capture", receiver(ctx, CardReceiver.CAPTURE, mutable = true))
            .addRemoteInput(input)
            .build()
    }

    /** A broadcast to [CardReceiver]. The data URI keeps each row's PendingIntent distinct. */
    fun receiver(
        ctx: Context, kind: String, id: Int? = null, text: String? = null, token: String? = null, mutable: Boolean = false,
    ): PendingIntent {
        val intent = Intent(ctx, CardReceiver::class.java)
            .setAction(kind)
            .setData(Uri.parse("sundial-card://$kind/${id ?: 0}/${Uri.encode(token ?: "")}"))
        id?.let { intent.putExtra(CardReceiver.EXTRA_ID, it) }
        text?.let { intent.putExtra(CardReceiver.EXTRA_TEXT, it) }
        token?.let { intent.putExtra(CardReceiver.EXTRA_TOKEN, it) }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(ctx, 0, intent, flags)
    }

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Opens the app on this action's sheet. */
    fun openAction(ctx: Context, actionId: Int): PendingIntent = PendingIntent.getActivity(
        ctx, actionId,
        Intent(ctx, MainActivity::class.java)
            .setData(Uri.parse("sundial://action/$actionId"))
            .putExtra(MainActivity.EXTRA_ACTION_ID, actionId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
