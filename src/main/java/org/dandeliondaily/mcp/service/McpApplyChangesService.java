package org.dandeliondaily.mcp.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.dandeliondaily.dashboard.service.DashboardActionOrdering;
import org.dandeliondaily.shared.service.ActionCompletionService;
import org.dandeliondaily.shared.service.ActionCompletionService.CompletionTime;
import org.dandeliondaily.shared.service.ActionNotesService;
import org.dandeliondaily.shared.service.LinkedMeetingService;
import org.hibernate.LockMode;
import org.hibernate.Query;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.api.v1.dao.ActionChangeLogDao;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpToolException;
import org.openimmunizationsoftware.pt.doa.ActionSetDao;
import org.openimmunizationsoftware.pt.model.LinkedMeeting;
import org.openimmunizationsoftware.pt.model.ActionChangeLog;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ActionSet;
import org.openimmunizationsoftware.pt.model.ActionSetType;
import org.openimmunizationsoftware.pt.model.ActorType;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.model.ProjectContact;
import org.openimmunizationsoftware.pt.model.ProjectNextActionStatus;
import org.openimmunizationsoftware.pt.model.ProjectNextActionType;
import org.openimmunizationsoftware.pt.model.WebUser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * apply_changes: the batch write-back operation. Pre-validates the entire
 * batch against current DB state (same shape as
 * ProjectDefinitionImportService's
 * "resolve everything, fail the whole batch on any mismatch" pattern) before
 * mutating anything; if any item fails validation, nothing is applied.
 * Delegates to existing services where one exists (ActionCompletionService
 * for completion) rather than re-implementing their business rules. See
 * docs/Dandelion_Daily_AI_Integration_Assessment.md section 4 and section 6
 * decisions 6, 8, 9, 10, 14.
 */
public class McpApplyChangesService {

    private static final int MAX_CHANGES = 25;
    private static final int MAX_ESTIMATE_MINUTES = 480;
    private static final int DEFAULT_ESTIMATE_MINUTES = 15;
    private static final int MAX_LINK_URL_LENGTH = 1200;

    private final ActionCompletionService actionCompletionService = new ActionCompletionService();
    private final ActionNotesService actionNotesService = new ActionNotesService();
    private final LinkedMeetingService linkedMeetingService = new LinkedMeetingService();
    private final McpProjectMutationService projectMutationService = new McpProjectMutationService();

    public Map<String, Object> applyChanges(Session session, int workspaceId, WebUser webUser, String agentName,
            List<JsonNode> changeNodes) {
        if (changeNodes.isEmpty()) {
            throw new McpToolException("invalid_arguments", "\"changes\" must contain at least one item.");
        }
        if (changeNodes.size() > MAX_CHANGES) {
            throw new McpToolException("invalid_arguments",
                    "Too many changes in one batch; maximum is " + MAX_CHANGES + ".");
        }

        List<PreparedChange> prepared = new ArrayList<PreparedChange>();
        List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
        boolean allValid = true;
        for (int i = 0; i < changeNodes.size(); i++) {
            PreparedChange pc = validateItem(session, workspaceId, webUser, i, changeNodes.get(i), prepared);
            prepared.add(pc);
            Map<String, Object> resultItem = new LinkedHashMap<String, Object>();
            resultItem.put("index", Integer.valueOf(i));
            resultItem.put("type", pc.type);
            if (pc.actionNextId != null) {
                resultItem.put("actionNextId", pc.actionNextId);
            }
            if (pc.error != null) {
                allValid = false;
                resultItem.put("status", "rejected");
                resultItem.put("reason", pc.error);
            } else {
                resultItem.put("status", "valid");
            }
            results.add(resultItem);
        }

        for (int i = 0; i < prepared.size(); i++) {
            PreparedChange pc = prepared.get(i);
            if (pc.meetingChange == null) {
                continue;
            }
            for (int j = 0; j < prepared.size(); j++) {
                PreparedChange other = prepared.get(j);
                boolean sameAction = pc.meetingCreate != null ? pc.meetingCreate == other.meetingCreate
                        && other.meetingCreate != null : pc.action != null && pc.action == other.action;
                boolean sameIdentity = other.meetingChange != null
                        && pc.meetingChange.getSnapshot() != null && other.meetingChange.getSnapshot() != null
                        && LinkedMeetingService.sameIdentity(pc.meetingChange.getSnapshot(),
                                other.meetingChange.getSnapshot());
                if (i != j && ((other.meetingChange != null && (sameAction || sameIdentity))
                        || (sameAction && other.hasActionType
                                && !ProjectNextActionType.WILL_MEET.equals(other.actionType)))) {
                    allValid = false;
                    results.get(i).put("status", "rejected");
                    results.get(i).put("reason", "Conflicting meeting/action changes in the same batch.");
                }
            }
        }
        if (rejectIncompatibleMovedActionChanges(prepared, results)) {
            allValid = false;
        }
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        if (!allValid) {
            response.put("applied", Boolean.FALSE);
            response.put("message", "Batch rejected: one or more changes failed validation. Nothing was applied.");
            response.put("results", results);
            return response;
        }

        for (int i = 0; i < prepared.size(); i++) {
            PreparedChange pc = prepared.get(i);
            Map<String, Object> resultItem = results.get(i);
            if ("create_project".equals(pc.type)) {
                projectMutationService.applyProject(session, webUser, pc.projectDraft);
                resultItem.put("ref", pc.projectDraft.ref);
                resultItem.put("projectId", Integer.valueOf(pc.projectDraft.project.getProjectId()));
            } else if ("create_action".equals(pc.type)) {
                applyCreate(session, webUser, pc, resultItem);
            } else if ("update_action".equals(pc.type)) {
                applyUpdate(session, webUser, pc);
            } else if ("reschedule_action".equals(pc.type)) {
                applyReschedule(session, pc);
            } else if ("split_action".equals(pc.type)) {
                applySplit(session, webUser, pc, resultItem);
            } else if ("remove_action".equals(pc.type)) {
                applyRemove(session, pc);
            } else if ("complete_action".equals(pc.type)) {
                applyComplete(session, webUser, pc, resultItem);
            } else if ("order_day".equals(pc.type)) {
                applyOrderDay(session, workspaceId, webUser, pc, resultItem);
            } else if (pc.meetingChange != null) {
                if (pc.meetingCreate != null) {
                    pc.action = pc.meetingCreate.action;
                    pc.project = pc.meetingCreate.project;
                }
                pc.meetingResult = linkedMeetingService.apply(session, pc.meetingChange, pc.action);
                resultItem.put("actionNextId", pc.action.getActionNextId());
            }
            resultItem.put("status", "applied");
            if (pc.action != null) {
                String actionAsOf = McpActionContextSupport.toIso(pc.action.getNextChangeDate());
                resultItem.put("asOf", actionAsOf);
                if ("split_action".equals(pc.type)) {
                    resultItem.put("cancelledAsOf", actionAsOf);
                }
            }
            if ("split_action".equals(pc.type) && pc.createdActions != null) {
                List<String> createdAsOf = new ArrayList<String>();
                for (ActionNext created : pc.createdActions) {
                    createdAsOf.add(McpActionContextSupport.toIso(created.getNextChangeDate()));
                }
                resultItem.put("createdActionsAsOf", createdAsOf);
            }
            logChange(webUser, agentName, pc);
        }

        session.flush();
        for (int i = 0; i < prepared.size(); i++) {
            if (prepared.get(i).meetingChange != null) {
                results.get(i).put("linkedMeeting", LinkedMeetingService.toMap(prepared.get(i).meetingResult));
            }
        }
        response.put("applied", Boolean.TRUE);
        response.put("message", "All " + prepared.size() + " changes applied.");
        response.put("results", results);
        return response;
    }

    // ---- validation ----

    private PreparedChange validateItem(Session session, int workspaceId, WebUser webUser, int index, JsonNode item,
            List<PreparedChange> preceding) {
        PreparedChange pc = new PreparedChange();
        pc.index = index;
        pc.preceding = preceding;
        if (item == null || !item.isObject()) {
            pc.error = "Each change must be an object.";
            return pc;
        }
        String type = item.hasNonNull("type") ? item.get("type").asText() : null;
        pc.type = type;
        if (type == null) {
            pc.error = "\"type\" is required.";
            return pc;
        }
        if ("upsert_linked_meeting".equals(type) || "unlink_meeting".equals(type)) {
            validateMeeting(session, workspaceId, item, pc, preceding);
            return pc;
        }
        try {
            LinkedMeetingService.rejectUnknown(item, new HashSet<String>(Arrays.asList(
                    "type", "actionNextId", "asOf", "projectId", "projectRef", "description", "nextActionType",
                    "scheduledDate", "deadlineDate", "targetDate", "estimateMinutes", "notes", "addNote", "linkUrl",
                    "reason", "completedAt", "durationMinutes", "completionDescription", "outcomeNote", "followUp",
                    "newActions", "date",
                    "actions", "ref", "name", "billCode", "tags", "currentFocus", "allowDuplicate",
                    "projectHandle")));
        } catch (McpToolException e) {
            pc.error = e.getMessage() + " Use upsert_linked_meeting for meeting source fields.";
            return pc;
        }
        if ("create_project".equals(type)) {
            List<McpProjectMutationService.ProjectDraft> previousProjects =
                    new ArrayList<McpProjectMutationService.ProjectDraft>();
            for (PreparedChange previous : preceding) {
                if (previous.projectDraft != null) {
                    previousProjects.add(previous.projectDraft);
                }
            }
            pc.projectDraft = projectMutationService.prepareProject(session, workspaceId, webUser, item,
                    previousProjects);
            pc.error = pc.projectDraft.error;
        } else if ("create_action".equals(type)) {
            validateCreate(session, workspaceId, item, pc);
        } else if ("update_action".equals(type)) {
            validateUpdate(session, workspaceId, webUser, item, pc);
        } else if ("reschedule_action".equals(type)) {
            validateReschedule(session, workspaceId, item, pc);
        } else if ("split_action".equals(type)) {
            validateSplit(session, workspaceId, item, pc);
        } else if ("remove_action".equals(type)) {
            validateRemove(session, workspaceId, item, pc);
        } else if ("complete_action".equals(type)) {
            validateComplete(session, workspaceId, webUser, item, pc);
        } else if ("order_day".equals(type)) {
            validateOrderDay(session, workspaceId, webUser, item, pc);
        } else {
            pc.error = "Unknown change type \"" + type + "\".";
        }
        return pc;
    }

    private void validateMeeting(Session session, int workspaceId, JsonNode item, PreparedChange pc,
            List<PreparedChange> preceding) {
        try {
            LinkedMeetingService.rejectUnknown(item, new HashSet<String>(Arrays.asList("type", "actionNextId",
                    "asOf", "actionChangeIndex", "expectedMeetingVersion", "meeting")));
            if (item.has("actionChangeIndex")) {
                JsonNode index = item.get("actionChangeIndex");
                if (item.has("actionNextId") || item.has("asOf") || !index.isIntegralNumber()
                        || !index.canConvertToInt() || index.intValue() < 0 || index.intValue() >= preceding.size()) {
                    throw new McpToolException("invalid_arguments",
                            "actionChangeIndex must reference an earlier create_action, instead of actionNextId/asOf.");
                }
                PreparedChange create = preceding.get(index.intValue());
                if (!"create_action".equals(create.type) || create.error != null) {
                    throw new McpToolException("invalid_arguments", "Referenced create_action is invalid.");
                }
                pc.meetingCreate = create;
                pc.action = new ActionNext();
                pc.action.setWorkspaceId(workspaceId);
                pc.action.setNextActionType(create.newActionType);
            } else if (!resolveAndCheckAction(session, workspaceId, item, pc, true)) {
                return;
            }
            if (item.has("actionNextId") && (!item.get("actionNextId").isIntegralNumber()
                    || !item.get("actionNextId").canConvertToInt())) {
                throw new McpToolException("invalid_arguments", "actionNextId must be an integer.");
            }
            if ("unlink_meeting".equals(pc.type) && item.has("meeting")) {
                throw new McpToolException("invalid_arguments", "unlink_meeting does not accept a meeting snapshot.");
            }
            pc.meetingChange = linkedMeetingService.prepare(session, workspaceId, pc.action, item,
                    "unlink_meeting".equals(pc.type));
        } catch (McpToolException e) {
            pc.error = e.getCode() + ": " + e.getMessage();
        }
    }

    private void validateCreate(Session session, int workspaceId, JsonNode item, PreparedChange pc) {
        if (item.has("projectId") && item.has("projectRef")) {
            pc.error = "Use either \"projectId\" or \"projectRef\", not both.";
            return;
        }
        if (item.hasNonNull("projectRef")) {
            pc.project = resolveProjectRef(item.get("projectRef"), pc, pc.preceding);
        } else if (item.hasNonNull("projectId") && item.get("projectId").isIntegralNumber()
                && item.get("projectId").canConvertToInt()) {
            pc.project = requireProjectInWorkspace(session, workspaceId, item.get("projectId").asInt());
        } else {
            pc.error = "\"projectId\" or an earlier \"projectRef\" is required.";
            return;
        }
        if (pc.error != null) {
            return;
        }
        Project project = pc.project;
        if (project == null) {
            pc.error = "Project not found.";
            return;
        }
        if (!item.hasNonNull("description") || item.get("description").asText().trim().length() == 0) {
            pc.error = "\"description\" is required.";
            return;
        }
        String actionType = item.hasNonNull("nextActionType") ? item.get("nextActionType").asText()
                : ProjectNextActionType.WILL;
        if (!isValidActionType(actionType)) {
            pc.error = "Invalid nextActionType \"" + actionType + "\".";
            return;
        }
        pc.project = project;
        pc.newDescription = item.get("description").asText().trim();
        pc.newActionType = actionType;
        pc.newScheduledDate = parseNullableDate(item, "scheduledDate", pc);
        if (pc.error != null) {
            return;
        }
        pc.newDeadlineDate = parseNullableDate(item, "deadlineDate", pc);
        if (pc.error != null) {
            return;
        }
        pc.newTargetDate = parseNullableDate(item, "targetDate", pc);
        if (pc.error != null) {
            return;
        }
        pc.newEstimateMinutes = Integer.valueOf(item.hasNonNull("estimateMinutes")
                ? clampEstimate(item.get("estimateMinutes").asInt())
                : DEFAULT_ESTIMATE_MINUTES);
        pc.newNotes = item.hasNonNull("notes") ? item.get("notes").asText() : null;
        String notesError = actionNotesService.lengthError(pc.newNotes);
        if (notesError != null) {
            pc.error = notesError;
            return;
        }
        pc.newLinkUrl = parseLinkUrl(item, pc);
    }

    private void validateUpdate(Session session, int workspaceId, WebUser webUser, JsonNode item,
            PreparedChange pc) {
        // Changing only the link or the notes describes today's occurrence, not the
        // template, so those are allowed on template-generated instances (I-12).
        boolean instanceAllowed = !item.has("description") && !item.has("nextActionType")
                && !item.has("estimateMinutes") && !item.has("projectId") && !item.has("projectRef");
        if (!resolveAndCheckAction(session, workspaceId, item, pc, instanceAllowed)) {
            return;
        }
        if (item.has("projectId") || item.has("projectRef")) {
            if (item.has("projectId") && item.has("projectRef")) {
                pc.error = "Use either \"projectId\" or \"projectRef\" to move an action, not both.";
                return;
            }
            Project destination;
            if (item.hasNonNull("projectRef")) {
                destination = resolveProjectRef(item.get("projectRef"), pc, pc.preceding);
            } else if (item.has("projectId") && item.get("projectId").isIntegralNumber()
                    && item.get("projectId").canConvertToInt()) {
                destination = requireProjectInWorkspace(session, workspaceId, item.get("projectId").asInt());
            } else {
                pc.error = "\"projectId\" or \"projectRef\" must identify a destination project.";
                return;
            }
            if (pc.error != null) {
                return;
            }
            pc.oldProject = pc.project;
            String moveError = projectMutationService.validateMove(session, workspaceId, webUser, pc.action,
                    destination);
            if (moveError != null) {
                pc.error = moveError;
                return;
            }
            pc.project = destination;
            pc.hasProjectChange = true;
        }
        if (item.has("description")) {
            pc.hasDescription = true;
            String description = item.get("description").isNull() ? "" : item.get("description").asText().trim();
            if (description.length() == 0) {
                pc.error = "\"description\" cannot be empty.";
                return;
            }
            pc.description = description;
        }
        if (item.has("nextActionType")) {
            pc.hasActionType = true;
            String type = item.get("nextActionType").asText();
            if (!isValidActionType(type)) {
                pc.error = "Invalid nextActionType \"" + type + "\".";
                return;
            }
            pc.actionType = type;
            String meetingError = LinkedMeetingService.actionTypeError(session, pc.action, type);
            if (meetingError != null) {
                pc.error = meetingError;
                return;
            }
        }
        if (item.has("estimateMinutes")) {
            pc.hasEstimate = true;
            pc.estimateMinutes = Integer.valueOf(clampEstimate(item.get("estimateMinutes").asInt()));
        }
        if (item.has("notes") && item.has("addNote")) {
            pc.error = "Use either \"notes\" (replaces the notes) or \"addNote\" (appends), not both.";
            return;
        }
        if (item.has("notes")) {
            pc.hasNotes = true;
            pc.notes = item.get("notes").isNull() ? "" : item.get("notes").asText();
            String notesError = actionNotesService.lengthError(pc.notes);
            if (notesError != null) {
                pc.error = notesError;
                return;
            }
        }
        if (item.has("addNote")) {
            pc.hasAddNote = true;
            pc.addNote = item.get("addNote").isNull() ? null : item.get("addNote").asText();
            String notesError = pc.addNote == null ? null
                    : actionNotesService.lengthError(actionNotesService.read(pc.action) + "\n" + pc.addNote.trim());
            if (notesError != null) {
                pc.error = notesError;
                return;
            }
        }
        if (item.has("linkUrl")) {
            pc.hasLinkUrl = true;
            pc.linkUrl = parseLinkUrl(item, pc);
            if (pc.error != null) {
                return;
            }
        }
        if (!pc.hasDescription && !pc.hasActionType && !pc.hasEstimate && !pc.hasNotes && !pc.hasAddNote
                && !pc.hasLinkUrl && !pc.hasProjectChange) {
            pc.error = "At least one update field, including projectId or projectRef, must be provided.";
        }
    }

    private void validateReschedule(Session session, int workspaceId, JsonNode item, PreparedChange pc) {
        if (!resolveAndCheckAction(session, workspaceId, item, pc, false)) {
            return;
        }
        if (item.has("scheduledDate")) {
            pc.hasScheduledDate = true;
            pc.scheduledDateValue = parseNullableDate(item, "scheduledDate", pc);
            if (pc.error != null) {
                return;
            }
        }
        if (item.has("deadlineDate")) {
            pc.hasDeadlineDate = true;
            pc.deadlineDateValue = parseNullableDate(item, "deadlineDate", pc);
            if (pc.error != null) {
                return;
            }
        }
        if (item.has("targetDate")) {
            pc.hasTargetDate = true;
            pc.targetDateValue = parseNullableDate(item, "targetDate", pc);
            if (pc.error != null) {
                return;
            }
        }
        if (!pc.hasScheduledDate && !pc.hasDeadlineDate && !pc.hasTargetDate) {
            pc.error = "At least one of scheduledDate, deadlineDate, targetDate must be provided.";
        }
    }

    private void validateSplit(Session session, int workspaceId, JsonNode item, PreparedChange pc) {
        if (!resolveAndCheckAction(session, workspaceId, item, pc, false)) {
            return;
        }
        if (!item.has("newActions") || !item.get("newActions").isArray() || item.get("newActions").size() == 0) {
            pc.error = "\"newActions\" must be a non-empty array.";
            return;
        }
        List<NewActionSpec> specs = new ArrayList<NewActionSpec>();
        for (JsonNode newActionNode : item.get("newActions")) {
            try {
                LinkedMeetingService.requireObject(newActionNode, "newActions entry");
                LinkedMeetingService.rejectUnknown(newActionNode, new HashSet<String>(Arrays.asList(
                        "description", "nextActionType", "scheduledDate", "estimateMinutes", "linkUrl")));
            } catch (McpToolException e) {
                pc.error = e.getMessage();
                return;
            }
            NewActionSpec spec = new NewActionSpec();
            if (!newActionNode.hasNonNull("description")
                    || newActionNode.get("description").asText().trim().length() == 0) {
                pc.error = "Each entry in \"newActions\" requires a non-empty \"description\".";
                return;
            }
            spec.description = newActionNode.get("description").asText().trim();
            String type = newActionNode.hasNonNull("nextActionType") ? newActionNode.get("nextActionType").asText()
                    : pc.action.getNextActionType();
            if (!isValidActionType(type)) {
                pc.error = "Invalid nextActionType \"" + type + "\" in newActions.";
                return;
            }
            spec.actionType = type;
            if (newActionNode.hasNonNull("scheduledDate")) {
                try {
                    spec.scheduledDate = java.sql.Date.valueOf(LocalDate.parse(newActionNode.get("scheduledDate")
                            .asText()));
                } catch (DateTimeParseException e) {
                    pc.error = "Invalid \"scheduledDate\" in newActions; must be yyyy-MM-dd.";
                    return;
                }
            }
            spec.estimateMinutes = Integer.valueOf(newActionNode.hasNonNull("estimateMinutes")
                    ? clampEstimate(newActionNode.get("estimateMinutes").asInt())
                    : DEFAULT_ESTIMATE_MINUTES);
            spec.linkUrl = parseLinkUrl(newActionNode, pc);
            if (pc.error != null) {
                return;
            }
            specs.add(spec);
        }
        pc.newActions = specs;
    }

    private void validateRemove(Session session, int workspaceId, JsonNode item, PreparedChange pc) {
        if (!resolveAndCheckAction(session, workspaceId, item, pc, false)) {
            return;
        }
        pc.removeReason = item.hasNonNull("reason") ? item.get("reason").asText() : null;
    }

    private void validateComplete(Session session, int workspaceId, WebUser webUser, JsonNode item,
            PreparedChange pc) {
        if (!resolveAndCheckAction(session, workspaceId, item, pc, true)) {
            return;
        }
        // "completedAt" is when the work finished (the end of the time entry), matching
        // how a
        // person would naturally describe it ("I finished this just now, took me 10
        // minutes").
        // ActionCompletionService's completionMoment parameter is the START of the
        // entry, so it
        // has to be derived by subtracting the duration -- passing completedAt straight
        // through
        // as the start made any positive duration push the computed end into the future
        // and fail
        // validation unconditionally (found in live testing).
        Date completedAtEnd;
        if (item.hasNonNull("completedAt")) {
            try {
                completedAtEnd = Date.from(java.time.Instant.parse(item.get("completedAt").asText()));
            } catch (java.time.format.DateTimeParseException e) {
                pc.error = "\"completedAt\" must be an ISO-8601 instant, e.g. 2026-10-01T17:00:00Z.";
                return;
            }
        } else {
            completedAtEnd = McpActionContextSupport.truncatedNow();
        }
        int durationMinutes = item.hasNonNull("durationMinutes") ? item.get("durationMinutes").asInt() : 0;
        if (durationMinutes < 0) {
            pc.error = "\"durationMinutes\" cannot be negative.";
            return;
        }
        if (durationMinutes > ActionCompletionService.MAX_DURATION_MINS) {
            pc.error = "\"durationMinutes\" cannot be more than " + ActionCompletionService.MAX_DURATION_MINS + ".";
            return;
        }
        Date completionStart = new Date(completedAtEnd.getTime() - (durationMinutes * 60000L));
        CompletionTime completionTime = CompletionTime.of(completionStart, durationMinutes);
        String validationError = actionCompletionService.validateCompletion(webUser, session, pc.action,
                completionTime);
        if (validationError != null) {
            pc.error = validationError;
            return;
        }
        pc.completedAt = completionStart;
        pc.durationMinutes = durationMinutes;
        pc.completionDescription = item.hasNonNull("completionDescription")
                ? item.get("completionDescription").asText()
                : pc.action.getNextDescription();
        pc.outcomeNote = item.hasNonNull("outcomeNote") ? item.get("outcomeNote").asText() : null;
        String followUpNotes = null;
        JsonNode followUpNode = item.get("followUp");
        if (followUpNode != null && !followUpNode.isNull()) {
            if (!followUpNode.isObject()) {
                pc.error = "\"followUp\" must be an object.";
                return;
            }
            try {
                LinkedMeetingService.rejectUnknown(followUpNode, new HashSet<String>(Arrays.asList(
                        "description", "nextActionType", "scheduledDate", "deadlineDate", "targetDate",
                        "estimateMinutes", "notes", "linkUrl")));
            } catch (McpToolException e) {
                pc.error = "followUp: " + e.getMessage()
                        + " A follow-up is always created in the completed action's project.";
                return;
            }
            followUpNotes = followUpNode.hasNonNull("notes") ? followUpNode.get("notes").asText() : null;
        }
        String handoffError = actionCompletionService.validateHandoff(pc.outcomeNote, followUpNotes);
        if (handoffError != null) {
            pc.error = handoffError;
            return;
        }
        if (followUpNode != null && followUpNode.isObject()) {
            ObjectNode createItem = ((ObjectNode) followUpNode).deepCopy();
            createItem.put("type", "create_action");
            createItem.put("projectId", pc.action.getProjectId());
            PreparedChange followUp = new PreparedChange();
            followUp.index = pc.index;
            followUp.type = "create_action";
            followUp.preceding = pc.preceding;
            validateCreate(session, workspaceId, createItem, followUp);
            if (followUp.error != null) {
                pc.error = "followUp: " + followUp.error;
                return;
            }
            pc.followUp = followUp;
        }
    }

    private void validateOrderDay(Session session, int workspaceId, WebUser webUser, JsonNode item,
            PreparedChange pc) {
        if (!item.hasNonNull("date")) {
            pc.error = "\"date\" is required.";
            return;
        }
        LocalDate day;
        try {
            day = LocalDate.parse(item.get("date").asText());
        } catch (DateTimeParseException e) {
            pc.error = "\"date\" must be yyyy-MM-dd.";
            return;
        }
        if (!item.has("actions") || !item.get("actions").isArray() || item.get("actions").size() == 0) {
            pc.error = "\"actions\" must be a non-empty array of {actionNextId, asOf}.";
            return;
        }
        LocalDate today = webUser.getLocalDateToday();
        List<ActionNext> ordered = new ArrayList<ActionNext>();
        List<Project> orderedProjects = new ArrayList<Project>();
        Set<Integer> seen = new HashSet<Integer>();
        for (JsonNode entry : item.get("actions")) {
            if (entry == null || !entry.hasNonNull("actionNextId") || entry.get("actionNextId").asInt() <= 0) {
                pc.error = "Each entry in \"actions\" requires \"actionNextId\".";
                return;
            }
            int actionNextId = entry.get("actionNextId").asInt();
            if (!seen.add(Integer.valueOf(actionNextId))) {
                pc.error = "Action " + actionNextId + " is listed more than once.";
                return;
            }
            ActionNext action = (ActionNext) session.get(ActionNext.class, Integer.valueOf(actionNextId));
            if (action == null || action.getWorkspaceId() == null
                    || action.getWorkspaceId().intValue() != workspaceId) {
                pc.error = "Action " + actionNextId + " not found.";
                return;
            }
            if (action.isTemplate()) {
                pc.error = "template_managed: action " + actionNextId + " is a template root and can't be ordered.";
                return;
            }
            if (!ProjectNextActionStatus.READY.getId().equals(action.getNextActionStatusString())) {
                pc.error = "Action " + actionNextId + " is not open (status "
                        + action.getNextActionStatusString() + ").";
                return;
            }
            if (!isOnDay(action, day, today, webUser)) {
                pc.error = "Action " + actionNextId + " is not scheduled on " + day
                        + (day.equals(today) ? " (or overdue)" : "") + ".";
                return;
            }
            if (!entry.hasNonNull("asOf")) {
                pc.error = "Each entry in \"actions\" requires \"asOf\" (action " + actionNextId + ").";
                return;
            }
            String currentAsOf = McpActionContextSupport.toIso(action.getNextChangeDate());
            if (currentAsOf == null || !currentAsOf.equals(entry.get("asOf").asText())) {
                pc.error = "stale: action " + actionNextId + " has changed since it was last read"
                        + (currentAsOf != null ? " (current asOf: " + currentAsOf + ")" : "") + ".";
                return;
            }
            Project project = action.getProject();
            if (project == null && action.getProjectId() > 0) {
                project = (Project) session.get(Project.class, Integer.valueOf(action.getProjectId()));
            }
            ordered.add(action);
            orderedProjects.add(project);
        }
        pc.orderDay = day;
        pc.orderedActions = ordered;
        pc.orderedProjects = orderedProjects;
    }

    /**
     * The dashboard's day: actions scheduled on the date, plus, when the date
     * is today, anything still open from earlier days (the Overdue bucket).
     */
    private boolean isOnDay(ActionNext action, LocalDate day, LocalDate today, WebUser webUser) {
        LocalDate actionDate = webUser.toLocalDate(action.getNextActionDate());
        if (actionDate == null) {
            return false;
        }
        if (actionDate.equals(day)) {
            return true;
        }
        return day.equals(today) && actionDate.isBefore(today);
    }

    /**
     * @param instanceAllowed true when the change may be applied to a
     *                        template-generated instance (completing it, or
     *                        updating only its link or notes). Template roots
     *                        are always rejected.
     */
    private boolean resolveAndCheckAction(Session session, int workspaceId, JsonNode item, PreparedChange pc,
            boolean instanceAllowed) {
        if (!item.hasNonNull("actionNextId") || item.get("actionNextId").asInt() <= 0) {
            pc.error = "\"actionNextId\" is required.";
            return false;
        }
        int actionNextId = item.get("actionNextId").asInt();
        pc.actionNextId = Integer.valueOf(actionNextId);
        ActionNext action = (ActionNext) session.get(ActionNext.class, Integer.valueOf(actionNextId));
        if (action == null || action.getWorkspaceId() == null || action.getWorkspaceId().intValue() != workspaceId) {
            pc.error = "Action not found.";
            return false;
        }
        session.refresh(action, LockMode.UPGRADE);
        boolean isRoot = action.isTemplate();
        boolean isInstance = action.getTemplateActionNextId() != null;
        if (instanceAllowed) {
            if (isRoot) {
                pc.error = "template_managed: cannot change a template root; edit templates via the Dandelion UI.";
                return false;
            }
        } else if (isRoot || isInstance) {
            pc.error = "template_managed: template-managed actions can only be changed via the Dandelion UI "
                    + "(the exceptions for a generated instance are complete_action, order_day, and an "
                    + "update_action that only sets linkUrl, notes or addNote).";
            return false;
        }
        if (!item.hasNonNull("asOf")) {
            pc.error = "\"asOf\" is required.";
            return false;
        }
        String asOf = item.get("asOf").asText();
        String currentAsOf = McpActionContextSupport.toIso(action.getNextChangeDate());
        if (currentAsOf == null || !currentAsOf.equals(asOf)) {
            pc.error = "stale: action has changed since it was last read"
                    + (currentAsOf != null ? " (current asOf: " + currentAsOf + ")" : "") + ".";
            return false;
        }
        pc.action = action;
        Project project = action.getProject();
        if (project == null && action.getProjectId() > 0) {
            project = (Project) session.get(Project.class, Integer.valueOf(action.getProjectId()));
        }
        pc.project = project;
        return true;
    }

    // ---- apply ----

    private void applyCreate(Session session, WebUser webUser, PreparedChange pc,
            Map<String, Object> resultItem) {
        ActionNext action = new ActionNext();
        action.setProject(pc.project);
        action.setProjectId(pc.project.getProjectId());
        action.setWorkspaceId(pc.project.getWorkspaceId());
        ProjectContact ownerContact = webUser.getProjectContact();
        if (ownerContact == null) {
            ownerContact = (ProjectContact) session.get(ProjectContact.class,
                    Integer.valueOf(webUser.getContactId()));
        }
        action.setContact(ownerContact);
        action.setContactId(webUser.getContactId());
        action.setNextActionType(pc.newActionType);
        action.setNextDescription(pc.newDescription);
        action.setNextSummary(pc.newDescription);
        action.setNextTimeEstimate(pc.newEstimateMinutes);
        action.setNextActionStatus(ProjectNextActionStatus.READY);
        action.setNextActionDate(pc.newScheduledDate);
        action.setNextDeadlineDate(pc.newDeadlineDate);
        action.setNextTargetDate(pc.newTargetDate);
        action.setLinkUrl(pc.newLinkUrl);
        action.setNextChangeDate(McpActionContextSupport.truncatedNow());
        action.setPriorityLevel(ProjectNextActionType.defaultPriority(pc.newActionType));
        action.setBillable(isBillable(pc.project));
        ActionSet actionSet = new ActionSetDao(session).createActionSet(webUser, ActionSetType.STANDARD);
        action.setActionSet(actionSet);
        actionNotesService.initialize(action, pc.newNotes);
        session.save(action);
        pc.action = action;
        resultItem.put("createdActionNextId", Integer.valueOf(action.getActionNextId()));
    }

    private void applyUpdate(Session session, WebUser webUser, PreparedChange pc) {
        ActionNext action = pc.action;
        if (pc.hasDescription) {
            action.setNextDescription(pc.description);
        }
        if (pc.hasActionType) {
            action.setNextActionType(pc.actionType);
        }
        if (pc.hasEstimate) {
            action.setNextTimeEstimate(pc.estimateMinutes);
        }
        if (pc.hasLinkUrl) {
            action.setLinkUrl(pc.linkUrl);
        }
        // Notes reach the action's SHARED twins too; written before a move so the
        // move's monotonic change date is the one this action keeps.
        ActionNotesService.Result notesResult = null;
        if (pc.hasNotes) {
            notesResult = actionNotesService.replace(session, action, pc.notes, null,
                    McpActionContextSupport.truncatedNow());
        } else if (pc.hasAddNote && pc.addNote != null && pc.addNote.trim().length() > 0) {
            notesResult = actionNotesService.append(session, action, pc.addNote,
                    McpActionContextSupport.truncatedNow());
        }
        if (notesResult != null && !notesResult.isOk()) {
            throw new IllegalStateException(notesResult.getErrorMessage());
        }
        if (pc.hasProjectChange) {
            projectMutationService.applyMove(session, action, pc.project,
                    McpProjectMutationService.monotonicChangeDate(action.getNextChangeDate()));
        }
        if (!pc.hasProjectChange) {
            action.setNextChangeDate(McpActionContextSupport.truncatedNow());
        }
        session.update(action);
    }

    private void applyReschedule(Session session, PreparedChange pc) {
        ActionNext action = pc.action;
        if (pc.hasScheduledDate) {
            action.setNextActionDate(pc.scheduledDateValue);
        }
        if (pc.hasDeadlineDate) {
            action.setNextDeadlineDate(pc.deadlineDateValue);
        }
        if (pc.hasTargetDate) {
            action.setNextTargetDate(pc.targetDateValue);
        }
        action.setNextChangeDate(McpActionContextSupport.truncatedNow());
        session.update(action);
    }

    private void applySplit(Session session, WebUser webUser, PreparedChange pc,
            Map<String, Object> resultItem) {
        ActionNext original = pc.action;
        original.setNextActionStatus(ProjectNextActionStatus.CANCELLED);
        original.setNextChangeDate(McpActionContextSupport.truncatedNow());
        session.update(original);

        List<Integer> createdIds = new ArrayList<Integer>();
        List<ActionNext> createdActions = new ArrayList<ActionNext>();
        for (NewActionSpec spec : pc.newActions) {
            ActionNext action = new ActionNext();
            action.setProject(pc.project);
            action.setProjectId(pc.project.getProjectId());
            action.setWorkspaceId(pc.project.getWorkspaceId());
            action.setContact(webUser.getProjectContact());
            action.setContactId(webUser.getContactId());
            action.setNextActionType(spec.actionType);
            action.setNextDescription(spec.description);
            action.setNextSummary(spec.description);
            action.setNextTimeEstimate(spec.estimateMinutes);
            action.setNextActionStatus(ProjectNextActionStatus.READY);
            action.setNextActionDate(spec.scheduledDate);
            action.setLinkUrl(spec.linkUrl);
            action.setNextChangeDate(McpActionContextSupport.truncatedNow());
            action.setPriorityLevel(ProjectNextActionType.defaultPriority(spec.actionType));
            action.setBillable(isBillable(pc.project));
            action.setActionSet(new ActionSetDao(session).createActionSet(webUser, ActionSetType.STANDARD));
            session.save(action);
            createdIds.add(Integer.valueOf(action.getActionNextId()));
            createdActions.add(action);
        }
        pc.createdActions = createdActions;
        resultItem.put("cancelledActionNextId", Integer.valueOf(original.getActionNextId()));
        resultItem.put("createdActionNextIds", createdIds);
    }

    private void applyRemove(Session session, PreparedChange pc) {
        pc.action.setNextActionStatus(ProjectNextActionStatus.CANCELLED);
        pc.action.setNextChangeDate(McpActionContextSupport.truncatedNow());
        session.update(pc.action);
    }

    /**
     * Writes completionOrder 1..n down the whole day in dashboard order, the
     * same numbering DashboardCurrentActionService's rationalize step uses, so
     * the dashboard keeps it (it only fills in actions whose order is 0).
     */
    private void applyOrderDay(Session session, int workspaceId, WebUser webUser, PreparedChange pc,
            Map<String, Object> resultItem) {
        boolean includeOverdue = pc.orderDay.equals(webUser.getLocalDateToday());
        Query query = session.createQuery("from ActionNext an where an.workspaceId = :workspaceId "
                + "and an.nextActionStatusString = :ready "
                + "and (an.templateTypeString is null or an.templateTypeString = '') "
                + "and an.nextActionDate < :dayEnd"
                + (includeOverdue ? "" : " and an.nextActionDate >= :dayStart"));
        query.setInteger("workspaceId", workspaceId);
        query.setString("ready", ProjectNextActionStatus.READY.getId());
        query.setParameter("dayEnd", java.sql.Date.valueOf(pc.orderDay.plusDays(1)));
        if (!includeOverdue) {
            query.setParameter("dayStart", java.sql.Date.valueOf(pc.orderDay));
        }
        @SuppressWarnings("unchecked")
        List<ActionNext> dayActions = query.list();

        List<ActionNext> newOrder = DashboardActionOrdering.applyRequestedOrder(dayActions, pc.orderedActions,
                webUser);
        Set<Integer> requestedIds = new HashSet<Integer>();
        for (ActionNext action : pc.orderedActions) {
            requestedIds.add(Integer.valueOf(action.getActionNextId()));
        }
        Date now = McpActionContextSupport.truncatedNow();
        List<Map<String, Object>> ordered = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> renumbered = new ArrayList<Map<String, Object>>();
        int completionOrder = 1;
        for (ActionNext action : newOrder) {
            boolean changed = action.getCompletionOrder() != completionOrder;
            if (changed) {
                action.setCompletionOrder(completionOrder);
                action.setNextChangeDate(now);
                session.update(action);
            }
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("actionNextId", Integer.valueOf(action.getActionNextId()));
            entry.put("completionOrder", Integer.valueOf(completionOrder));
            entry.put("dashboardBucket", DashboardActionOrdering.getBucketLabel(
                    DashboardActionOrdering.getCompletionBucket(action, webUser)));
            entry.put("asOf", McpActionContextSupport.toIso(action.getNextChangeDate()));
            if (requestedIds.contains(Integer.valueOf(action.getActionNextId()))) {
                ordered.add(entry);
            } else if (changed) {
                renumbered.add(entry);
            }
            completionOrder++;
        }
        resultItem.put("date", pc.orderDay.toString());
        resultItem.put("orderedActions", ordered);
        resultItem.put("renumberedActions", renumbered);
    }

    private void applyComplete(Session session, WebUser webUser, PreparedChange pc,
            Map<String, Object> resultItem) {
        actionCompletionService.applyCompletion(session, webUser, pc.action, pc.completionDescription,
                pc.outcomeNote, ProjectNextActionStatus.COMPLETED, pc.completedAt, pc.durationMinutes);
        if (pc.followUp != null) {
            Map<String, Object> followUpResult = new LinkedHashMap<String, Object>();
            applyCreate(session, webUser, pc.followUp, followUpResult);
            resultItem.put("followUpActionNextId", followUpResult.get("createdActionNextId"));
            resultItem.put("followUpAsOf", McpActionContextSupport.toIso(pc.followUp.action.getNextChangeDate()));
        }
    }

    // ---- audit trail ----

    private void logChange(WebUser webUser, String agentName, PreparedChange pc) {
        if ("create_project".equals(pc.type)) {
            // ActionChangeLog requires both an existing action and project ID.
            // A project-only creation has no action to audit in this table;
            // its creator/modifier metadata is persisted on the project instead.
            return;
        }
        if ("order_day".equals(pc.type)) {
            for (int i = 0; i < pc.orderedActions.size(); i++) {
                writeChangeLog(webUser, agentName, pc.orderedActions.get(i), pc.orderedProjects.get(i), "order_day",
                        "Ordered " + (i + 1) + " of " + pc.orderedActions.size() + " within its bucket on "
                                + pc.orderDay);
            }
            return;
        }
        if ("split_action".equals(pc.type)) {
            writeChangeLog(webUser, agentName, pc.action, pc.project, "split_action",
                    "Split into actions " + describeCreatedIds(pc.createdActions));
            for (ActionNext created : pc.createdActions) {
                writeChangeLog(webUser, agentName, created, pc.project, "split_action",
                        "Created from split of action #" + pc.actionNextId);
            }
            return;
        }
        if ("update_action".equals(pc.type) && pc.hasProjectChange) {
            String oldName = pc.oldProject == null ? "unknown"
                    : pc.oldProject.getProjectName() + " (#" + pc.oldProject.getProjectId() + ")";
            String newName = pc.project == null ? "unknown"
                    : pc.project.getProjectName() + " (#" + pc.project.getProjectId() + ")";
            writeChangeLog(webUser, agentName, pc.action, pc.oldProject, "update_action",
                    "Moved from " + oldName + " to " + newName);
            writeChangeLog(webUser, agentName, pc.action, pc.project, "update_action",
                    "Moved from " + oldName + " to " + newName);
            return;
        }
        if ("complete_action".equals(pc.type) && pc.followUp != null) {
            writeChangeLog(webUser, agentName, pc.action, pc.project, "complete_action",
                    "Follow-up action #" + pc.followUp.action.getActionNextId());
            writeChangeLog(webUser, agentName, pc.followUp.action, pc.followUp.project, "create_action",
                    "Follow-up to completed action #" + pc.action.getActionNextId());
            return;
        }
        String changeReason = "remove_action".equals(pc.type) ? pc.removeReason : null;
        if ("update_action".equals(pc.type) && pc.hasLinkUrl) {
            changeReason = pc.linkUrl.length() == 0 ? "Cleared link" : "Set link to " + pc.linkUrl;
        }
        writeChangeLog(webUser, agentName, pc.action, pc.project, pc.type, changeReason);
    }

    private String describeCreatedIds(List<ActionNext> createdActions) {
        StringBuilder sb = new StringBuilder();
        for (ActionNext action : createdActions) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append("#").append(action.getActionNextId());
        }
        return sb.toString();
    }

    private void writeChangeLog(WebUser webUser, String agentName, ActionNext action, Project project,
            String changeSummary, String changeReason) {
        ActionChangeLog log = new ActionChangeLog();
        log.setAction(action);
        log.setProject(project);
        log.setChangeDate(new Date());
        log.setActorType(ActorType.AI);
        log.setActorId(clip(agentName != null ? agentName : "mcp", 60));
        log.setSourceType(clip("mcp_apply_changes", 24));
        log.setChangeSummary(clip(changeSummary, 4000));
        log.setChangeReason(changeReason);
        new ActionChangeLogDao().save(log);
    }

    // ---- shared helpers ----

    private boolean isBillable(Project project) {
        return project.getBillCode() != null && project.getBillCode().trim().length() > 0;
    }

    private boolean isValidActionType(String value) {
        return ProjectNextActionType.WILL.equals(value)
                || ProjectNextActionType.WILL_CONTACT.equals(value)
                || ProjectNextActionType.WILL_MEET.equals(value)
                || ProjectNextActionType.WILL_REVIEW.equals(value)
                || ProjectNextActionType.WILL_DOCUMENT.equals(value)
                || ProjectNextActionType.WILL_FOLLOW_UP.equals(value)
                || ProjectNextActionType.MIGHT.equals(value)
                || ProjectNextActionType.WOULD_LIKE_TO.equals(value)
                || ProjectNextActionType.COMMITTED_TO.equals(value)
                || ProjectNextActionType.GOAL.equals(value)
                || ProjectNextActionType.WAITING.equals(value)
                || ProjectNextActionType.OVERDUE_TO.equals(value);
    }

    private int clampEstimate(int value) {
        if (value < 0) {
            return 0;
        }
        if (value > MAX_ESTIMATE_MINUTES) {
            return MAX_ESTIMATE_MINUTES;
        }
        return value;
    }

    /**
     * Reads an optional "linkUrl": null or blank means no link (stored as "",
     * as the Dandelion UI does); otherwise it must be an absolute http(s) URL
     * that fits the link_url column.
     */
    private String parseLinkUrl(JsonNode item, PreparedChange pc) {
        if (!item.hasNonNull("linkUrl")) {
            return "";
        }
        String value = item.get("linkUrl").asText().trim();
        if (value.length() == 0) {
            return "";
        }
        String error = linkUrlError(value);
        if (error != null) {
            pc.error = error;
            return null;
        }
        return value;
    }

    /** Returns why a non-blank link isn't acceptable, or null when it is. */
    static String linkUrlError(String value) {
        if (value.length() > MAX_LINK_URL_LENGTH) {
            return "\"linkUrl\" is longer than " + MAX_LINK_URL_LENGTH + " characters.";
        }
        try {
            java.net.URI uri = new java.net.URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if ((!"http".equals(scheme) && !"https".equals(scheme)) || uri.getHost() == null) {
                return "\"linkUrl\" must be an http or https URL.";
            }
        } catch (java.net.URISyntaxException e) {
            return "\"linkUrl\" is not a valid URL.";
        }
        return null;
    }

    private Date parseNullableDate(JsonNode item, String field, PreparedChange pc) {
        if (!item.has(field)) {
            return null;
        }
        JsonNode node = item.get(field);
        if (node.isNull()) {
            return null;
        }
        try {
            return java.sql.Date.valueOf(LocalDate.parse(node.asText()));
        } catch (DateTimeParseException e) {
            pc.error = "\"" + field + "\" must be yyyy-MM-dd or null.";
            return null;
        }
    }

    private Project requireProjectInWorkspace(Session session, int workspaceId, int projectId) {
        Query query = session.createQuery(
                "from Project p where p.projectId = :projectId and p.workspaceId = :workspaceId");
        query.setInteger("projectId", projectId);
        query.setInteger("workspaceId", workspaceId);
        return (Project) query.uniqueResult();
    }

    private Project resolveProjectRef(JsonNode refNode, PreparedChange pc, List<PreparedChange> preceding) {
        if (!refNode.isTextual() || refNode.textValue().trim().length() == 0) {
            pc.error = "\"projectRef\" must be a non-empty string naming an earlier create_project.";
            return null;
        }
        String ref = refNode.textValue().trim();
        for (int i = preceding.size() - 1; i >= 0; i--) {
            PreparedChange previous = preceding.get(i);
            if (previous.projectDraft != null
                    && McpProjectMutationService.sameKey(previous.projectDraft.ref, ref)) {
                if (previous.error != null || previous.projectDraft.project == null) {
                    pc.error = "Referenced create_project \"" + ref + "\" is invalid.";
                    return null;
                }
                return previous.projectDraft.project;
            }
        }
        pc.error = "projectRef \"" + ref + "\" must refer to an earlier create_project in this batch.";
        return null;
    }

    private boolean rejectIncompatibleMovedActionChanges(List<PreparedChange> prepared,
            List<Map<String, Object>> results) {
        boolean rejected = false;
        for (int i = 0; i < prepared.size(); i++) {
            PreparedChange move = prepared.get(i);
            if (move.error != null || !move.hasProjectChange || move.action == null) {
                continue;
            }
            for (int j = 0; j < prepared.size(); j++) {
                if (i == j) {
                    continue;
                }
                PreparedChange other = prepared.get(j);
                if (other.error != null
                        || !McpProjectMutationService.conflictsWithMove(move.actionNextId.intValue(), other.type,
                                other.actionNextId, other.orderedActions)) {
                    continue;
                }
                String reason = "A moved action cannot have another action change in the same batch; combine "
                        + "compatible fields into one update_action.";
                move.error = reason;
                other.error = reason;
                results.get(i).put("status", "rejected");
                results.get(i).put("reason", reason);
                results.get(j).put("status", "rejected");
                results.get(j).put("reason", reason);
                rejected = true;
            }
        }
        return rejected;
    }

    private String clip(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    // ---- internal structures ----

    private static class PreparedChange {
        int index;
        String type;
        String error;
        List<PreparedChange> preceding;
        Integer actionNextId;
        ActionNext action;
        Project project;
        Project oldProject;
        boolean hasProjectChange;
        McpProjectMutationService.ProjectDraft projectDraft;

        // create_action
        String newDescription;
        String newActionType;
        Date newScheduledDate;
        Date newDeadlineDate;
        Date newTargetDate;
        Integer newEstimateMinutes;
        String newNotes;
        String newLinkUrl;

        // update_action (presence flags)
        boolean hasDescription;
        String description;
        boolean hasActionType;
        String actionType;
        boolean hasEstimate;
        Integer estimateMinutes;
        boolean hasNotes;
        String notes;
        boolean hasAddNote;
        String addNote;
        boolean hasLinkUrl;
        String linkUrl;

        // reschedule_action (presence flags; null value means clear)
        boolean hasScheduledDate;
        Date scheduledDateValue;
        boolean hasDeadlineDate;
        Date deadlineDateValue;
        boolean hasTargetDate;
        Date targetDateValue;

        // remove_action
        String removeReason;

        // complete_action
        Date completedAt;
        int durationMinutes;
        String completionDescription;
        String outcomeNote;
        PreparedChange followUp;

        // order_day
        LocalDate orderDay;
        List<ActionNext> orderedActions;
        List<Project> orderedProjects;

        // split_action
        List<NewActionSpec> newActions;
        List<ActionNext> createdActions;
        LinkedMeetingService.Prepared meetingChange;
        PreparedChange meetingCreate;
        LinkedMeeting meetingResult;
    }

    private static class NewActionSpec {
        String description;
        String actionType;
        Date scheduledDate;
        Integer estimateMinutes;
        String linkUrl;
    }
}
