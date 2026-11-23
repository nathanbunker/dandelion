package org.openimmunizationsoftware.pt.api.v1.mcp.tools;

import java.util.Map;

import org.dandeliondaily.mcp.service.McpTemplateService;
import org.dandeliondaily.mcp.service.McpWebUserSupport;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpArgs;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpSchema;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpTool;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpToolContext;
import org.openimmunizationsoftware.pt.model.WebUser;

import com.fasterxml.jackson.databind.JsonNode;

public class ListTemplatesTool implements McpTool {

    private final McpTemplateService service = new McpTemplateService();

    @Override
    public String getName() {
        return "list_templates";
    }

    @Override
    public String getDescription() {
        return "Lists the user's recurring action templates (the Templates page under Plan Ahead), work and "
                + "personal, with each one's full definition: description, project, templateType and schedule "
                + "pattern, autoGenerate, missedActionBehavior, nextActionType (work) or timeSlot (personal), "
                + "estimate, link, notes, the agent that performs it (if any) with its handoff, and its next few "
                + "generated actions. Read-only. Use it before update_template: pass a template's asOf back to "
                + "update_template to be sure nothing changed in between.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return McpSchema.object(McpSchema.properties(
                "mode", McpSchema.stringEnum("WORK (billable), PERSONAL, or ALL (default).", "WORK", "PERSONAL",
                        "ALL"),
                "projectId", McpSchema.integer("Only templates on this project."),
                "includeInactive", McpSchema.bool("Also list deactivated (deleted) templates. Default false.")));
    }

    @Override
    public Object call(JsonNode arguments, McpToolContext context) {
        WebUser webUser = McpWebUserSupport.requireWebUser(context.getSession(), context.getUsername());
        return service.list(context.getSession(), context.getWorkspaceId(), webUser,
                McpArgs.optString(arguments, "mode", "ALL"), McpArgs.optInt(arguments, "projectId", null),
                McpArgs.optBool(arguments, "includeInactive", false));
    }
}
