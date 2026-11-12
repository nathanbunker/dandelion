package org.dandeliondaily.sundial.api

import org.json.JSONArray
import org.json.JSONObject

/** The four parts of the day, in Dandelion's order. Ids match the server's `part` values. */
enum class Part(val id: String, val label: String) {
    WAKE("WAKE", "Wake"),
    MORNING("MORNING", "Morning"),
    AFTERNOON("AFTERNOON", "Afternoon"),
    EVENING("EVENING", "Evening");

    companion object {
        /** The server sends AFTERNOON for actions with no time slot; anything unknown lands there too. */
        fun fromId(id: String?): Part = entries.firstOrNull { it.id == id } ?: AFTERNOON
    }
}

data class Me(
    val username: String,
    val firstName: String?,
    val timeZone: String,
    val today: String,
    val apiLevel: Int,
)

data class DeviceInfo(
    val clientId: Int,
    val deviceName: String?,
    val status: String,
    val username: String?,
) {
    val isApproved get() = status == "APPROVED"
    val isPending get() = status == "PENDING"
}

/** One personal action as `/personal/day` and `/personal/actions/{id}` return it. */
data class Action(
    val id: Int,
    val description: String,
    val displayText: String,
    val actionType: String?,
    val date: String?,
    val part: Part,
    val priorityLevel: Int?,
    val timeEstimateMins: Int?,
    val linkUrl: String?,
    val projectId: Int?,
    val projectName: String?,
    val projectIcon: String?,
    val templateActionNextId: Int?,
    val missedActionBehavior: String?,
    /** Auto Cancel template instance: it expires instead of going overdue. */
    val expires: Boolean,
    val overdue: Boolean,
    // Detail only (null on the day list).
    val status: String? = null,
    val notes: String? = null,
    val deadline: String? = null,
) {
    val isRoutine get() = templateActionNextId != null
}

/** What a complete, cancel, move, Complete & Next or Block returns. */
data class Changed(
    val action: Action,
    val undoToken: String?,
    val summary: String,
    val unblocked: Action?,
    val created: List<Action> = emptyList(),
)

/** What a quick capture returns. */
data class Captured(val action: Action, val summary: String)

/** A personal project (capture and Edit details pick from these). */
data class Project(val id: Int, val name: String, val icon: String?) {
    val label get() = if (icon != null) "$icon $name" else name
}

data class Day(
    val date: String,
    val today: String,
    val isToday: Boolean,
    val overdue: List<Action>,
    val parts: Map<Part, List<Action>>,
) {
    fun part(part: Part): List<Action> = parts[part].orEmpty()
}

object Json {
    fun me(o: JSONObject) = Me(
        username = o.getString("username"),
        firstName = o.str("firstName"),
        timeZone = o.optString("timeZone", "US/Mountain"),
        today = o.getString("today"),
        apiLevel = o.optInt("apiLevel", 0),
    )

    fun device(o: JSONObject) = DeviceInfo(
        clientId = o.optInt("clientId"),
        deviceName = o.str("deviceName"),
        status = o.optString("status", ""),
        username = o.str("username"),
    )

    fun action(o: JSONObject) = Action(
        id = o.getInt("id"),
        description = o.str("description") ?: "",
        displayText = o.str("displayText") ?: o.str("description") ?: "",
        actionType = o.str("actionType"),
        date = o.str("date"),
        part = Part.fromId(o.str("part")),
        priorityLevel = o.int("priorityLevel"),
        timeEstimateMins = o.int("timeEstimateMins"),
        linkUrl = o.str("linkUrl"),
        projectId = o.int("projectId"),
        projectName = o.str("projectName"),
        projectIcon = o.str("projectIcon"),
        templateActionNextId = o.int("templateActionNextId"),
        missedActionBehavior = o.str("missedActionBehavior"),
        expires = o.optBoolean("expires", false),
        overdue = o.optBoolean("overdue", false),
        status = o.str("status"),
        notes = o.str("notes"),
        deadline = o.str("deadline"),
    )

    fun changed(o: JSONObject) = Changed(
        action = action(o.getJSONObject("action")),
        undoToken = o.str("undoToken"),
        summary = o.str("summary") ?: "Done",
        unblocked = o.optJSONObject("unblocked")?.let { action(it) },
        created = actions(o.optJSONArray("created")),
    )

    fun project(o: JSONObject) = Project(
        id = o.getInt("id"),
        name = o.str("name") ?: "Project ${o.getInt("id")}",
        icon = o.str("icon"),
    )

    fun day(o: JSONObject): Day {
        val partsJson = o.optJSONObject("parts") ?: JSONObject()
        val parts = Part.entries.associateWith { p -> actions(partsJson.optJSONArray(p.id)) }
        return Day(
            date = o.getString("date"),
            today = o.getString("today"),
            isToday = o.optBoolean("isToday", false),
            overdue = actions(o.optJSONArray("overdue")),
            parts = parts,
        )
    }

    private fun actions(a: JSONArray?): List<Action> =
        if (a == null) emptyList() else (0 until a.length()).map { action(a.getJSONObject(it)) }

    /** org.json returns "null" for JSON null with optString; treat null and blank as absent. */
    private fun JSONObject.str(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.int(key: String): Int? =
        if (!has(key) || isNull(key)) null else optInt(key)
}
