package org.dandeliondaily.mcp.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.dandeliondaily.dashboard.service.DashboardActionOrdering;
import org.hibernate.Query;
import org.hibernate.Session;
import org.dandeliondaily.shared.service.ActionThreadService;
import org.dandeliondaily.shared.service.LinkedMeetingService;
import org.openimmunizationsoftware.pt.doa.LinkedMeetingDao;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ActionSet;
import org.openimmunizationsoftware.pt.model.ActionSetType;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.model.ProjectNextActionStatus;
import org.openimmunizationsoftware.pt.model.WebUser;

/**
 * Shared shaping of an ActionNext row for MCP read tools: exposes the
 * template-origin flags (see docs/Dandelion_Daily_AI_Integration_Assessment.md,
 * section 6, decision 14) and, for actions in a SHARED ActionSet, the identity
 * of the other project(s) the action is shared with, even when those projects
 * live in a different workspace than the calling client (decision 1).
 */
public final class McpActionContextSupport {

    private McpActionContextSupport() {
    }

    public static Map<String, Object> toActionMap(Session session, ActionNext action, boolean includeProjectName) {
        return toActionMap(session, action, includeProjectName, null);
    }

    /**
     * With a webUser, also reports the dashboard Today-column bucket the action
     * falls in (see DashboardActionOrdering); completionOrder only orders actions
     * within a bucket.
     */
    public static Map<String, Object> toActionMap(Session session, ActionNext action, boolean includeProjectName,
            WebUser webUser) {
        return toActionMap(session, action, includeProjectName, webUser, true);
    }

    public static Map<String, Object> toActionMap(Session session, ActionNext action, boolean includeProjectName,
            WebUser webUser, boolean includePrivateContext) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("actionNextId", action.getActionNextId());
        map.put("projectId", action.getProjectId());
        if (includeProjectName) {
            Project project = resolveProject(session, action);
            map.put("projectName", project == null ? null : project.getProjectName());
        }
        map.put("description", action.getNextDescription());
        map.put("nextActionType", action.getNextActionType());
        map.put("status", action.getNextActionStatusString());
        map.put("asOf", toIso(action.getNextChangeDate()));
        map.put("scheduledDate", toDateOnly(action.getNextActionDate()));
        map.put("deadlineDate", toDateOnly(action.getNextDeadlineDate()));
        map.put("targetDate", toDateOnly(action.getNextTargetDate()));
        map.put("estimateMinutes", action.getNextTimeEstimate());
        map.put("actualMinutes", action.getNextTimeActual());
        map.put("notes", action.getNextNotes());
        map.put("linkUrl", action.getLinkUrl());
        map.put("completionOrder", action.getCompletionOrder());
        map.put("priorityLevel", action.getPriorityLevel());
        if (includePrivateContext && action.getWorkspaceId() != null) {
            map.put("linkedMeeting", LinkedMeetingService.toMap(
                    new LinkedMeetingDao(session).findByAction(action.getWorkspaceId(), action.getActionNextId())));
        }
        if (webUser != null) {
            boolean open = action.getNextActionStatus() == ProjectNextActionStatus.READY
                    || action.getNextActionStatus() == ProjectNextActionStatus.PROPOSED;
            map.put("dashboardBucket", open ? DashboardActionOrdering.getBucketLabel(
                    DashboardActionOrdering.getCompletionBucket(action, webUser)) : null);
        }
        map.put("rescheduleLocked", action.isRescheduleLocked());
        boolean isTemplateRoot = action.isTemplate();
        map.put("isTemplateRoot", isTemplateRoot);
        map.put("generatedFromTemplateId", action.getTemplateActionNextId());
        map.put("templateManaged", isTemplateRoot || action.getTemplateActionNextId() != null);
        List<Map<String, Object>> sharedWith = includePrivateContext ? resolveSharedProjects(session, action)
                : new ArrayList<Map<String, Object>>();
        if (!sharedWith.isEmpty()) {
            map.put("sharedWithProjects", sharedWith);
        }
        if (action.getFollowsActionNextId() != null) {
            map.put("followsActionNextId", action.getFollowsActionNextId());
            if (includePrivateContext) {
                ActionThreadService.Thread thread = THREAD_SERVICE.history(session, action, HISTORY_STEPS);
                map.put("history", toHistoryList(thread));
                map.put("historyContinues", Boolean.valueOf(thread.isMore()));
            }
        }
        return map;
    }

    /** How many earlier steps of a thread an action map carries. */
    public static final int HISTORY_STEPS = 3;

    private static final ActionThreadService THREAD_SERVICE = new ActionThreadService();

    /** A thread's earlier steps for an MCP result, newest first. */
    public static List<Map<String, Object>> toHistoryList(ActionThreadService.Thread thread) {
        List<Map<String, Object>> steps = new ArrayList<Map<String, Object>>();
        for (ActionThreadService.Step step : thread.getSteps()) {
            Map<String, Object> stepMap = new LinkedHashMap<String, Object>();
            stepMap.put("actionNextId", Integer.valueOf(step.getActionNextId()));
            stepMap.put("description", step.getDescription());
            stepMap.put("status", step.getStatus());
            stepMap.put("date", toIso(step.getDate()));
            if (!step.getDone().isEmpty()) {
                stepMap.put("done", step.getDone());
            }
            if (!step.getOutcomeNotes().isEmpty()) {
                stepMap.put("outcomeNotes", step.getOutcomeNotes());
            }
            if (step.getNotes() != null) {
                stepMap.put("notes", step.getNotes());
            }
            if (step.getMinutes() > 0) {
                stepMap.put("minutes", Integer.valueOf(step.getMinutes()));
            }
            steps.add(stepMap);
        }
        return steps;
    }

    private static Project resolveProject(Session session, ActionNext action) {
        Project project = action.getProject();
        if (project == null && action.getProjectId() > 0) {
            project = (Project) session.get(Project.class, action.getProjectId());
        }
        return project;
    }

    private static List<Map<String, Object>> resolveSharedProjects(Session session, ActionNext action) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        ActionSet actionSet = action.getActionSet();
        if (actionSet == null || actionSet.getActionSetType() != ActionSetType.SHARED) {
            return result;
        }
        Query query = session.createQuery(
                "from ActionNext an where an.actionSet.actionSetId = :actionSetId and an.actionNextId <> :selfId");
        query.setInteger("actionSetId", actionSet.getActionSetId());
        query.setInteger("selfId", action.getActionNextId());
        @SuppressWarnings("unchecked")
        List<ActionNext> siblings = query.list();
        Set<Integer> seenProjectIds = new HashSet<Integer>();
        for (ActionNext sibling : siblings) {
            if (!seenProjectIds.add(Integer.valueOf(sibling.getProjectId()))) {
                continue;
            }
            Project project = (Project) session.get(Project.class, sibling.getProjectId());
            if (project == null) {
                continue;
            }
            Map<String, Object> projectMap = new LinkedHashMap<String, Object>();
            projectMap.put("projectId", project.getProjectId());
            projectMap.put("projectName", project.getProjectName());
            projectMap.put("projectHandle", project.getProjectHandle());
            projectMap.put("workspaceId", project.getWorkspaceId());
            result.add(projectMap);
        }
        return result;
    }

    /**
     * Some ActionNext date fields (nextActionDate, nextDeadlineDate,
     * nextTargetDate) are mapped Hibernate type="date" and come back as
     * java.sql.Date, whose toInstant() always throws
     * UnsupportedOperationException. Building the Instant from the epoch
     * millis instead works for java.util.Date, java.sql.Date, and
     * java.sql.Timestamp alike.
     */
    public static String toIso(Date date) {
        return date == null ? null : Instant.ofEpochMilli(date.getTime()).toString();
    }

    private static String toDateOnly(Date date) {
        // These fields are Hibernate DATE columns, not instants in the viewer's time zone.
        return date == null ? null : new java.sql.Date(date.getTime()).toLocalDate().toString();
    }

    /**
     * "Now", truncated to whole-second precision. MySQL's datetime columns
     * (last_modified_date, next_change_date) silently drop fractional
     * seconds on write; a version-marker value that's echoed straight back
     * in the same response -- without a DB round-trip -- must already match
     * what a later read will show, or a client replaying that exact value
     * as "asOf" gets spuriously rejected as stale (found in live testing:
     * McpProjectLanguageService's response carried milliseconds the
     * persisted column never kept).
     */
    public static Date truncatedNow() {
        return new Date((System.currentTimeMillis() / 1000L) * 1000L);
    }
}
