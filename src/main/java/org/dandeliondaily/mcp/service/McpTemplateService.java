package org.dandeliondaily.mcp.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import org.dandeliondaily.planahead.service.TemplateEditService;
import org.dandeliondaily.planahead.service.TemplateEditService.TemplateEditException;
import org.dandeliondaily.planahead.service.TemplateSyncCounts;
import org.hibernate.Query;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpToolException;
import org.openimmunizationsoftware.pt.manager.TrackerKeysManager;
import org.openimmunizationsoftware.pt.model.ActionAgentConfig;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ActionNextTemplateConfig;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.model.ProjectNextActionStatus;
import org.openimmunizationsoftware.pt.model.TemplateType;
import org.openimmunizationsoftware.pt.model.WebUser;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * MCP's list_templates and update_template. Writes go through {@link TemplateEditService}, the
 * same service the Templates page uses, so a template changed here cascades to its generated
 * actions exactly as it does there. Template roots and their generated actions stay read-only in
 * apply_changes; this is the way to change them.
 */
public class McpTemplateService {

    public static final String OP_CREATE = "create";
    public static final String OP_UPDATE = "update";
    public static final String OP_DEACTIVATE = "deactivate";
    public static final String OP_REACTIVATE = "reactivate";

    private static final int UPCOMING_LIMIT = 5;

    private final TemplateEditService editService = new TemplateEditService();

    // =========================================================================
    // list_templates
    // =========================================================================

    @SuppressWarnings("unchecked")
    public Map<String, Object> list(Session session, int workspaceId, WebUser webUser, String mode,
            Integer projectId, boolean includeInactive) {
        String normalizedMode = mode == null ? "ALL" : mode.trim().toUpperCase();
        if (!"ALL".equals(normalizedMode) && !TemplateEditService.MODE_WORK.equals(normalizedMode)
                && !TemplateEditService.MODE_PERSONAL.equals(normalizedMode)) {
            throw new McpToolException("invalid_arguments", "mode must be WORK, PERSONAL or ALL.");
        }
        StringBuilder hql = new StringBuilder("from ActionNext an where an.workspaceId = :workspaceId "
                + "and (an.contactId = :contactId or an.nextContactId = :contactId) "
                + "and an.templateTypeString is not null and an.templateTypeString <> '' "
                + "and (an.templateActionNextId is null or an.templateActionNextId = 0)");
        if (!"ALL".equals(normalizedMode)) {
            hql.append(" and an.billable = :billable");
        }
        if (projectId != null) {
            hql.append(" and an.projectId = :projectId");
        }
        if (!includeInactive) {
            hql.append(" and an.nextActionStatusString <> :cancelled");
        }
        hql.append(" order by an.billable desc, an.nextDescription");
        Query query = session.createQuery(hql.toString());
        query.setParameter("workspaceId", Integer.valueOf(workspaceId));
        query.setParameter("contactId", Integer.valueOf(webUser.getContactId()));
        if (!"ALL".equals(normalizedMode)) {
            query.setParameter("billable", Boolean.valueOf(TemplateEditService.MODE_WORK.equals(normalizedMode)));
        }
        if (projectId != null) {
            query.setParameter("projectId", projectId);
        }
        if (!includeInactive) {
            query.setParameter("cancelled", ProjectNextActionStatus.CANCELLED.getId());
        }
        LocalDate today = today(session, webUser);
        List<Map<String, Object>> templates = new ArrayList<Map<String, Object>>();
        for (ActionNext template : (List<ActionNext>) query.list()) {
            templates.add(templateMap(session, template, today));
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("today", today.toString());
        result.put("advanceDays", Integer.valueOf(TemplateEditService.advanceDays(session)));
        result.put("templates", templates);
        return result;
    }

    // =========================================================================
    // update_template
    // =========================================================================

    public Map<String, Object> update(Session session, int workspaceId, WebUser webUser, JsonNode args) {
        String operation = text(args, "operation");
        if (operation == null) {
            throw new McpToolException("invalid_arguments", "\"operation\" is required.");
        }
        operation = operation.trim().toLowerCase();
        TemplateEditService.Owner owner = new TemplateEditService.Owner(workspaceId, webUser.getContactId(),
                webUser.getProjectContact(), today(session, webUser));
        try {
            if (OP_CREATE.equals(operation) || OP_UPDATE.equals(operation)) {
                return save(session, owner, operation, args);
            }
            int templateId = requireTemplateId(args);
            if (OP_DEACTIVATE.equals(operation)) {
                ActionNext template = editService.requireTemplate(session, owner, templateId);
                checkAsOf(args, template);
                TemplateSyncCounts counts = editService.delete(session, owner, templateId);
                session.flush();
                return response(session, owner, operation, templateId, counts, "Template deactivated");
            }
            if (OP_REACTIVATE.equals(operation)) {
                ActionNext template = editService.requireTemplate(session, owner, templateId);
                checkAsOf(args, template);
                TemplateEditService.Saved saved = editService.reactivate(session, owner, templateId);
                session.flush();
                TemplateSyncCounts counts = editService.sync(session, owner, saved);
                session.flush();
                return response(session, owner, operation, templateId, counts, "Template reactivated");
            }
        } catch (TemplateEditException invalid) {
            throw new McpToolException(invalid.getCode(), invalid.getMessage());
        }
        throw new McpToolException("invalid_arguments",
                "operation must be create, update, deactivate or reactivate.");
    }

    private Map<String, Object> save(Session session, TemplateEditService.Owner owner, String operation,
            JsonNode args) {
        TemplateEditService.Edit edit = new TemplateEditService.Edit();
        boolean creating = OP_CREATE.equals(operation);
        Boolean personal;
        ActionNext existing = null;
        if (creating) {
            if (args.has("templateId")) {
                throw new McpToolException("invalid_arguments", "create makes a new template; leave out templateId.");
            }
            edit.mode = text(args, "mode");
            if (edit.mode == null) {
                throw new McpToolException("invalid_arguments", "create requires \"mode\": WORK or PERSONAL.");
            }
            personal = Boolean.valueOf(TemplateEditService.MODE_PERSONAL.equalsIgnoreCase(edit.mode.trim()));
            if (!args.hasNonNull("description")) {
                throw new McpToolException("invalid_arguments", "create requires \"description\".");
            }
            if (!args.hasNonNull("templateType")) {
                throw new McpToolException("invalid_arguments", "create requires \"templateType\".");
            }
        } else {
            edit.templateId = Integer.valueOf(requireTemplateId(args));
            if (args.has("mode")) {
                throw new McpToolException("invalid_arguments",
                        "A template's mode can't be changed; create a new template in the other mode instead.");
            }
            existing = editService.requireTemplate(session, owner, edit.templateId.intValue());
            if (ProjectNextActionStatus.CANCELLED.equals(existing.getNextActionStatus())) {
                throw new McpToolException("invalid_arguments", "Template #" + edit.templateId
                        + " is deactivated; reactivate it first.");
            }
            checkAsOf(args, existing);
            personal = Boolean.valueOf(!existing.isBillable());
        }
        if (personal.booleanValue() && args.has("nextActionType")) {
            throw new McpToolException("invalid_arguments",
                    "Personal templates are always WILL actions; set timeSlot instead of nextActionType.");
        }
        if (!personal.booleanValue() && args.has("timeSlot")) {
            throw new McpToolException("invalid_arguments",
                    "timeSlot is for personal templates; work templates use nextActionType.");
        }

        edit.description = clearable(args, "description");
        String templateType = text(args, "templateType");
        if (templateType != null) {
            edit.templateType = parseTemplateType(templateType);
        }
        edit.nextActionType = text(args, "nextActionType");
        edit.timeSlot = text(args, "timeSlot");
        if (args.has("estimateMinutes")) {
            edit.estimateMinutes = Integer.valueOf(intValue(args, "estimateMinutes", 0));
        }
        edit.missedActionBehavior = text(args, "missedActionBehavior");
        if (args.has("autoGenerate")) {
            if (!args.get("autoGenerate").isBoolean()) {
                throw new McpToolException("invalid_arguments", "autoGenerate must be true or false.");
            }
            edit.autoGenerate = Boolean.valueOf(args.get("autoGenerate").asBoolean());
        }
        edit.scheduleDaysOfWeek = clearable(args, "scheduleDaysOfWeek");
        edit.scheduleDaysOfMonth = clearable(args, "scheduleDaysOfMonth");
        edit.scheduleDaysOfQuarter = clearable(args, "scheduleDaysOfQuarter");
        edit.scheduleDaysOfYear = clearable(args, "scheduleDaysOfYear");
        edit.linkUrl = clearable(args, "linkUrl");
        edit.notes = clearable(args, "notes");
        if (args.has("projectId")) {
            edit.projectId = Integer.valueOf(intValue(args, "projectId", 0));
        }
        if (args.has("agent")) {
            readAgent(session, args.get("agent"), existing, edit);
        }

        TemplateEditService.Saved saved = editService.save(session, owner, edit);
        session.flush();
        TemplateSyncCounts counts = editService.sync(session, owner, saved);
        session.flush();
        String message = creating ? "Template created" : "Template saved";
        if (saved.isPerTargetAgent()) {
            message += ". Its agent preps come from each occurrence of the other template, so it has no "
                    + "schedule of its own and its open preps were left as they are";
        }
        return response(session, owner, operation, saved.getTemplateId(), counts, message);
    }

    /**
     * "agent": null hands the template back to a person. An object changes only the keys it has;
     * the rest keep the template's current agent settings.
     */
    private void readAgent(Session session, JsonNode agent, ActionNext existing, TemplateEditService.Edit edit) {
        edit.agentSent = true;
        ActionAgentConfig config = existing == null ? null
                : (ActionAgentConfig) session.get(ActionAgentConfig.class, Integer.valueOf(existing.getActionNextId()));
        edit.agent = existing == null ? null : existing.getPerformedByAgent();
        edit.agentHandoffMode = config == null ? null : config.getHandoffMode();
        edit.agentHandoffTemplateId = config == null ? null : config.getHandoffTemplateId();
        edit.agentHandoffProjectId = config == null ? null : config.getHandoffProjectId();
        edit.agentWorkloadKey = config == null ? null : config.getWorkloadKey();
        if (agent == null || agent.isNull()) {
            edit.agent = null;
            return;
        }
        if (!agent.isObject()) {
            throw new McpToolException("invalid_arguments", "agent must be an object or null.");
        }
        if (agent.has("performedBy")) {
            edit.agent = agent.get("performedBy").isNull() ? null : agent.get("performedBy").asText();
        }
        if (agent.has("handoffMode")) {
            edit.agentHandoffMode = agent.get("handoffMode").isNull() ? null : agent.get("handoffMode").asText();
        }
        if (agent.has("handoffTemplateId")) {
            edit.agentHandoffTemplateId = positiveOrNull(agent, "handoffTemplateId");
        }
        if (agent.has("handoffProjectId")) {
            edit.agentHandoffProjectId = positiveOrNull(agent, "handoffProjectId");
        }
        if (agent.has("workloadKey")) {
            edit.agentWorkloadKey = agent.get("workloadKey").isNull() ? null : agent.get("workloadKey").asText();
        }
        if (edit.agent == null || edit.agent.trim().length() == 0) {
            edit.agentHandoffMode = null;
            edit.agentHandoffTemplateId = null;
            edit.agentHandoffProjectId = null;
            edit.agentWorkloadKey = null;
        }
    }

    private Map<String, Object> response(Session session, TemplateEditService.Owner owner, String operation,
            int templateId, TemplateSyncCounts counts, String message) {
        ActionNext template = (ActionNext) session.get(ActionNext.class, Integer.valueOf(templateId));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("operation", operation);
        String summary = counts.summary();
        result.put("message", message + (summary.length() > 0 ? ": " + summary : "") + ".");
        result.put("cascade", counts.toMap());
        result.put("template", templateMap(session, template, owner.getToday()));
        return result;
    }

    // =========================================================================
    // Template view
    // =========================================================================

    Map<String, Object> templateMap(Session session, ActionNext template, LocalDate today) {
        ActionNextTemplateConfig config = (ActionNextTemplateConfig) session.get(ActionNextTemplateConfig.class,
                Integer.valueOf(template.getActionNextId()));
        ActionAgentConfig agentConfig = (ActionAgentConfig) session.get(ActionAgentConfig.class,
                Integer.valueOf(template.getActionNextId()));
        boolean personal = !template.isBillable();
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("templateId", Integer.valueOf(template.getActionNextId()));
        map.put("asOf", McpActionContextSupport.toIso(template.getNextChangeDate()));
        map.put("status", ProjectNextActionStatus.CANCELLED.equals(template.getNextActionStatus()) ? "DEACTIVATED"
                : "ACTIVE");
        map.put("mode", personal ? TemplateEditService.MODE_PERSONAL : TemplateEditService.MODE_WORK);
        map.put("description", template.getNextDescription());
        Project project = template.getProject();
        if (project == null && template.getProjectId() > 0) {
            project = (Project) session.get(Project.class, Integer.valueOf(template.getProjectId()));
        }
        if (project != null) {
            Map<String, Object> projectMap = new LinkedHashMap<String, Object>();
            projectMap.put("projectId", Integer.valueOf(project.getProjectId()));
            projectMap.put("projectName", project.getProjectName());
            map.put("project", projectMap);
        } else {
            map.put("project", null);
        }
        map.put("templateType", template.getTemplateType() == null ? null : template.getTemplateType().name());
        map.put("scheduleDaysOfWeek", config == null ? null : config.getScheduleDaysOfWeek());
        map.put("scheduleDaysOfMonth", config == null ? null : config.getScheduleDaysOfMonth());
        map.put("scheduleDaysOfQuarter", config == null ? null : config.getScheduleDaysOfQuarter());
        map.put("scheduleDaysOfYear", config == null ? null : config.getScheduleDaysOfYear());
        map.put("autoGenerate", Boolean.valueOf(config == null || config.isAutoGenerate()));
        map.put("missedActionBehavior", config == null || config.getMissedActionBehavior() == null
                ? TemplateEditService.MISSED_AUTO_CANCEL : config.getMissedActionBehavior());
        if (personal) {
            map.put("timeSlot", template.getTimeSlot() == null ? null : template.getTimeSlot().getId());
        } else {
            map.put("nextActionType", template.getNextActionType());
        }
        map.put("estimateMinutes", template.getNextTimeEstimate());
        map.put("linkUrl", template.getLinkUrl());
        map.put("notes", template.getNextNotes());
        if (template.getPerformedByAgent() != null && template.getPerformedByAgent().length() > 0) {
            Map<String, Object> agent = new LinkedHashMap<String, Object>();
            agent.put("performedBy", template.getPerformedByAgent());
            agent.put("handoffMode", agentConfig == null ? null : agentConfig.getHandoffMode());
            agent.put("handoffTemplateId", agentConfig == null ? null : agentConfig.getHandoffTemplateId());
            agent.put("handoffProjectId", agentConfig == null ? null : agentConfig.getHandoffProjectId());
            agent.put("workloadKey", agentConfig == null ? null : agentConfig.getWorkloadKey());
            map.put("agent", agent);
        } else {
            map.put("agent", null);
        }
        map.put("upcoming", upcoming(session, template, today));
        return map;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> upcoming(Session session, ActionNext template, LocalDate today) {
        Query query = session.createQuery("from ActionNext an where an.templateActionNextId = :templateId "
                + "and an.workspaceId = :workspaceId "
                + "and an.nextActionDate is not null and an.nextActionDate >= :today "
                + "and an.nextActionStatusString = :ready order by an.nextActionDate");
        query.setParameter("templateId", Integer.valueOf(template.getActionNextId()));
        query.setParameter("workspaceId", template.getWorkspaceId());
        query.setParameter("today", java.sql.Date.valueOf(today));
        query.setParameter("ready", ProjectNextActionStatus.READY.getId());
        query.setMaxResults(UPCOMING_LIMIT);
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (ActionNext instance : (List<ActionNext>) query.list()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("actionNextId", Integer.valueOf(instance.getActionNextId()));
            item.put("date", new java.sql.Date(instance.getNextActionDate().getTime()).toLocalDate().toString());
            list.add(item);
        }
        return list;
    }

    // =========================================================================
    // Argument helpers
    // =========================================================================

    /** The user's own "today", as the Templates page computes it from their time zone setting. */
    static LocalDate today(Session session, WebUser webUser) {
        String zone = TrackerKeysManager.getKeyValue(TrackerKeysManager.KEY_TIME_ZONE, WebUser.AMERICA_DENVER,
                webUser, session);
        return LocalDate.now(TimeZone.getTimeZone(zone).toZoneId());
    }

    private static void checkAsOf(JsonNode args, ActionNext template) {
        if (!args.hasNonNull("asOf")) {
            return;
        }
        String asOf = args.get("asOf").asText();
        Date expected;
        try {
            expected = Date.from(Instant.parse(asOf));
        } catch (DateTimeParseException e) {
            throw new McpToolException("invalid_arguments", "asOf must be the template's asOf from list_templates.");
        }
        Date current = template.getNextChangeDate();
        if (current == null || current.getTime() / 1000L != expected.getTime() / 1000L) {
            throw new McpToolException("stale", "Template #" + template.getActionNextId()
                    + " has changed since it was last read (current asOf: "
                    + McpActionContextSupport.toIso(current) + ").");
        }
    }

    static TemplateType parseTemplateType(String value) {
        String v = value.trim().toUpperCase();
        for (TemplateType type : TemplateType.values()) {
            if (type.name().equals(v) || type.getId().equals(v)) {
                return type;
            }
        }
        throw new McpToolException("invalid_arguments",
                "templateType must be DAILY, WEEKLY, MONTHLY, QUARTERLY or YEARLY.");
    }

    private static int requireTemplateId(JsonNode args) {
        if (!args.hasNonNull("templateId") || !args.get("templateId").canConvertToInt()
                || args.get("templateId").asInt() <= 0) {
            throw new McpToolException("invalid_arguments", "\"templateId\" is required.");
        }
        return args.get("templateId").asInt();
    }

    private static String text(JsonNode args, String field) {
        return args.hasNonNull(field) ? args.get(field).asText() : null;
    }

    /** Absent: unchanged (null). Present as null: cleared (""). */
    private static String clearable(JsonNode args, String field) {
        if (!args.has(field)) {
            return null;
        }
        return args.get(field).isNull() ? "" : args.get(field).asText();
    }

    private static int intValue(JsonNode args, String field, int nullValue) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull()) {
            return nullValue;
        }
        if (!node.canConvertToInt() || !node.isIntegralNumber()) {
            throw new McpToolException("invalid_arguments", field + " must be a whole number.");
        }
        return node.asInt();
    }

    private static Integer positiveOrNull(JsonNode node, String field) {
        int value = intValue(node, field, 0);
        if (value < 0) {
            throw new McpToolException("invalid_arguments", "agent." + field + " must be a positive number.");
        }
        return value == 0 ? null : Integer.valueOf(value);
    }
}
