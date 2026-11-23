package org.openimmunizationsoftware.pt.api.v1.mcp.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.dandeliondaily.mcp.service.McpProjectAccess;
import org.dandeliondaily.mcp.service.McpWebUserSupport;
import org.dandeliondaily.sessions.service.ProjectFolderService;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpArgs;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpSchema;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpTool;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpToolContext;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpToolException;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.model.WebUser;

import com.fasterxml.jackson.databind.JsonNode;

/** Sets a project's primary folder and ordered, labeled related folders (the project editor's fields). */
public class UpdateProjectFoldersTool implements McpTool {

    private final ProjectFolderService service = new ProjectFolderService();

    @Override
    public String getName() {
        return "update_project_folders";
    }

    @Override
    public String getDescription() {
        return "Replaces a project's folders, after the user has approved them: one primary folder and an ordered "
                + "list of labeled related folders. Each folder is a known root plus a relative path: brain:, "
                + "dropbox:, dev: or aira: (e.g. brain:projects/work-hygiene). Absolute paths, drive letters, \"..\" and "
                + "unknown roots are rejected. An empty primaryFolder removes it. get_project_context returns the "
                + "current folders.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> related = McpSchema.object(McpSchema.properties(
                "label", McpSchema.string("Optional short label shown in the Folder menu."),
                "folder", McpSchema.string("root:relative/path")), "folder");
        return McpSchema.object(McpSchema.properties(
                "projectId", McpSchema.integer("The Dandelion project id."),
                "primaryFolder", McpSchema.string("root:relative/path, or \"\" to remove."),
                "relatedFolders", McpSchema.array("Related folders in display order.", related)),
                "projectId", "primaryFolder");
    }

    @Override
    public Object call(JsonNode arguments, McpToolContext context) {
        int projectId = McpArgs.requireInt(arguments, "projectId");
        WebUser webUser = McpWebUserSupport.requireWebUser(context.getSession(), context.getUsername());
        Project project = McpProjectAccess.requireReadable(context.getSession(), context.getWorkspaceId(), webUser,
                projectId);
        if (project.getWorkspaceId().intValue() != context.getWorkspaceId()) {
            throw new McpToolException("forbidden", "Folders can only be set on projects in this workspace.");
        }
        List<ProjectFolderService.RelatedInput> inputs = new ArrayList<ProjectFolderService.RelatedInput>();
        for (JsonNode item : McpArgs.optNodeList(arguments, "relatedFolders")) {
            inputs.add(new ProjectFolderService.RelatedInput(item.hasNonNull("label") ? item.get("label").asText() : null,
                    item.hasNonNull("folder") ? item.get("folder").asText() : null));
        }
        try {
            service.replace(context.getSession(), projectId, McpArgs.optString(arguments, "primaryFolder", ""), inputs);
        } catch (IllegalArgumentException invalid) {
            throw new McpToolException("invalid_arguments", invalid.getMessage());
        }
        context.getSession().flush();
        return service.describe(context.getSession(), projectId);
    }
}
