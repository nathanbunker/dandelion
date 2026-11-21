package org.openimmunizationsoftware.pt.api.v1.mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.dandeliondaily.mcp.service.McpActionContextSupport;
import org.dandeliondaily.mcp.service.McpWebUserSupport;
import org.dandeliondaily.personal.service.PersonalUserSupport;
import org.dandeliondaily.sessions.service.AgentCatalogService;
import org.dandeliondaily.sessions.service.EligibleAgentWorkService;
import org.dandeliondaily.sessions.service.ProjectFolderService;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpArgs;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpSchema;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpTool;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpToolContext;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpToolException;
import org.openimmunizationsoftware.pt.model.Agent;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.WebUser;

import com.fasterxml.jackson.databind.JsonNode;

/** The agent list, the user's agent preferences, and the agent-key work list (read-only). */
public class ListAgentWorkTool implements McpTool {

    private final AgentCatalogService catalog = new AgentCatalogService();
    private final EligibleAgentWorkService eligibleService = new EligibleAgentWorkService();
    private final ProjectFolderService folderService = new ProjectFolderService();

    @Override
    public String getName() {
        return "list_agent_work";
    }

    @Override
    public String getDescription() {
        return "Lists the agents Dandelion knows (key, name, capabilities), the user's default agent and per-agent "
                + "model, and, for an agentKey, the user's open agent actions performed by that agent that are due "
                + "today or overdue (by the user's local date), with notes and the project's primary folder. "
                + "Templates, future actions, blocked actions, actions already running and failed actions waiting "
                + "for Retry are not listed. Read-only: listing claims nothing.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return McpSchema.object(McpSchema.properties(
                "agentKey", McpSchema.string("Optional agent key (claude-code, codex) whose due work to list.")));
    }

    @Override
    public Object call(JsonNode arguments, McpToolContext context) {
        WebUser webUser = McpWebUserSupport.requireWebUser(context.getSession(), context.getUsername());
        WebUser zoned = PersonalUserSupport.loadWebUser(context.getSession(), context.getUsername());
        if (zoned != null) {
            webUser.setTimeZone(zoned.getTimeZone());
        }
        String agentKey = McpArgs.optString(arguments, "agentKey", null);
        if (agentKey != null && catalog.findActive(context.getSession(), agentKey) == null) {
            throw new McpToolException("invalid_arguments", "Unknown agent \"" + agentKey + "\".");
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> agents = new ArrayList<Map<String, Object>>();
        for (Agent agent : catalog.listActive(context.getSession())) {
            Map<String, Object> map = AgentCatalogService.toMap(agent);
            map.put("model", catalog.model(context.getSession(), webUser, agent.getAgentKey()));
            agents.add(map);
        }
        result.put("agents", agents);
        result.put("defaultAgent", catalog.defaultAgent(context.getSession(), webUser));
        result.put("today", webUser.getLocalDateToday().toString());
        if (agentKey != null) {
            List<Map<String, Object>> work = new ArrayList<Map<String, Object>>();
            for (ActionNext action : eligibleService.eligibleWithoutSync(context.getSession(), webUser,
                    context.getWorkspaceId(), agentKey, webUser.getLocalDateToday())) {
                Map<String, Object> map = McpActionContextSupport.toActionMap(context.getSession(), action, true,
                        webUser);
                map.put("primaryFolder", folderService.describe(context.getSession(), action.getProjectId())
                        .get("primaryFolder"));
                work.add(map);
            }
            result.put("agentKey", agentKey);
            result.put("work", work);
        }
        return result;
    }
}
