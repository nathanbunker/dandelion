package org.dandeliondaily.mcp.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.dandeliondaily.shared.service.ProjectIconService;
import org.hibernate.Query;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpToolException;
import org.openimmunizationsoftware.pt.model.Project;

/**
 * update_project_icon: sets or clears a project's emoji through ProjectIconService, the same rules
 * the dashboard project form uses. Writes stay within the client's workspace, like the other MCP
 * project writes. The icon is cosmetic, so lastModifiedDate is left alone: a batch of icon changes
 * should not make every project look recently active or make language edits go stale.
 */
public class McpProjectIconService {

    private final ProjectIconService projectIconService = new ProjectIconService();

    public Map<String, Object> updateIcon(Session session, int workspaceId, int projectId, String icon) {
        Project project = requireProjectInWorkspace(session, workspaceId, projectId);
        String previous;
        try {
            previous = projectIconService.setIcon(project, icon);
        } catch (ProjectIconService.InvalidIconException invalid) {
            throw new McpToolException("invalid_arguments", invalid.getMessage());
        }
        session.update(project);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("projectId", project.getProjectId());
        result.put("projectName", project.getProjectName());
        result.put("icon", project.getProjectIcon());
        result.put("previousIcon", previous);
        return result;
    }

    private Project requireProjectInWorkspace(Session session, int workspaceId, int projectId) {
        Query query = session.createQuery(
                "from Project p where p.projectId = :projectId and p.workspaceId = :workspaceId");
        query.setInteger("projectId", projectId);
        query.setInteger("workspaceId", workspaceId);
        Project project = (Project) query.uniqueResult();
        if (project == null) {
            throw new McpToolException("not_found", "Project not found.");
        }
        return project;
    }
}
