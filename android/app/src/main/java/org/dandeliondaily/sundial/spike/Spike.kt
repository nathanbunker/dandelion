package org.dandeliondaily.sundial.spike

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Step 0 spike: canned rows and a little state, no server. Throwaway code.
 * See docs/plans/Android App/Sundial Plan.md, "Build order", step 0.
 */
object Spike {
    const val TAG = "Sundial"
    val PARTS = listOf("Wake", "Morning", "Afternoon", "Evening")

    private val FAKE = mapOf(
        "Wake" to listOf(
            "Morning prayer" to "Home",
            "Scripture study" to "Home",
            "Exercise" to "Health",
            "Take vitamins" to "Health",
        ),
        "Morning" to listOf(
            "Call the shop about the car" to "Car",
            "Pay the water bill" to "Home",
            "Email the agenda for Sunday" to "Church",
            "Pick up prescription" to "Health",
            "Schedule furnace service" to "Home",
            "Renew library books" to "Home",
        ),
        "Afternoon" to listOf(
            "Mow the lawn" to "Home",
            "Text Mom about Sunday dinner" to "Family",
            "Return library books" to "Home",
        ),
        "Evening" to listOf(
            "Set out clothes" to "Home",
            "Plug in phone" to "Home",
            "Read 20 minutes" to "Home",
        ),
    )

    data class Item(val id: String, val text: String, val project: String, val fromEarlier: String?)

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("spike", Context.MODE_PRIVATE)

    fun part(ctx: Context): String = prefs(ctx).getString("part", "Morning")!!

    fun nextPart(ctx: Context): String {
        val next = PARTS[(PARTS.indexOf(part(ctx)) + 1) % PARTS.size]
        prefs(ctx).edit().putString("part", next).apply()
        return next
    }

    fun rowLimit(ctx: Context): Int = prefs(ctx).getInt("rows", 5)
    fun toggleRows(ctx: Context) =
        prefs(ctx).edit().putInt("rows", if (rowLimit(ctx) == 5) 3 else 5).apply()

    fun style(ctx: Context): String = prefs(ctx).getString("style", "custom")!!
    fun toggleStyle(ctx: Context) =
        prefs(ctx).edit().putString("style", if (style(ctx) == "custom") "plain" else "custom").apply()

    fun placement(ctx: Context): String = prefs(ctx).getString("placement", "silent")!!
    fun togglePlacement(ctx: Context) =
        prefs(ctx).edit().putString("placement", if (placement(ctx) == "silent") "top" else "silent").apply()

    fun cardOn(ctx: Context): Boolean = prefs(ctx).getBoolean("cardOn", false)
    fun setCardOn(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("cardOn", on).apply()

    fun chainOn(ctx: Context): Boolean = prefs(ctx).getBoolean("chainOn", false)
    fun setChainOn(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("chainOn", on).apply()

    private fun set(ctx: Context, key: String): Set<String> =
        prefs(ctx).getStringSet(key, emptySet())!!.toSet()

    private fun update(ctx: Context, key: String, id: String, add: Boolean) {
        val s = set(ctx, key).toMutableSet()
        if (add) s.add(id) else s.remove(id)
        prefs(ctx).edit().putStringSet(key, s).apply()
    }

    fun done(ctx: Context): Set<String> = set(ctx, "done")
    fun setDone(ctx: Context, id: String, value: Boolean) = update(ctx, "done", id, value)

    fun moveAllOpenToTomorrow(ctx: Context): Int {
        val open = openItems(ctx)
        open.forEach { update(ctx, "moved", it.id, true) }
        return open.size
    }

    private fun captured(ctx: Context): List<String> =
        prefs(ctx).getString("captured", "")!!.split("\n").filter { it.isNotBlank() }

    fun capture(ctx: Context, text: String) {
        val all = captured(ctx) + text.replace("\n", " ")
        prefs(ctx).edit().putString("captured", all.joinToString("\n")).apply()
    }

    /** The current part's rows, with a Morning leftover carried into Afternoon (plan change 10). */
    fun items(ctx: Context): List<Item> {
        val p = part(ctx)
        val done = done(ctx)
        val list = mutableListOf<Item>()
        if (p == "Afternoon" && "Morning:3" !in done) {
            list += Item("Morning:3", "Pick up prescription", "Health", "Morning")
        }
        FAKE.getValue(p).forEachIndexed { i, (t, proj) -> list += Item("$p:$i", t, proj, null) }
        captured(ctx).forEachIndexed { i, t -> list += Item("cap:$i", t, "Inbox", null) }
        val moved = set(ctx, "moved")
        return list.filter { it.id !in moved }
    }

    fun openItems(ctx: Context): List<Item> = items(ctx).filter { it.id !in done(ctx) }

    fun reset(ctx: Context) {
        val keepCard = cardOn(ctx)
        prefs(ctx).edit().clear().putBoolean("cardOn", keepCard).apply()
    }
}

/** Timestamped event log: logcat tag "Sundial" plus files/spike-log.txt (read with adb run-as). */
object EventLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "spike-log.txt")

    fun add(ctx: Context, msg: String) {
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
        Log.i(Spike.TAG, msg)
        file(ctx).appendText("$stamp  $msg\n")
    }

    fun read(ctx: Context): String {
        val f = file(ctx)
        if (!f.exists()) return "(no events yet)"
        return f.readLines().takeLast(200).reversed().joinToString("\n")
    }

    fun clear(ctx: Context) {
        file(ctx).delete()
    }
}
