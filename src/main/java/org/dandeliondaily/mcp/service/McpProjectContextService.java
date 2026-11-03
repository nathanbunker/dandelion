package org.dandeliondaily.mcp.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.dandeliondaily.projectainote.service.ProjectAiNoteService;
import org.hibernate.Query;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.doa.ProjectIssueDao;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ActionTaken;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.model.ProjectAiNote;
import org.openimmunizationsoftware.pt.model.ProjectIssue;
import org.openimmunizationsoftware.pt.model.ProjectNarrative;
import org.openimmunizationsoftware.pt.model.ProjectNextActionStatus;
import org.openimmunizationsoftware.pt.model.WebUser;

/**
 * Structured (JSON-friendly) equivalent of
 * org.dandeliondaily.dashboard.service.ProjectDashboardAiContextService,
 * which renders the same underlying data to a text blob for the in-app AI
 * chat prompt. Kept separate rather than refactoring that class, so the
 * existing chat feature is not put at risk by this change.
 */
public class McpProjectContextService {

    private static final int MAX_RECENT_ACTION_TAKEN = 15;
    private static final int MAX_OPEN_ACTIONS = 20;
    private static final int MAX_OPEN_ISSUES = 20;
    private static final int MAX_RECENT_NARRATIVES = 20;

    private final ProjectAiNoteService aiNoteService = new ProjectAiNoteService();

    public Map<String, Object> getProjectContext(Session session, int workspaceId, int projectId,
            WebUser webUser) {
        Project project = McpProjectAccess.requireReadable(session, workspaceId, webUser, projectId);
        boolean sharedRead = project.getWorkspaceId().intValue() != workspaceId;

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("projectId", project.getProjectId());
        result.put("projectName", project.getProjectName());
        result.put("workspaceId", project.getWorkspaceId());
        result.put("externalSourceKey", project.getExternalSourceKey());
        result.put("externalProjectId", project.getExternalProjectId());
        result.put("externalManaged", project.isExternalManaged());
        result.put("projectHandle", project.getProjectHandle());
        result.put("projectStatus", project.getProjectStatus());
        result.put("description", project.getDescription());
        result.put("currentFocus", project.getCurrentFocusText());
        result.put("outcome", project.getOutcomeText());
        result.put("successCriteria", project.getSuccessCriteriaText());
        result.put("lastModifiedAt", McpActionContextSupport.toIso(project.getLastModifiedDate()));
        result.put("priorityLevel", project.getPriorityLevel());
        result.put("billCode", project.getBillCode());
        result.put("tags", loadTagNames(session, projectId));
        result.put("recentActionTaken", loadRecentActionTakenList(session, projectId));
        result.put("openActions", loadOpenActionsList(session, projectId, webUser, !sharedRead));
        result.put("activeTemplates", loadActiveTemplatesList(session, projectId));
        result.put("openIssues", loadOpenIssuesList(session, project));
        result.put("recentNarratives", loadRecentNarrativesList(session, projectId));
        result.put("aiThoughts", loadAiThoughtsList(session, projectId));
        return result;
    }

    private List<Map<String, Object>> loadAiThoughtsList(Session session, int projectId) {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (ProjectAiNote note : aiNoteService.list(session, projectId)) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("noteId", note.getNoteId());
            item.put("noteText", note.getNoteText());
            item.put("source", note.getSource());
            item.put("createdAt", McpActionContextSupport.toIso(note.getCreatedAt()));
            item.put("updatedAt", McpActionContextSupport.toIso(note.getUpdatedAt()));
            list.add(item);
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private List<String> loadTagNames(Session session, int projectId) {
        Query query = session.createQuery(
                "select pt.tagName from ProjectTagMap ptm, ProjectTag pt "
                        + "where ptm.projectId = :projectId and pt.projectTagId = ptm.projectTagId "
                        + "order by pt.sortOrder, pt.tagName");
        query.setParameter("projectId", projectId);
        return query.list();
    }

    private List<Map<String, Object>> loadRecentActionTakenList(Session session, int projectId) {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (ActionTaken taken : loadRecentActionTaken(session, projectId)) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("date", McpActionContextSupport.toIso(taken.getActionDate()));
            item.put("description", taken.getActionDescription());
            if (taken.getActionNote() != null) {
                item.put("outcomeNote", taken.getActionNote());
            }
            list.add(item);
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private List<ActionTaken> loadRecentActionTaken(Session session, int projectId) {
        Query query = session.createQuery("from ActionTaken where projectId = :projectId order by actionDate desc");
        query.setParameter("projectId", projectId);
        query.setMaxResults(MAX_RECENT_ACTION_TAKEN);
        return query.list();
    }

    private List<Map<String, Object>> loadOpenActionsList(Session session, int projectId, WebUser webUser,
            boolean includePrivateContext) {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (ActionNext action : loadOpenActions(session, projectId)) {
            list.add(McpActionContextSupport.toActionMap(session, action, false, webUser, includePrivateContext));
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private List<ActionNext> loadOpenActions(Session session, int projectId) {
        Query query = session.createQuery(
                "from ActionNext an where an.projectId = :projectId "
                        + "and (an.nextActionStatusString = :readyStatus or an.nextActionStatusString = :proposedStatus) "
                        + "order by an.nextActionDate asc, an.priorityLevel desc, an.nextChangeDate desc");
        query.setParameter("projectId", projectId);
        query.setParameter("readyStatus", ProjectNextActionStatus.READY.getId());
        query.setParameter("proposedStatus", ProjectNextActionStatus.PROPOSED.getId());
        query.setMaxResults(MAX_OPEN_ACTIONS);
        return query.list();
    }

    private List<Map<String, Object>> loadActiveTemplatesList(Session session, int projectId) {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (ActionNext template : loadActiveTemplates(session, projectId)) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("actionNextId", template.getActionNextId());
            item.put("description", template.getNextDescription());
            item.put("cadence", template.getTemplateType() == null ? null : template.getTemplateType().getLabel());
            list.add(item);
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private List<ActionNext> loadActiveTemplates(Session session, int projectId) {
        Query query = session.createQuery(
                "from ActionNext an where an.projectId = :projectId "
                        + "and an.templateTypeString is not null and an.templateTypeString <> '' "
                        + "order by an.nextDescription");
        query.setParameter("projectId", projectId);
        return query.list();
    }

    private List<Map<String, Object>> loadOpenIssuesList(Session session, Project project) {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        List<ProjectIssue> issues = new ProjectIssueDao(session).listOpenIssuesForProject(project);
        int count = 0;
        for (ProjectIssue issue : issues) {
            if (count >= MAX_OPEN_ISSUES) {
                break;
            }
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("issueType", issue.getIssueType() == null ? null : issue.getIssueType().name());
            item.put("issueText", issue.getIssueText());
            list.add(item);
            count++;
        }
        return list;
    }

    private List<Map<String, Object>> loadRecentNarrativesList(Session session, int projectId) {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (ProjectNarrative narrative : loadRecentNarratives(session, projectId)) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("narrativeId", narrative.getNarrativeId());
            item.put("date", McpActionContextSupport.toIso(narrative.getNarrativeDate()));
            item.put("verb", narrative.getNarrativeVerb() == null ? null : narrative.getNarrativeVerb().getId());
            item.put("text", narrative.getNarrativeText());
            item.put("lastUpdated", McpActionContextSupport.toIso(narrative.getLastUpdated()));
            list.add(item);
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private List<ProjectNarrative> loadRecentNarratives(Session session, int projectId) {
        Query query = session.createQuery("from ProjectNarrative where projectId = :projectId order by narrativeDate desc");
        query.setParameter("projectId", projectId);
        query.setMaxResults(MAX_RECENT_NARRATIVES);
        return query.list();
    }
}
