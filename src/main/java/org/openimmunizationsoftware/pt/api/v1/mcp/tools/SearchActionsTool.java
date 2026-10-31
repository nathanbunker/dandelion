package org.openimmunizationsoftware.pt.api.v1.mcp.tools;

import java.util.Map;
import org.dandeliondaily.mcp.service.McpDiscoveryService;
import org.dandeliondaily.mcp.service.McpWebUserSupport;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpSchema;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpTool;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpToolContext;
import com.fasterxml.jackson.databind.JsonNode;

public class SearchActionsTool implements McpTool {
    public String getName() { return "search_actions"; }
    public String getDescription() {
        return "Searches action descriptions (optionally notes) by literal case-insensitive text in the client's "
                + "workspace, including open/completed/cancelled actions. Dates yyyy-MM-dd inclusive, max366 days. "
                + "Matches scheduled date OR attributable recorded-work date for a closed action. Work is attributable "
                + "only if exactly one action anywhere shares its action set and work matches the current project/workspace; otherwise completionDate "
                + "is null, completionDateKnown=false and only scheduled-date matching is possible. Modification time "
                + "is never presented as completion time. completionDateUnknownReason is NOT_COMPLETED or "
                + "NO_UNAMBIGUOUS_WORK_RECORD, null when known. Scheduled/deadline/target dates are yyyy-MM-dd; "
                + "closed actions have dashboardBucket:null. Returns action asOf, project, notes (one plain-text value, "
                + "as stored) and linkedMeeting. "
                + "Stable action-ID pagination: limit default100 max200, offset default0. Does not search shared "
                + "workspaces or disclose private sibling actions. Read-only.";
    }
    public Map<String, Object> getInputSchema() {
        return McpSchema.object(McpSchema.properties(
                "text", McpSchema.string("Required literal text, max200."),
                "includeNotes", McpSchema.bool("Default false. Also match the action's notes text."),
                "startDate", McpSchema.string("yyyy-MM-dd inclusive."),
                "endDate", McpSchema.string("yyyy-MM-dd inclusive."),
                "actionTypes", McpSchema.array("Optional nonempty action-type list.", McpSchema.string(null)),
                "statuses", McpSchema.array("Default all.", McpSchema.stringEnum(null, "OPEN", "COMPLETED", "CANCELLED")),
                "projectIds", McpSchema.array("Optional nonempty project-ID list.", McpSchema.integer(null)),
                "hasLinkedMeeting", McpSchema.bool("Filter linked/unlinked."),
                "limit", McpSchema.integer("1-200, default100."),
                "offset", McpSchema.integer("Nonnegative, default0.")), "text", "startDate", "endDate");
    }
    public Object call(JsonNode args, McpToolContext context) {
        return new McpDiscoveryService().searchActions(context.getSession(), context.getWorkspaceId(),
                McpWebUserSupport.requireWebUser(context.getSession(), context.getUsername()), args);
    }
}
