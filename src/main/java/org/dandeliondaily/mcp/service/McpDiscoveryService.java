package org.dandeliondaily.mcp.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.dandeliondaily.shared.service.LinkedMeetingService;
import org.hibernate.Query;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpToolException;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.model.WebUser;
import org.openimmunizationsoftware.pt.model.Workspace;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

public class McpDiscoveryService {
    // Action sets can group unrelated actions. Never attribute their work timestamps to one action unless unique.
    private static final String WORK_DATE = "(select max(t.actionDate) from ActionTaken t "
            + "where t.actionSet = a.actionSet and t.projectId = a.projectId and t.workspaceId = a.workspaceId "
            + "and (select count(s.actionNextId) from ActionNext s where s.actionSet = a.actionSet) = 1)";

    public Map<String, Object> listProjects(Session session, int workspaceId, WebUser user, JsonNode input) {
        JsonNode args = arguments(input, "nameContains", "scope", "status", "billCode", "tag", "limit", "offset",
                "workspaceId", "externalSourceKey");
        int limit = integer(args, "limit", 100, 1, 200);
        int offset = integer(args, "offset", 0, 0, Integer.MAX_VALUE - 200);
        String scope = optional(args, "scope", "ALL");
        if (!Arrays.asList("ALL", "PRIVATE", "SHARED").contains(scope)) {
            throw invalid("scope must be ALL, PRIVATE, or SHARED.");
        }
        String status = optional(args, "status", "OPEN");
        if (!Arrays.asList("OPEN", "ALL", "Active", "Paused", "Complete", "Closed").contains(status)) {
            throw invalid("status must be OPEN, ALL, Active, Paused, Complete, or Closed.");
        }
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("workspaceIds", McpProjectAccess.readableWorkspaces(session, workspaceId, user));
        StringBuilder hql = new StringBuilder("select p, w from Project p, Workspace w "
                + "where p.workspaceId = w.workspaceId and p.workspaceId in (:workspaceIds)");
        if (args.has("workspaceId")) {
            hql.append(" and p.workspaceId = :selectedWorkspaceId");
            params.put("selectedWorkspaceId", integer(args, "workspaceId", 0, 1, Integer.MAX_VALUE));
        }
        if (args.has("externalSourceKey")) {
            hql.append(" and p.externalSourceKey = :externalSourceKey");
            params.put("externalSourceKey", text(args, "externalSourceKey", 100, true));
        }
        if (!"ALL".equals(scope)) {
            hql.append(" and w.workspaceType = :workspaceType");
            params.put("workspaceType", "PRIVATE".equals(scope) ? Workspace.TYPE_PRIVATE : Workspace.TYPE_PATCH);
        }
        if ("OPEN".equals(status)) {
            hql.append(" and (p.projectStatus is null or p.projectStatus not in ('Closed', 'Complete'))");
        } else if (!"ALL".equals(status)) {
            hql.append(" and p.projectStatus = :status");
            params.put("status", status);
        }
        if (args.has("nameContains")) {
            hql.append(" and lower(p.projectName) like :name escape '!'");
            params.put("name", contains(text(args, "nameContains", 100, true)));
        }
        if (args.has("billCode")) {
            hql.append(" and p.billCode = :billCode");
            params.put("billCode", text(args, "billCode", 15, true));
        }
        if (args.has("tag")) {
            hql.append(" and exists (select tm.projectTagMapId from ProjectTagMap tm, ProjectTag tag "
                    + "where tm.projectId = p.projectId and tm.projectTagId = tag.projectTagId "
                    + "and tag.workspaceId = p.workspaceId and lower(tag.tagName) = :tag)");
            params.put("tag", text(args, "tag", 100, true).toLowerCase(Locale.ROOT));
        }
        Query query = bind(session.createQuery(hql + " order by lower(p.projectName), p.projectId"), params);
        query.setFirstResult(offset).setMaxResults(limit + 1);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.list();
        List<Map<String, Object>> projects = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < Math.min(limit, rows.size()); i++) {
            Project project = (Project) rows.get(i)[0];
            Workspace workspace = (Workspace) rows.get(i)[1];
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("projectId", project.getProjectId());
            map.put("projectName", project.getProjectName());
            map.put("projectIcon", project.getProjectIcon());
            map.put("workspaceId", workspace.getWorkspaceId());
            map.put("workspaceName", workspace.getWorkspaceName());
            map.put("scope", Workspace.TYPE_PATCH.equals(workspace.getWorkspaceType()) ? "SHARED" : "PRIVATE");
            map.put("ownerWebUserId", project.getWebUser() == null ? null : project.getWebUser().getWebUserId());
            map.put("status", project.getProjectStatus());
            map.put("billCode", project.getBillCode());
            map.put("externalSourceKey", project.getExternalSourceKey());
            map.put("externalProjectId", project.getExternalProjectId());
            map.put("externalManaged", project.isExternalManaged());
            map.put("lastModifiedAt", McpActionContextSupport.toIso(project.getLastModifiedDate()));
            map.put("lastActivityAt", lastActivity(session, project));
            projects.add(map);
        }
        return page("projects", projects, rows.size() > limit, offset, limit);
    }

    private String lastActivity(Session session, Project project) {
        Date latest = project.getLastModifiedDate();
        for (String hql : Arrays.asList("select max(a.nextChangeDate) from ActionNext a",
                "select max(a.actionDate) from ActionTaken a")) {
            Date date = (Date) session.createQuery(hql + " where a.projectId = :projectId")
                    .setInteger("projectId", project.getProjectId()).uniqueResult();
            if (date != null && (latest == null || date.after(latest))) { latest = date; }
        }
        return McpActionContextSupport.toIso(latest);
    }

    public Map<String, Object> searchActions(Session session, int workspaceId, WebUser user, JsonNode input) {
        JsonNode args = arguments(input, "text", "includeNotes", "startDate", "endDate", "actionTypes",
                "statuses", "projectIds", "hasLinkedMeeting", "limit", "offset");
        String search = text(args, "text", 200, true);
        LocalDate start = date(args, "startDate");
        LocalDate end = date(args, "endDate");
        if (end.isBefore(start) || ChronoUnit.DAYS.between(start, end) >= 366) {
            throw invalid("Date range must be ordered and contain at most 366 days.");
        }
        int limit = integer(args, "limit", 100, 1, 200);
        int offset = integer(args, "offset", 0, 0, Integer.MAX_VALUE - 200);
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("workspaceId", workspaceId);
        params.put("text", contains(search));
        params.put("scheduledStart", java.sql.Date.valueOf(start));
        params.put("scheduledEnd", java.sql.Date.valueOf(end.plusDays(1)));
        params.put("workStart", user.toDate(start));
        params.put("workEnd", user.toDate(end.plusDays(1)));
        StringBuilder hql = new StringBuilder("select a, " + WORK_DATE + " from ActionNext a "
                + "where a.workspaceId = :workspaceId and a.project.workspaceId = :workspaceId "
                + "and (lower(a.nextDescription) like :text escape '!'");
        if (bool(args, "includeNotes", false)) {
            hql.append(" or lower(a.nextNotes) like :text escape '!'");
        }
        hql.append(") and ((a.nextActionDate >= :scheduledStart and a.nextActionDate < :scheduledEnd) "
                + "or (a.nextActionStatusString in ('C','X') and " + WORK_DATE + " >= :workStart and "
                + WORK_DATE + " < :workEnd))");
        if (args.has("actionTypes")) {
            List<String> types = strings(args, "actionTypes");
            for (String type : types) {
                if (!Arrays.asList("WILL", "WILL_CONTACT", "WILL_MEET", "REVIEW", "DOCUMENT",
                        "WILL_FOLLOW_UP", "MIGHT", "WOULD_LIKE_TO", "COMMITTED_TO", "GOAL", "WAITING",
                        "OVERDUE_TO").contains(type)) { throw invalid("Unknown action type: " + type); }
            }
            hql.append(" and a.nextActionType in (:actionTypes)");
            params.put("actionTypes", types);
        }
        List<String> statuses = args.has("statuses") ? strings(args, "statuses")
                : Arrays.asList("OPEN", "COMPLETED", "CANCELLED");
        List<String> codes = new ArrayList<String>();
        for (String status : statuses) {
            if ("OPEN".equals(status)) { codes.addAll(Arrays.asList("P", "R")); }
            else if ("COMPLETED".equals(status)) { codes.add("C"); }
            else if ("CANCELLED".equals(status)) { codes.add("X"); }
            else { throw invalid("statuses must contain OPEN, COMPLETED, or CANCELLED."); }
        }
        params.put("statuses", codes);
        hql.append(" and a.nextActionStatusString in (:statuses)");
        if (args.has("projectIds")) {
            JsonNode ids = args.get("projectIds");
            if (!ids.isArray() || ids.size() == 0 || ids.size() > 100) {
                throw invalid("projectIds must be a nonempty array of at most 100 IDs.");
            }
            List<Integer> projectIds = new ArrayList<Integer>();
            for (JsonNode id : ids) {
                if (!id.isIntegralNumber() || !id.canConvertToInt() || id.intValue() <= 0) {
                    throw invalid("projectIds must contain positive integer IDs.");
                }
                projectIds.add(id.intValue());
            }
            params.put("projectIds", projectIds);
            hql.append(" and a.projectId in (:projectIds)");
        }
        if (args.has("hasLinkedMeeting")) {
            hql.append(bool(args, "hasLinkedMeeting", false) ? " and exists (" : " and not exists (");
            hql.append("select m.linkedMeetingId from LinkedMeeting m where m.actionNextId = a.actionNextId "
                    + "and m.workspaceId = :workspaceId)");
        }
        hql.append(" order by a.actionNextId");
        Query query = bind(session.createQuery(hql.toString()), params);
        query.setFirstResult(offset).setMaxResults(limit + 1);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.list();
        List<Map<String, Object>> actions = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < Math.min(limit, rows.size()); i++) {
            ActionNext action = (ActionNext) rows.get(i)[0];
            Date workDate = (Date) rows.get(i)[1];
            Map<String, Object> map = McpActionContextSupport.toActionMap(session, action, true, user);
            map.remove("sharedWithProjects");
            boolean closed = "C".equals(action.getNextActionStatusString()) || "X".equals(action.getNextActionStatusString());
            map.put("completionDate", "C".equals(action.getNextActionStatusString()) ? McpActionContextSupport.toIso(workDate) : null);
            map.put("recordedWorkDate", closed ? McpActionContextSupport.toIso(workDate) : null);
            map.put("completionDateKnown", "C".equals(action.getNextActionStatusString()) && workDate != null);
            map.put("completionDateUnknownReason", !"C".equals(action.getNextActionStatusString())
                    ? "NOT_COMPLETED" : workDate == null ? "NO_UNAMBIGUOUS_WORK_RECORD" : null);
            actions.add(map);
        }
        return page("actions", actions, rows.size() > limit, offset, limit);
    }

    static JsonNode arguments(JsonNode input, String... fields) {
        JsonNode args = input == null ? JsonNodeFactory.instance.objectNode() : input;
        LinkedMeetingService.requireObject(args, "arguments");
        LinkedMeetingService.rejectUnknown(args, new HashSet<String>(Arrays.asList(fields)));
        return args;
    }

    static String text(JsonNode args, String field, int max, boolean required) {
        return LinkedMeetingService.text(args, field, max, required);
    }

    static int integer(JsonNode args, String field, int defaultValue, int min, int max) {
        if (!args.has(field)) { return defaultValue; }
        JsonNode node = args.get(field);
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < min || node.intValue() > max) {
            throw invalid(field + " must be an integer between " + min + " and " + max + ".");
        }
        return node.intValue();
    }

    static boolean bool(JsonNode args, String field, boolean defaultValue) {
        if (!args.has(field)) { return defaultValue; }
        if (!args.get(field).isBoolean()) { throw invalid(field + " must be boolean."); }
        return args.get(field).booleanValue();
    }

    private static String optional(JsonNode args, String field, String defaultValue) {
        return args.has(field) ? text(args, field, 30, true) : defaultValue;
    }

    private static LocalDate date(JsonNode args, String field) {
        try { return LocalDate.parse(text(args, field, 10, true)); }
        catch (DateTimeParseException e) { throw invalid(field + " must be yyyy-MM-dd."); }
    }

    private static List<String> strings(JsonNode args, String field) {
        JsonNode node = args.get(field);
        if (!node.isArray() || node.size() == 0 || node.size() > 100) {
            throw invalid(field + " must be a nonempty array (maximum 100).");
        }
        List<String> values = new ArrayList<String>();
        for (JsonNode value : node) {
            if (!value.isTextual()) { throw invalid(field + " must contain strings."); }
            values.add(value.textValue());
        }
        return values;
    }

    private static String contains(String value) {
        return "%" + value.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    static Query bind(Query query, Map<String, Object> params) {
        for (Map.Entry<String, Object> param : params.entrySet()) {
            if (param.getValue() instanceof java.util.Collection<?>) {
                query.setParameterList(param.getKey(), (java.util.Collection<?>) param.getValue());
            } else { query.setParameter(param.getKey(), param.getValue()); }
        }
        return query;
    }

    private static Map<String, Object> page(String field, Object items, boolean more, int offset, int limit) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put(field, items);
        result.put("hasMore", more);
        result.put("nextOffset", more ? offset + limit : null);
        return result;
    }

    static McpToolException invalid(String message) { return new McpToolException("invalid_arguments", message); }
}
