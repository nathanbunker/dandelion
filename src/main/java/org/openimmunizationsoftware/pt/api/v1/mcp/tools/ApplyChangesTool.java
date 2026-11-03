package org.openimmunizationsoftware.pt.api.v1.mcp.tools;

import java.util.List;
import java.util.Map;

import org.dandeliondaily.mcp.service.McpApplyChangesService;
import org.dandeliondaily.mcp.service.McpWebUserSupport;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpArgs;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpSchema;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpTool;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpToolContext;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpToolException;
import org.openimmunizationsoftware.pt.model.ProjectNextActionType;
import org.openimmunizationsoftware.pt.model.WebUser;

import com.fasterxml.jackson.databind.JsonNode;

public class ApplyChangesTool implements McpTool {

    private final McpApplyChangesService service = new McpApplyChangesService();

    @Override
    public String getName() {
        return "apply_changes";
    }

    @Override
    public String getDescription() {
        return "Applies a coherent set of approved action changes together, atomically: either every change "
                + "applies or none do. The whole batch is validated against current data first -- if any item "
                + "references stale data (use the asOf value from a prior get_project_context/get_planning_context "
                + "read) or a template-managed action (edit those via the Dandelion UI instead), the entire batch "
                + "is rejected with a reason per item and nothing is written. Only call this after the user has "
                + "explicitly approved the proposed changes -- never treat your own suggestions as already "
                + "approved. Each entry in \"changes\" has a \"type\" of create_action, update_action, "
                + "create_project, reschedule_action, split_action, remove_action, complete_action, order_day, "
                + "upsert_linked_meeting, or unlink_meeting:\n"
                + "- upsert_linked_meeting: {type, actionNextId, asOf, expectedMeetingVersion?, meeting}. "
                + "meeting is a complete externally owned snapshot, not an action update. "
                + "Requires WILL_MEET. For an existing link supply expectedMeetingVersion from a read; "
                + "omit it for a new link. Instead of actionNextId/asOf, actionChangeIndex may reference "
                + "an earlier create_action in this batch (zero-based). Source identity cannot be reassigned. "
                + "Unchanged snapshots are no-ops. Renames/reschedules update the same occurrence; they never "
                + "change action dates, project, description, estimate, notes, order, completion or time. "
                + "CANCELLED/MISSING are explicit source statuses, not action cancellation.\n"
                + "- unlink_meeting: {type, actionNextId, asOf, expectedMeetingVersion}. Explicitly deletes "
                + "the source snapshot only; leaves the action unchanged. Approval required.\n"
                + "- create_project: {type, ref, name, description?, billCode, tags?, currentFocus?, "
                + "allowDuplicate?, projectHandle?}. Creates a private project in the active private workspace. "
                + "Bill codes and tags must already exist there; project names cannot duplicate an open visible "
                + "project unless allowDuplicate is true, and refs/handles/names must be unambiguous in the batch. "
                + "A later create_action or update_action may use projectRef instead of projectId.\n"
                + "- create_action: {type, projectId | projectRef, description, nextActionType?, scheduledDate?, "
                + "deadlineDate?, targetDate?, estimateMinutes?, notes?, linkUrl?}. projectRef must name an earlier "
                + "create_project in this batch. Each independent action gets its own STANDARD action set "
                + "so completed work can be attributed; batch atomicity does not group unrelated actions.\n"
                + "- update_action: {type, actionNextId, asOf, description?, nextActionType?, estimateMinutes?, "
                + "notes?, addNote?, linkUrl?, projectId?, projectRef?}. Only fields present are changed; projectId or "
                + "projectRef moves an open, non-template, non-shared action to an open private project. Its "
                + "linked meeting follows the action; recorded time stays billed to its original project. Moves "
                + "are rejected while its current entry may be a running timer or if another action change in "
                + "the batch targets the same action. An action's notes are one plain-text value, stored "
                + "exactly as sent: notes replaces the whole text (\"\" or null clears it) and addNote appends "
                + "a line to the end; send one or the other, not both. Notes on a linked-project action are "
                + "shared by its twin on the other project, so either changes both. linkUrl \"\" or null "
                + "clears the link. An update that only sets linkUrl, notes and/or addNote is also allowed on a "
                + "template-generated instance (it changes only that occurrence, not the template).\n"
                + "- reschedule_action: {type, actionNextId, asOf, scheduledDate?, deadlineDate?, targetDate?} "
                + "(yyyy-MM-dd, or null to clear a date). At least one date field required.\n"
                + "- split_action: {type, actionNextId, asOf, newActions: [{description, nextActionType?, "
                + "scheduledDate?, estimateMinutes?, linkUrl?}, ...]}. Cancels the original and creates the replacements "
                + "fresh with independent STANDARD action sets, each following the original in its thread; the change audit records the split relationship.\n"
                + "- remove_action: {type, actionNextId, asOf, reason?}. Always a soft cancel, never a hard "
                + "delete.\n"
                + "- complete_action: {type, actionNextId, asOf, completedAt?, durationMinutes?, "
                + "completionDescription?, outcomeNote?, followUp?: {description, nextActionType?, scheduledDate?, "
                + "deadlineDate?, targetDate?, estimateMinutes?, notes?, linkUrl?}}. Allowed on template-generated "
                + "instances but not template roots. Record work in three parts: completionDescription is a short "
                + "past-tense fragment of what was done (about 80 characters, e.g. \"Finished Small Group "
                + "support\"), never a summary; outcomeNote holds the detail (what happened, decisions, blockers, "
                + "where it was left, links) and is what the daily and weekly reports draw on; followUp creates "
                + "the next action in the same project in the same batch, linked to the completed action as the next "
                + "step of its thread (a linked-project action gets a linked follow-up, one per project; "
                + "followUpLinkedActionNextIds lists the others), and its notes should say what the next "
                + "session needs to start without asking. The action's own notes (written before or during the "
                + "work) are planning context and are not used in reports, so put outcomes in outcomeNote, not "
                + "addNote.\n"
                + "- order_day: {type, date, actions: [{actionNextId, asOf}, ...]}. Sets the order of work for a "
                + "day (completionOrder), as the dashboard's up/down buttons do. Order only applies WITHIN a "
                + "dashboard bucket: the dashboard always shows buckets in a fixed order (Overdue, Start of Work "
                + "Day, Committed, Will, Personal (Morning), Might, Waiting, Will Meet, End of Work Day, Other), "
                + "so listing a Might before a Will doesn't move the Might above the Will. The listed actions go "
                + "first in their own bucket in the order given; the rest of each bucket follows in its current "
                + "order. Read the day first with get_planning_context (each action's dashboardBucket, and "
                + "\"dayOrder\") so you know the groups. Each action must be open and scheduled on date (for "
                + "today, overdue actions also count); template-generated instances can be ordered. The result "
                + "lists the new completionOrder and asOf of the ordered actions, plus any other actions on the "
                + "day that were renumbered (their asOf changed too).";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> changeItem = McpSchema.object(
                McpSchema.properties(
                        "type", McpSchema.stringEnum(
                                "Which kind of change this is; see the tool description for each type's fields.",
                                "create_project", "create_action", "update_action", "reschedule_action", "split_action",
                                "remove_action", "complete_action", "order_day",
                                "upsert_linked_meeting", "unlink_meeting"),
                        "actionChangeIndex", McpSchema.integer(
                                "For upsert_linked_meeting: zero-based earlier create_action index, instead of actionNextId/asOf."),
                        "expectedMeetingVersion", McpSchema.integer(
                                "Required for existing links; omit for a new link. Independent of action asOf."),
                        "meeting", meetingSchema(),
                        "actionNextId", McpSchema.integer(
                                "Required for every type except create_project, create_action, order_day, and "
                                        + "upsert_linked_meeting using actionChangeIndex."),
                        "asOf", McpSchema.string(
                                "Required alongside actionNextId: the action's current asOf/scheduledDate context "
                                        + "timestamp from a prior read, used to detect concurrent changes."),
                        "projectId", McpSchema.integer(
                                "Project for create_action, or an open destination for update_action."),
                        "projectRef", McpSchema.string(
                                "For create_action or update_action: ref of an earlier create_project in this batch."),
                        "ref", McpSchema.string("For create_project: unique batch-local placeholder."),
                        "name", McpSchema.string("For create_project: project name."),
                        "billCode", McpSchema.string("For create_project: an existing bill code in this workspace."),
                        "tags", McpSchema.array("For create_project: existing active tag names or handles.",
                                McpSchema.string(null)),
                        "currentFocus", McpSchema.string("For create_project: optional current-focus text."),
                        "allowDuplicate", McpSchema.bool(
                                "For create_project: allow a name already used by an open visible project."),
                        "projectHandle", McpSchema.string(
                                "For create_project: optional project handle; defaults to a handle from name."),
                        "description", McpSchema.string("Action description."),
                        "nextActionType", McpSchema.stringEnum(null,
                                ProjectNextActionType.WILL, ProjectNextActionType.WILL_CONTACT,
                                ProjectNextActionType.WILL_MEET, ProjectNextActionType.WILL_REVIEW,
                                ProjectNextActionType.WILL_DOCUMENT, ProjectNextActionType.WILL_FOLLOW_UP,
                                ProjectNextActionType.MIGHT, ProjectNextActionType.WOULD_LIKE_TO,
                                ProjectNextActionType.COMMITTED_TO, ProjectNextActionType.GOAL,
                                ProjectNextActionType.WAITING, ProjectNextActionType.OVERDUE_TO),
                        "scheduledDate", McpSchema.string("yyyy-MM-dd, or null to clear."),
                        "deadlineDate", McpSchema.string("yyyy-MM-dd, or null to clear."),
                        "targetDate", McpSchema.string("yyyy-MM-dd, or null to clear."),
                        "estimateMinutes", McpSchema.integer("0-480."),
                        "notes", McpSchema.string("The action's notes as one plain-text value, stored as sent "
                                + "(at most 20,000 characters). On create_action, the initial notes; on "
                                + "update_action, replaces the whole text (\"\" or null clears it)."),
                        "addNote", McpSchema.string("Text to append to the end of the notes on a new line, for "
                                + "update_action only. Cannot be combined with notes."),
                        "linkUrl", McpSchema.string(
                                "A link for the action (PR, ticket, document, ...): an http(s) URL of at most "
                                        + "1200 characters. For create_action and update_action; on "
                                        + "update_action, \"\" or null clears it."),
                        "reason", McpSchema.string("Optional reason, for remove_action."),
                        "completedAt", McpSchema.string(
                                "ISO-8601 instant when the work finished (the end of the time entry, not the "
                                        + "start); defaults to now. Combined with durationMinutes to derive when "
                                        + "the work started."),
                        "durationMinutes", McpSchema.integer("Minutes actually spent; defaults to 0."),
                        "completionDescription", McpSchema.string(
                                "For complete_action: a short past-tense fragment of what was done (about 80 "
                                        + "characters); defaults to the action's own description. Put detail "
                                        + "in outcomeNote."),
                        "outcomeNote", McpSchema.string(
                                "For complete_action: the detail behind completionDescription, kept with the "
                                        + "action taken: what happened, decisions, blockers, where it was left, "
                                        + "links. Plain text, at most 20,000 characters. Used by the daily and "
                                        + "weekly reports."),
                        "followUp", McpSchema.object(McpSchema.properties(
                                "description", McpSchema.string("The next action, e.g. \"review the PR with Chris\"."),
                                "nextActionType", McpSchema.string("Defaults to W (will)."),
                                "scheduledDate", McpSchema.string("yyyy-MM-dd."),
                                "deadlineDate", McpSchema.string("yyyy-MM-dd."),
                                "targetDate", McpSchema.string("yyyy-MM-dd."),
                                "estimateMinutes", McpSchema.integer(null),
                                "notes", McpSchema.string("The handoff: what to know or do first when picking "
                                        + "this up."),
                                "linkUrl", McpSchema.string(null)), "description"),
                        "newActions", McpSchema.array("For split_action: the replacement actions.",
                                McpSchema.object(McpSchema.properties(
                                        "description", McpSchema.string(null),
                                        "nextActionType", McpSchema.string(null),
                                        "scheduledDate", McpSchema.string(null),
                                        "estimateMinutes", McpSchema.integer(null),
                                        "linkUrl", McpSchema.string(null)))),
                        "date", McpSchema.string("For order_day: the day to order, yyyy-MM-dd."),
                        "actions", McpSchema.array(
                                "For order_day: the actions in the order to do them (within their buckets).",
                                McpSchema.object(McpSchema.properties(
                                        "actionNextId", McpSchema.integer(null),
                                        "asOf", McpSchema.string(null)),
                                        "actionNextId", "asOf"))),
                "type");
        return McpSchema.object(
                McpSchema.properties(
                        "changes", McpSchema.array("Up to 25 changes, applied atomically.", changeItem)),
                "changes");
    }

    private Map<String, Object> meetingSchema() {
        return McpSchema.object(McpSchema.properties(
                "sourceSystem", McpSchema.string("Source label, max 64; identity is case-sensitive."),
                "sourceNamespace", McpSchema.string("Calendar/account namespace, max 128; default empty."),
                "externalEventUid", McpSchema.string("Stable event or series UID, max 255."),
                "externalOccurrenceId", McpSchema.string("Stable occurrence ID, max 128; never change on reschedule."),
                "originalSubject", McpSchema.string("Exact source subject, max 512."),
                "originalDescription", McpSchema.string("Invitation body, max 1,000,000 characters."),
                "organizerName", McpSchema.string("Max 255."),
                "organizerAddress", McpSchema.string("Max 320."),
                "scheduledStart", McpSchema.string("ISO-8601 date/time with offset or Z."),
                "scheduledEnd", McpSchema.string("ISO-8601 date/time with offset or Z; after start."),
                "sourceTimeZone", McpSchema.string("Original source time-zone ID, max 128."),
                "location", McpSchema.string("Max 2048."),
                "joinUrl", McpSchema.string("Absolute http(s) URL without credentials, max 1200."),
                "sourceStatus", McpSchema.stringEnum(null, "CONFIRMED", "TENTATIVE", "CANCELLED", "MISSING"),
                "sourceLastModifiedAt", McpSchema.string("Optional ISO-8601 source timestamp with offset."),
                "sourceRevision", McpSchema.string("Opaque source revision, max 255."),
                "sourceFingerprint", McpSchema.string("Opaque content fingerprint, max 255.")),
                "sourceSystem", "externalEventUid", "externalOccurrenceId", "originalSubject",
                "scheduledStart", "scheduledEnd", "sourceStatus");
    }

    @Override
    public Object call(JsonNode arguments, McpToolContext context) {
        List<JsonNode> changes = McpArgs.optNodeList(arguments, "changes");
        if (changes.isEmpty()) {
            throw new McpToolException("invalid_arguments", "\"changes\" must contain at least one item.");
        }
        WebUser webUser = McpWebUserSupport.requireWebUser(context.getSession(), context.getUsername());
        try {
            return service.applyChanges(context.getSession(), context.getWorkspaceId(), webUser,
                    context.getAgentName(), changes);
        } catch (org.hibernate.StaleStateException e) {
            throw new McpToolException("stale", "A record changed concurrently. Nothing was applied; reread and retry.");
        } catch (org.hibernate.exception.ConstraintViolationException e) {
            throw new McpToolException("conflict",
                    "A database constraint rejected the batch. Nothing was applied; reread the action/source identity.");
        }
    }
}
