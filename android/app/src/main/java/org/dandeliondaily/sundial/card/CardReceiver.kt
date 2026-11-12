package org.dandeliondaily.sundial.card

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.dandeliondaily.sundial.TAG
import org.dandeliondaily.sundial.api.ApiException
import org.dandeliondaily.sundial.api.isNetwork
import org.dandeliondaily.sundial.day.DayLogic
import org.dandeliondaily.sundial.day.Receipt

/**
 * The card's buttons: ✔, → tomorrow, ✖ (routines), Undo, All to tomorrow, Capture, ↻, and swipe-away.
 * Each makes one API call (with an idempotency key), keeps a receipt row, refetches and redraws.
 * Nothing is queued: if Dandelion can't be reached, the card says so and nothing changes.
 */
class CardReceiver : BroadcastReceiver() {
    companion object {
        const val DONE = "done"
        const val TOMORROW = "tomorrow"
        const val SKIP = "skip"
        const val UNDO = "undo"
        const val ALL_TOMORROW = "all_tomorrow"
        const val CAPTURE = "capture"
        const val REFRESH = "refresh"
        const val DISMISSED = "dismissed"
        const val EXTRA_ID = "id"
        const val EXTRA_TEXT = "text"
        const val EXTRA_TOKEN = "token"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val kind = intent.action ?: return
        val app = ctx.applicationContext
        if (kind == DISMISSED) {
            CardStore(app).hide(CardEngine.now())
            Log.i(TAG, "card swiped away; hidden until the next part of the day")
            return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                act(app, kind, intent)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun act(ctx: Context, kind: String, intent: Intent) {
        val store = CardStore(ctx)
        val api = CardEngine.api(ctx) ?: return CardEngine.refresh(ctx, "signed out")
        val id = intent.getIntExtra(EXTRA_ID, 0)
        val text = intent.getStringExtra(EXTRA_TEXT) ?: "it"
        val now = CardEngine.now()
        val nowMs = System.currentTimeMillis()
        // Answer the tap at once; the real receipt replaces this when the server answers.
        val saving = when (kind) {
            DONE -> "✓ $text · saving…"
            SKIP -> "✖ $text · saving…"
            TOMORROW -> "→ $text · saving…"
            ALL_TOMORROW -> "Moving to tomorrow…"
            CAPTURE -> "Saving…"
            UNDO -> "Undoing…"
            else -> null
        }
        if (saving != null) {
            store.setReceipt(Receipt(saving, nowMs))
            CardEngine.post(ctx, now, "$kind (saving)")
        }
        try {
            withTimeout(12_000) {
                when (kind) {
                    DONE -> {
                        val r = api.complete(id)
                        store.setReceipt(Receipt("✓ ${r.action.displayText}", nowMs, r.undoToken))
                    }
                    SKIP -> {
                        val r = api.cancel(id)
                        store.setReceipt(Receipt("✖ ${r.action.displayText}", nowMs, r.undoToken))
                    }
                    TOMORROW -> {
                        val r = api.move(id, DayLogic.key(now.date.plusDays(1)), null)
                        store.setReceipt(Receipt("→ tomorrow: ${r.action.displayText}", nowMs, r.undoToken))
                    }
                    ALL_TOMORROW -> {
                        val state = org.dandeliondaily.sundial.day.CardModel.build(
                            now, store.day(), null, nowMs, store.fetchedAt(),
                        )
                        val (moved, token, _) = api.moveAll(state.movableLeftovers, DayLogic.key(now.date.plusDays(1)))
                        store.setReceipt(Receipt("→ $moved to tomorrow", nowMs, token))
                    }
                    UNDO -> {
                        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return@withTimeout
                        api.undo(token)
                        store.setReceipt(Receipt("Undone", nowMs))
                    }
                    CAPTURE -> {
                        val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(NowCard.KEY_CAPTURE)
                            ?.toString()?.trim().orEmpty()
                        if (typed.isNotEmpty()) {
                            val project = store.captureProject()
                            val r = api.capture(typed, project?.id, null, null)
                            store.setReceipt(Receipt("${r.summary}: ${r.action.displayText}", nowMs, actionId = r.action.id))
                        } else {
                            store.setReceipt(null)
                        }
                    }
                    REFRESH -> store.unhide()
                }
            }
        } catch (e: ApiException) {
            if (e.isRevoked) return CardEngine.refresh(ctx, "revoked")
            val msg = when {
                e.isNotOpen -> "Already done elsewhere"
                else -> "Not saved · ${e.message}"
            }
            store.setReceipt(Receipt(msg, nowMs))
        } catch (e: Exception) {
            Log.w(TAG, "card $kind failed: ${e.javaClass.simpleName} ${e.message}")
            store.setReceipt(Receipt(if (e.isNetwork() || e is kotlinx.coroutines.TimeoutCancellationException) "Not saved · can't reach Dandelion" else "Not saved · ${e.message}", nowMs))
        }
        Log.i(TAG, "card $kind on #$id ($text)")
        CardEngine.refresh(ctx, kind)
    }
}
