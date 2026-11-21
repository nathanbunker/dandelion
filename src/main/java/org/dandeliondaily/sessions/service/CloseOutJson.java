package org.dandeliondaily.sessions.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The JSON form of a close-out, shared by MCP close_out_action and the /close-out session
 * command, so both read and answer it the same way.
 *
 * <pre>
 * { "asOf": "...", "completionDescription": "...", "outcomeNote": "...",
 *   "successors": [ {"description": "...", "scheduledDate": "2026-11-24", "notes": "...", "nextActionType": "WILL"} ],
 *   "noSuccessor": false,
 *   "changes": [ {"actionNextId": 1, "asOf": "...", "op": "rewrite|reschedule|cancel|merge",
 *                 "description": "...", "scheduledDate": "...", "reason": "...",
 *                 "survivorActionNextId": 2, "survivorAsOf": "...", "combinedDescription": "..."} ],
 *   "confirmRows": [ {"actionNextId": 1, "asOf": "..."} ] }
 * </pre>
 */
public final class CloseOutJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> TOP = new HashSet<String>(Arrays.asList("actionNextId", "asOf",
            "completionDescription", "outcomeNote", "durationMinutes", "successors", "noSuccessor", "changes",
            "confirmRows", "mode"));
    private static final Set<String> SUCCESSOR = new HashSet<String>(Arrays.asList("description", "scheduledDate",
            "notes", "nextActionType"));
    private static final Set<String> CHANGE = new HashSet<String>(Arrays.asList("actionNextId", "asOf", "op",
            "description", "scheduledDate", "reason", "survivorActionNextId", "survivorAsOf",
            "combinedDescription"));

    private CloseOutJson() {
    }

    public static CloseOutService.Request parse(String body, int actionNextId, boolean apply, boolean allowDuration) {
        JsonNode node;
        try {
            node = MAPPER.readTree(body == null || body.trim().length() == 0 ? "{}" : body);
        } catch (Exception e) {
            throw SessionException.bad("The close-out must be a JSON object.");
        }
        return parse(node, actionNextId, apply, allowDuration);
    }

    public static CloseOutService.Request parse(JsonNode node, int actionNextId, boolean apply, boolean allowDuration) {
        if (node == null || !node.isObject()) {
            throw SessionException.bad("The close-out must be a JSON object.");
        }
        rejectUnknown(node, TOP, "close-out");
        if (node.hasNonNull("actionNextId") && node.get("actionNextId").asInt() != actionNextId) {
            throw SessionException.bad("This close-out is for action #" + actionNextId + ".");
        }
        CloseOutService.Request request = new CloseOutService.Request();
        request.actionNextId = actionNextId;
        request.asOf = text(node, "asOf");
        request.completionDescription = text(node, "completionDescription");
        request.outcomeNote = text(node, "outcomeNote");
        if (node.hasNonNull("durationMinutes")) {
            if (!allowDuration) {
                throw SessionException.bad("Agents don't record time; leave out \"durationMinutes\".");
            }
            request.durationMinutes = node.get("durationMinutes").asInt();
        }
        request.noSuccessor = node.hasNonNull("noSuccessor") && node.get("noSuccessor").asBoolean();
        if (node.has("successors")) {
            if (!node.get("successors").isArray()) {
                throw SessionException.bad("\"successors\" must be an array.");
            }
            for (JsonNode item : node.get("successors")) {
                rejectUnknown(item, SUCCESSOR, "successor");
                CloseOutService.SuccessorSpec spec = new CloseOutService.SuccessorSpec();
                spec.description = text(item, "description");
                spec.scheduledDate = date(item, "scheduledDate");
                spec.notes = text(item, "notes");
                spec.actionType = text(item, "nextActionType");
                request.successors.add(spec);
            }
        }
        if (node.has("changes")) {
            if (!node.get("changes").isArray()) {
                throw SessionException.bad("\"changes\" must be an array.");
            }
            for (JsonNode item : node.get("changes")) {
                rejectUnknown(item, CHANGE, "change");
                CloseOutService.ChangeSpec spec = new CloseOutService.ChangeSpec();
                if (!item.hasNonNull("actionNextId") || item.get("actionNextId").asInt() <= 0) {
                    throw SessionException.bad("Each change needs \"actionNextId\".");
                }
                spec.actionNextId = item.get("actionNextId").asInt();
                spec.asOf = text(item, "asOf");
                spec.op = text(item, "op");
                spec.description = text(item, "description");
                spec.scheduledDate = date(item, "scheduledDate");
                spec.reason = text(item, "reason");
                spec.survivorActionNextId = item.hasNonNull("survivorActionNextId")
                        ? Integer.valueOf(item.get("survivorActionNextId").asInt()) : null;
                spec.survivorAsOf = text(item, "survivorAsOf");
                spec.combinedDescription = text(item, "combinedDescription");
                request.changes.add(spec);
            }
        }
        if (apply) {
            if (!node.has("confirmRows") || !node.get("confirmRows").isArray()) {
                throw SessionException.bad("Preview first, then send the rows it listed as \"confirmRows\".");
            }
            request.confirmRows = new ArrayList<CloseOutService.RowRef>();
            for (JsonNode item : node.get("confirmRows")) {
                request.confirmRows.add(new CloseOutService.RowRef(item.path("actionNextId").asInt(),
                        text(item, "asOf")));
            }
        }
        return request;
    }

    public static Map<String, Object> planMap(CloseOutService.Plan plan) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("ok", Boolean.valueOf(plan.isOk()));
        map.put("errors", plan.errors);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> confirm = new ArrayList<Map<String, Object>>();
        for (CloseOutService.Row row : plan.rows) {
            rows.add(CloseOutService.rowMap(row));
            if (row.actionNextId > 0) {
                Map<String, Object> ref = new LinkedHashMap<String, Object>();
                ref.put("actionNextId", Integer.valueOf(row.actionNextId));
                ref.put("asOf", row.asOf);
                confirm.add(ref);
            }
        }
        map.put("rows", rows);
        map.put("confirmRows", confirm);
        map.put("message", plan.isOk() ? "Nothing has been changed yet. Show these rows to the person; "
                + "after they confirm, apply with the same request plus \"confirmRows\"." : "Fix the errors and preview again.");
        return map;
    }

    private static void rejectUnknown(JsonNode node, Set<String> allowed, String what) {
        if (node == null || !node.isObject()) {
            throw SessionException.bad("Each " + what + " must be an object.");
        }
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw SessionException.bad("Unknown " + what + " field \"" + name + "\".");
            }
        }
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static LocalDate date(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            return null;
        }
        try {
            return LocalDate.parse(node.get(field).asText());
        } catch (DateTimeParseException e) {
            throw SessionException.bad("\"" + field + "\" must be yyyy-MM-dd.");
        }
    }
}
