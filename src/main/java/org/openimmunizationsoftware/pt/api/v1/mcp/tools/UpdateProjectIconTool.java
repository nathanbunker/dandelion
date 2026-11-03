package org.openimmunizationsoftware.pt.api.v1.mcp.tools;

import java.util.Map;

import org.dandeliondaily.mcp.service.McpProjectIconService;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpArgs;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpSchema;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpTool;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpToolContext;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpToolException;

import com.fasterxml.jackson.databind.JsonNode;

public class UpdateProjectIconTool implements McpTool {

    private final McpProjectIconService service = new McpProjectIconService();

    @Override
    public String getName() {
        return "update_project_icon";
    }

    @Override
    public String getDescription() {
        return "Sets or clears a project's icon, after the user has explicitly approved it. The icon is a "
                + "single emoji shown beside the project on the Focused Action page, Todo lists and other views, "
                + "so the user can spot the project at a glance; list_projects returns each project's current "
                + "projectIcon. Up to 8 characters; an empty string clears it. Only changes the icon.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return McpSchema.object(
                McpSchema.properties(
                        "projectId", McpSchema.integer("The Dandelion project id."),
                        "icon", McpSchema.string("The emoji to show for the project, or an empty string to clear it.")),
                "projectId", "icon");
    }

    @Override
    public Object call(JsonNode arguments, McpToolContext context) {
        int projectId = McpArgs.requireInt(arguments, "projectId");
        if (arguments == null || !arguments.hasNonNull("icon")) {
            throw new McpToolException("invalid_arguments",
                    "\"icon\" is required; pass an empty string to clear the icon.");
        }
        return service.updateIcon(context.getSession(), context.getWorkspaceId(), projectId,
                arguments.get("icon").asText());
    }
}
