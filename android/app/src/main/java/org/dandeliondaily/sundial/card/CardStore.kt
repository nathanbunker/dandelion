package org.dandeliondaily.sundial.card

import android.content.Context
import org.dandeliondaily.sundial.api.Day
import org.dandeliondaily.sundial.api.Json
import org.dandeliondaily.sundial.api.Project
import org.dandeliondaily.sundial.day.Receipt
import org.dandeliondaily.sundial.day.SundialNow
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the card remembers between posts: the last day it fetched (so it shows at once after a reboot,
 * with "as of"), the last change (the Undo row), whether it was swiped away, the capture project, and
 * the server's API level.
 */
class CardStore(context: Context) {
    private val prefs = context.getSharedPreferences("sundial_card", Context.MODE_PRIVATE)

    fun saveDay(json: JSONObject, fetchedAt: Long) {
        prefs.edit().putString("day", json.toString()).putLong("fetched", fetchedAt).apply()
    }

    fun day(): Day? = prefs.getString("day", null)?.let {
        try {
            Json.day(JSONObject(it))
        } catch (e: Exception) {
            null
        }
    }

    fun fetchedAt(): Long = prefs.getLong("fetched", 0L)

    fun receipt(): Receipt? {
        val text = prefs.getString("receipt_text", null) ?: return null
        return Receipt(
            text = text,
            at = prefs.getLong("receipt_at", 0L),
            undoToken = prefs.getString("receipt_token", null),
            actionId = prefs.getInt("receipt_action", 0).takeIf { it > 0 },
        )
    }

    fun setReceipt(r: Receipt?) {
        val e = prefs.edit()
        if (r == null) {
            e.remove("receipt_text").remove("receipt_at").remove("receipt_token").remove("receipt_action")
        } else {
            e.putString("receipt_text", r.text).putLong("receipt_at", r.at)
                .putString("receipt_token", r.undoToken).putInt("receipt_action", r.actionId ?: 0)
        }
        e.apply()
    }

    /** Swiping the card away hides it until the next part of the day (or until the app opens). */
    fun hide(now: SundialNow) = prefs.edit().putString("hidden", key(now)).apply()
    fun isHidden(now: SundialNow) = prefs.getString("hidden", null) == key(now)
    fun unhide() = prefs.edit().remove("hidden").apply()
    private fun key(now: SundialNow) = "${now.date}/${now.part.id}"

    fun apiLevel(): Int = prefs.getInt("api_level", 0)
    fun setApiLevel(level: Int) = prefs.edit().putInt("api_level", level).apply()

    fun projects(): List<Project> = prefs.getString("projects", null)?.let { text ->
        try {
            val a = JSONArray(text)
            (0 until a.length()).map { Json.project(a.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    } ?: emptyList()

    fun projectsAt(): Long = prefs.getLong("projects_at", 0L)

    fun saveProjects(list: List<Project>, at: Long) {
        val a = JSONArray()
        for (p in list) a.put(JSONObject().put("id", p.id).put("name", p.name).put("icon", p.icon ?: JSONObject.NULL))
        prefs.edit().putString("projects", a.toString()).putLong("projects_at", at).apply()
    }

    /** The project the card's Capture saves to; null means the most recently used one. */
    fun captureProjectId(): Int? = prefs.getInt("capture_project", 0).takeIf { it > 0 }
    fun setCaptureProjectId(id: Int?) = prefs.edit().putInt("capture_project", id ?: 0).apply()

    fun captureProject(): Project? {
        val list = projects()
        return captureProjectId()?.let { id -> list.firstOrNull { it.id == id } } ?: list.firstOrNull()
    }

    fun clear() = prefs.edit().clear().apply()
}
